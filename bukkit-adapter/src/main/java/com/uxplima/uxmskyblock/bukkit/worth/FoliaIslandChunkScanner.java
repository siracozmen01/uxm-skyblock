package com.uxplima.uxmskyblock.bukkit.worth;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.CreatureSpawner;

import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.worth.IslandChunkScannerPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;

/**
 * Region-aware chunk scanner implementing {@link IslandChunkScannerPort} for Paper/Folia.
 *
 * <p>Dispatches chunk-by-chunk block and tile-entity scans across Folia region threads,
 * aggregating counts only for explicitly declared catalog blocks and mob spawners.
 */
public final class FoliaIslandChunkScanner implements IslandChunkScannerPort {

    private final SchedulerPort schedulerPort;
    private final Set<String> trackedBlocks;
    private final Set<String> trackedSpawners;

    public FoliaIslandChunkScanner(
            SchedulerPort schedulerPort, Set<String> trackedBlocks, Set<String> trackedSpawners) {
        this.schedulerPort = Objects.requireNonNull(schedulerPort, "schedulerPort must not be null");
        this.trackedBlocks = Set.copyOf(trackedBlocks);
        this.trackedSpawners = Set.copyOf(trackedSpawners);
    }

    @Override
    public void scanIsland(
            IslandId islandId,
            String worldName,
            IslandBounds bounds,
            BiConsumer<Map<String, Integer>, Map<String, Integer>> callback) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(worldName, "worldName must not be null");
        Objects.requireNonNull(bounds, "bounds must not be null");
        Objects.requireNonNull(callback, "callback must not be null");

        int minChunkX = bounds.minX() >> 4;
        int maxChunkX = bounds.maxX() >> 4;
        int minChunkZ = bounds.minZ() >> 4;
        int maxChunkZ = bounds.maxZ() >> 4;

        int totalChunks = (maxChunkX - minChunkX + 1) * (maxChunkZ - minChunkZ + 1);
        if (totalChunks <= 0) {
            callback.accept(Map.of(), Map.of());
            return;
        }

        ConcurrentHashMap<String, AtomicInteger> blockCounts = new ConcurrentHashMap<>();
        ConcurrentHashMap<String, AtomicInteger> spawnerCounts = new ConcurrentHashMap<>();
        AtomicInteger remainingChunks = new AtomicInteger(totalChunks);

        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                final int chunkX = cx;
                final int chunkZ = cz;
                schedulerPort.onRegion(worldName, chunkX, chunkZ, () -> {
                    try {
                        World world = Bukkit.getWorld(worldName);
                        if (world == null) {
                            return;
                        }
                        Chunk chunk = world.getChunkAt(chunkX, chunkZ);

                        // 1. Scan tile entities for creature spawners
                        for (BlockState state : chunk.getTileEntities()) {
                            if (state instanceof CreatureSpawner spawner) {
                                if (bounds.contains(state.getX(), state.getZ())) {
                                    String entityType = spawner.getSpawnedType() != null
                                            ? spawner.getSpawnedType().getKey().toString()
                                            : "minecraft:pig";
                                    if (trackedSpawners.isEmpty() || trackedSpawners.contains(entityType)) {
                                        spawnerCounts
                                                .computeIfAbsent(entityType, k -> new AtomicInteger(0))
                                                .incrementAndGet();
                                    }
                                }
                            }
                        }

                        // 2. Scan blocks within chunk bounds that intersect island bounds
                        int startX = Math.max(chunkX << 4, bounds.minX());
                        int endX = Math.min((chunkX << 4) + 15, bounds.maxX());
                        int startZ = Math.max(chunkZ << 4, bounds.minZ());
                        int endZ = Math.min((chunkZ << 4) + 15, bounds.maxZ());
                        int minY = Math.max(world.getMinHeight(), 0);
                        int maxY = Math.min(world.getMaxHeight(), 320);

                        for (int x = startX; x <= endX; x++) {
                            for (int z = startZ; z <= endZ; z++) {
                                for (int y = minY; y < maxY; y++) {
                                    Block block = world.getBlockAt(x, y, z);
                                    if (block.isEmpty()) {
                                        continue;
                                    }
                                    String matKey = block.getType().getKey().toString();
                                    if (trackedBlocks.contains(matKey)) {
                                        blockCounts
                                                .computeIfAbsent(matKey, k -> new AtomicInteger(0))
                                                .incrementAndGet();
                                    }
                                }
                            }
                        }

                        // 3. A stacked block stands for more than one: count the rest of its stack too.
                        stackedBeyondOne(chunk, world, bounds, minY, maxY)
                                .forEach((matKey, more) -> blockCounts
                                        .computeIfAbsent(matKey, k -> new AtomicInteger(0))
                                        .addAndGet(more));
                    } finally {
                        if (remainingChunks.decrementAndGet() == 0) {
                            Map<String, Integer> finalBlocks = new HashMap<>();
                            blockCounts.forEach((k, v) -> finalBlocks.put(k, v.get()));
                            Map<String, Integer> finalSpawners = new HashMap<>();
                            spawnerCounts.forEach((k, v) -> finalSpawners.put(k, v.get()));

                            callback.accept(
                                    Collections.unmodifiableMap(finalBlocks),
                                    Collections.unmodifiableMap(finalSpawners));
                        }
                    }
                });
            }
        }
    }

    /**
     * How many blocks the stacks in {@code chunk} stand for beyond the one each is in the world, by kind, for the
     * stacks inside {@code bounds} and the heights scanned, of a kind the scan counts.
     */
    Map<String, Integer> stackedBeyondOne(Chunk chunk, World world, IslandBounds bounds, int minY, int maxY) {
        Map<String, Integer> more = new HashMap<>();
        com.uxplima.uxmskyblock.bukkit.stack.BlockStacks.in(chunk).forEach((place, size) -> {
            if (!bounds.contains(place.x(), place.z()) || place.y() < minY || place.y() >= maxY) {
                return;
            }
            String matKey = world.getBlockAt(place.x(), place.y(), place.z())
                    .getType()
                    .getKey()
                    .toString();
            if (trackedBlocks.contains(matKey)) {
                more.merge(matKey, size - 1, Integer::sum);
            }
        });
        return more;
    }

    public Set<String> trackedBlocks() {
        return trackedBlocks;
    }

    public Set<String> trackedSpawners() {
        return trackedSpawners;
    }
}
