package com.uxplima.uxmskyblock.bukkit.spatial;

import java.util.Collection;
import java.util.Collections;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.LongAdder;

import org.bukkit.Location;

import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import org.jspecify.annotations.Nullable;

/**
 * High-performance O(1) in-memory spatial chunk index for Skyblock islands.
 *
 * <p>Eliminates O(N) iterative boundary scanning and eliminates synchronous relational
 * database lookups on high-frequency Bukkit event threads (BlockBreak, BlockPlace, Move, Interact).
 */
public final class SpatialIslandIndex {

    /**
     * How often this index answered from memory. The protection path asks it for every block a
     * player touches, so the ratio of these two is the one number that says whether the hot path is
     * actually hot. A miss costs a background refresh and a refused interaction until it lands.
     */
    private final LongAdder lookups = new LongAdder();

    private final LongAdder hits = new LongAdder();

    public record SpatialChunkKey(String world, int chunkX, int chunkZ) {
        public SpatialChunkKey {
            Objects.requireNonNull(world, "world must not be null");
        }
    }

    private final ConcurrentMap<SpatialChunkKey, IslandId> chunkToIsland = new ConcurrentHashMap<>();
    private final ConcurrentMap<IslandId, Island> islandMap = new ConcurrentHashMap<>();
    private final ConcurrentMap<IslandId, String> islandWorldMap = new ConcurrentHashMap<>();
    private final ConcurrentMap<IslandId, Set<SpatialChunkKey>> islandChunksMap = new ConcurrentHashMap<>();
    private final Set<SpatialChunkKey> pendingLookups = ConcurrentHashMap.newKeySet();

    private final @Nullable IslandStoragePort storagePort;
    private final @Nullable SchedulerPort schedulerPort;

    public SpatialIslandIndex(@Nullable IslandStoragePort storagePort, @Nullable SchedulerPort schedulerPort) {
        this.storagePort = storagePort;
        this.schedulerPort = schedulerPort;
    }

    public SpatialIslandIndex() {
        this(null, null);
    }

    /**
     * Indexes an island across all chunks covered by its spatial boundaries in O(chunks).
     */
    public void indexIsland(Island island, String worldName) {
        Objects.requireNonNull(island, "island must not be null");
        Objects.requireNonNull(worldName, "worldName must not be null");

        IslandId id = island.id();
        islandMap.put(id, island);
        islandWorldMap.put(id, worldName);

        int minCX = island.bounds().minX() >> 4;
        int maxCX = island.bounds().maxX() >> 4;
        int minCZ = island.bounds().minZ() >> 4;
        int maxCZ = island.bounds().maxZ() >> 4;

        Set<SpatialChunkKey> newKeys = ConcurrentHashMap.newKeySet();
        for (int cx = minCX; cx <= maxCX; cx++) {
            for (int cz = minCZ; cz <= maxCZ; cz++) {
                SpatialChunkKey key = new SpatialChunkKey(worldName, cx, cz);
                chunkToIsland.put(key, id);
                newKeys.add(key);
            }
        }

        // An island stands in each of its worlds at once, its Nether and End over its overworld: indexing it in one
        // world drops only that world's chunks it no longer covers, and keeps it in every other.
        Set<SpatialChunkKey> keys = islandChunksMap.computeIfAbsent(id, unused -> ConcurrentHashMap.newKeySet());
        keys.removeIf(oldKey -> {
            boolean dropped = oldKey.world().equals(worldName) && !newKeys.contains(oldKey);
            if (dropped) {
                chunkToIsland.remove(oldKey, id);
            }
            return dropped;
        });
        keys.addAll(newKeys);
    }

    /**
     * Removes an island and purges all of its chunk mappings from the spatial index.
     */
    public void removeIsland(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        islandMap.remove(islandId);
        islandWorldMap.remove(islandId);
        Set<SpatialChunkKey> keys = islandChunksMap.remove(islandId);
        if (keys != null) {
            for (SpatialChunkKey key : keys) {
                chunkToIsland.remove(key, islandId);
            }
        }
    }

    /**
     * Finds the island encompassing the given location in O(1) time without blocking on relational DB I/O.
     */
    public Optional<Island> findIslandAt(Location location) {
        if (location == null || location.getWorld() == null) {
            return Optional.empty();
        }
        return findIslandAt(location.getWorld().getName(), location.getBlockX(), location.getBlockZ());
    }

    /** The worlds islands are kept by, and the Nether and End worlds that stand over them at the same places. */
    private volatile java.util.List<String> islandWorlds = java.util.List.of();

    private volatile Set<String> mirrors = Set.of();

    /**
     * Names the worlds that mirror the island worlds: an island's Nether and End stand at its own places there, and
     * the database keeps the island by its island world alone. An island read in an island world is indexed in every
     * mirror too, and a miss in a mirror is read by the island worlds.
     */
    public void mirror(java.util.Collection<String> islandWorlds, java.util.Collection<String> mirrorWorlds) {
        this.islandWorlds = java.util.List.copyOf(islandWorlds);
        this.mirrors = Set.copyOf(mirrorWorlds);
    }

    /** The island at a place of {@code worldName}, read from the database by the world the island is kept by. */
    private Optional<Island> stored(String worldName, int blockX, int blockZ) {
        if (storagePort == null) {
            return Optional.empty();
        }
        if (!mirrors.contains(worldName)) {
            return storagePort.findIslandByLocation(worldName, blockX, blockZ);
        }
        for (String kept : islandWorlds) {
            Optional<Island> found = storagePort.findIslandByLocation(kept, blockX, blockZ);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    /**
     * Finds the island encompassing the world and block coordinates in O(1) time.
     * In production (when schedulerPort is present), on cache miss returns empty and dispatches
     * an asynchronous background refresh without stalling the server thread.
     */
    public Optional<Island> findIslandAt(String worldName, int blockX, int blockZ) {
        Objects.requireNonNull(worldName, "worldName must not be null");

        int chunkX = blockX >> 4;
        int chunkZ = blockZ >> 4;
        SpatialChunkKey key = new SpatialChunkKey(worldName, chunkX, chunkZ);

        lookups.increment();
        IslandId islandId = chunkToIsland.get(key);
        if (islandId != null) {
            Island island = islandMap.get(islandId);
            if (island != null && island.bounds().contains(blockX, blockZ)) {
                hits.increment();
                return Optional.of(island);
            }
        }

        // Cache miss: if asynchronous scheduler is present, dispatch async refresh and return empty (zero sync DB I/O
        // on hot path)
        if (storagePort != null) {
            if (schedulerPort != null) {
                if (pendingLookups.add(key)) {
                    schedulerPort.async(() -> {
                        try {
                            Optional<Island> persisted = stored(worldName, blockX, blockZ);
                            persisted.ifPresent(is -> indexIsland(is, worldName));
                        } finally {
                            pendingLookups.remove(key);
                        }
                    });
                }
            } else {
                // Synchronous fallback only when running in offline unit tests without scheduler
                Optional<Island> persisted = stored(worldName, blockX, blockZ);
                persisted.ifPresent(is -> indexIsland(is, worldName));
                return persisted;
            }
        }

        return Optional.empty();
    }

    /**
     * Bulk loads and indexes all islands persisted for the specified world.
     */
    public void warmFromStorage(String worldName) {
        Objects.requireNonNull(worldName, "worldName must not be null");
        if (storagePort == null) {
            return;
        }
        boolean islandWorld = islandWorlds.contains(worldName);
        for (Island island : storagePort.findAllByWorld(worldName)) {
            indexIsland(island, worldName);
            if (islandWorld) {
                for (String mirror : mirrors) {
                    indexIsland(island, mirror);
                }
            }
        }
    }

    public Optional<Island> getCachedIsland(IslandId islandId) {
        return Optional.ofNullable(islandMap.get(islandId));
    }

    /** The share of lookups answered from memory, or 1.0 before anything has asked. */
    public double hitRatio() {
        long asked = lookups.sum();
        if (asked <= 0) {
            return 1.0;
        }
        return (double) hits.sum() / (double) asked;
    }

    /** How many islands this index holds, which is how many are live on this node. */
    public int cachedIslandCount() {
        return islandMap.size();
    }

    public Collection<Island> allCachedIslands() {
        return Collections.unmodifiableCollection(islandMap.values());
    }

    public int totalIndexedIslands() {
        return islandMap.size();
    }

    public int totalIndexedChunks() {
        return chunkToIsland.size();
    }

    public void clear() {
        chunkToIsland.clear();
        islandMap.clear();
        islandWorldMap.clear();
        islandChunksMap.clear();
        pendingLookups.clear();
    }
}
