package com.uxplima.uxmskyblock.bukkit.boxed;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntitySnapshot;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;

import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import org.jspecify.annotations.Nullable;

/**
 * Copies a chunk of the template's land onto a chunk of an island: its blocks, its biomes, what its blocks hold, as
 * a chest its loot, and the creatures that stay on it, as the world's animals and villagers do.
 *
 * <p>The template's chunk is read on the thread that owns it and laid on the thread that owns the island's chunk. The
 * creatures that stay on the island's chunk give way to the template's; a creature a player named, tamed or
 * leashed, one that comes and goes, and every player, stays.
 */
public final class TemplateChunks implements BoxedLandCopier.ChunkCopy {

    private final SchedulerPort scheduler;
    private final Function<String, @Nullable World> worlds;

    /** @param worlds a world by its name, or null when there is none */
    public TemplateChunks(SchedulerPort scheduler, Function<String, @Nullable World> worlds) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.worlds = Objects.requireNonNull(worlds, "worlds");
    }

    /** A creature of the template's chunk, where it stood in the chunk. */
    private record Creature(EntitySnapshot snapshot, double x, double y, double z, float yaw, float pitch) {}

    /** What a chunk of the template holds. */
    private record Taken(ChunkSnapshot blocks, List<BlockState> states, List<Creature> creatures) {}

    @Override
    public CompletableFuture<Void> copy(String world, int fromX, int fromZ, int toX, int toZ) {
        World found = worlds.apply(world);
        if (found == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("There is no world called " + world));
        }
        return onChunk(found, fromX, fromZ, TemplateChunks::take)
                .thenCompose(taken -> onChunk(found, toX, toZ, chunk -> {
                    lay(chunk, taken);
                    return Boolean.TRUE;
                }))
                .thenApply(ignored -> null);
    }

    /** {@code then} on the chunk, once it is loaded, on the thread that owns it. */
    private <T> CompletableFuture<T> onChunk(World world, int x, int z, Function<Chunk, T> then) {
        CompletableFuture<T> done = new CompletableFuture<>();
        scheduler.onRegion(world.getName(), x, z, () -> {
            var unused = world.getChunkAtAsync(x, z).whenComplete((chunk, failed) -> {
                if (failed != null) {
                    done.completeExceptionally(failed);
                    return;
                }
                scheduler.onRegion(world.getName(), x, z, () -> {
                    try {
                        done.complete(then.apply(chunk));
                    } catch (RuntimeException e) {
                        done.completeExceptionally(e);
                    }
                });
            });
        });
        return done;
    }

    private static Taken take(Chunk chunk) {
        int baseX = chunk.getX() << 4;
        int baseZ = chunk.getZ() << 4;
        List<Creature> creatures = new ArrayList<>();
        for (Entity entity : chunk.getEntities()) {
            if (!madeWithTheLand(entity)) {
                continue;
            }
            EntitySnapshot snapshot = entity.createSnapshot();
            if (snapshot != null) {
                Location at = entity.getLocation();
                creatures.add(new Creature(
                        snapshot, at.getX() - baseX, at.getY(), at.getZ() - baseZ, at.getYaw(), at.getPitch()));
            }
        }
        return new Taken(chunk.getChunkSnapshot(false, true, false), List.of(chunk.getTileEntities()), creatures);
    }

    private static void lay(Chunk chunk, Taken taken) {
        World world = chunk.getWorld();
        int baseX = chunk.getX() << 4;
        int baseZ = chunk.getZ() << 4;
        for (Entity entity : chunk.getEntities()) {
            if (madeWithTheLand(entity)) {
                entity.remove();
            }
        }
        int min = world.getMinHeight();
        int max = world.getMaxHeight();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = min; y < max; y++) {
                    BlockData data = taken.blocks().getBlockData(x, y, z);
                    Block block = chunk.getBlock(x, y, z);
                    if (!block.getBlockData().equals(data)) {
                        // What the world only marked as holding something, as the deep dark's sculk does, is made
                        // whole by being read, so the block laid over it takes it away rather than leaving the mark.
                        var unused = block.getState(false);
                        block.setBlockData(data, false);
                    }
                }
            }
        }
        for (int x = 0; x < 16; x += 4) {
            for (int z = 0; z < 16; z += 4) {
                for (int y = min; y < max; y += 4) {
                    Biome biome = taken.blocks().getBiome(x, y, z);
                    if (!world.getBiome(baseX + x, y, baseZ + z).equals(biome)) {
                        world.setBiome(baseX + x, y, baseZ + z, biome);
                    }
                }
            }
        }
        for (BlockState state : taken.states()) {
            Location at = new Location(world, baseX + (state.getX() & 15), state.getY(), baseZ + (state.getZ() & 15));
            state.copy(at).update(true, false);
        }
        for (Creature creature : taken.creatures()) {
            creature.snapshot()
                    .createEntity(new Location(
                            world,
                            baseX + creature.x(),
                            creature.y(),
                            baseZ + creature.z(),
                            creature.yaw(),
                            creature.pitch()));
        }
    }

    /**
     * A creature that stays where the land put it, as the world's animals and villagers do, rather than one that
     * comes and goes, and that no player named, tamed or leashed.
     */
    private static boolean madeWithTheLand(Entity entity) {
        return entity instanceof LivingEntity living
                && !(entity instanceof Player)
                && !living.getRemoveWhenFarAway()
                && entity.customName() == null
                && !living.isLeashed()
                && !(entity instanceof Tameable tameable && tameable.isTamed());
    }
}
