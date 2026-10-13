package com.uxplima.uxmskyblock.bukkit.oneblock;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

import org.bukkit.World;
import org.bukkit.block.Biome;

import com.uxplima.uxmskyblock.bukkit.config.OneBlockBiome;
import com.uxplima.uxmskyblock.bukkit.config.OneBlockConfiguration;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;

/**
 * The biome a OneBlock phase gives its island when it begins, as its file names it: over the whole island, or over
 * the blocks within a distance of its block. Each chunk is painted on its own region, top to bottom, and sent again
 * to the players who see it, so the grass and the sky change at once. A phase that names no biome leaves the one
 * before it.
 */
public final class OneBlockBiomes {

    /** How the players who see a chunk are sent it again once its biome changed. */
    interface Resend {
        void chunk(World world, int chunkX, int chunkZ);
    }

    /** A biome is kept per cell of four blocks each way, so one block in four is enough to paint it. */
    private static final int CELL = 4;

    private final Supplier<OneBlockConfiguration> config;
    private final SchedulerPort scheduler;
    private final Function<IslandId, Optional<Island>> islands;
    private final Resend resend;

    OneBlockBiomes(
            Supplier<OneBlockConfiguration> config,
            SchedulerPort scheduler,
            Function<IslandId, Optional<Island>> islands,
            Resend resend) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.islands = Objects.requireNonNull(islands, "islands must not be null");
        this.resend = Objects.requireNonNull(resend, "resend must not be null");
    }

    /**
     * As on a server: a painted chunk is sent again to everyone who sees it.
     *
     * @param islands an island read from storage, off the server's threads, for one that is only just made
     */
    public OneBlockBiomes(
            Supplier<OneBlockConfiguration> config,
            SchedulerPort scheduler,
            Function<IslandId, Optional<Island>> islands) {
        this(config, scheduler, islands, OneBlockBiomes::refresh);
    }

    /**
     * Gives the island the biome of {@code phase}, once the island is read from storage off the server's threads:
     * for an island only just made, or one an operator moved to another phase.
     */
    public void paint(IslandId islandId, World world, int x, int z, String phase) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        if (config.get().biomeOf(phase).isEmpty()) {
            return;
        }
        scheduler.async(() -> islands.apply(islandId).ifPresent(island -> began(island, world, x, z, phase)));
    }

    /** Gives the island the biome of {@code phase}, which just began at its block at {@code x}, {@code z}. */
    public void began(Island island, World world, int x, int z, String phase) {
        Objects.requireNonNull(island, "island must not be null");
        Objects.requireNonNull(world, "world must not be null");
        config.get().biomeOf(phase).ifPresent(biome -> paint(island.bounds(), world, x, z, biome));
    }

    private void paint(IslandBounds bounds, World world, int x, int z, OneBlockBiome written) {
        int minX = written.wholeIsland() ? bounds.minX() : x - written.radius();
        int maxX = written.wholeIsland() ? bounds.maxX() : x + written.radius();
        int minZ = written.wholeIsland() ? bounds.minZ() : z - written.radius();
        int maxZ = written.wholeIsland() ? bounds.maxZ() : z + written.radius();
        Biome biome = written.biome();
        for (int chunkX = minX >> 4; chunkX <= maxX >> 4; chunkX++) {
            for (int chunkZ = minZ >> 4; chunkZ <= maxZ >> 4; chunkZ++) {
                int cx = chunkX;
                int cz = chunkZ;
                scheduler.onRegion(world.getName(), cx, cz, () -> {
                    int fromX = Math.max(minX, cx << 4);
                    int toX = Math.min(maxX, (cx << 4) + 15);
                    int fromZ = Math.max(minZ, cz << 4);
                    int toZ = Math.min(maxZ, (cz << 4) + 15);
                    // From the cell the edge falls in, so a cell the area only reaches into is painted too.
                    for (int bx = Math.floorDiv(fromX, CELL) * CELL; bx <= toX; bx += CELL) {
                        for (int bz = Math.floorDiv(fromZ, CELL) * CELL; bz <= toZ; bz += CELL) {
                            for (int by = world.getMinHeight(); by < world.getMaxHeight(); by += CELL) {
                                world.setBiome(bx, by, bz, biome);
                            }
                        }
                    }
                    resend.chunk(world, cx, cz);
                });
            }
        }
    }

    // The only way the server offers to send a chunk again, so a biome shows without the player leaving it.
    @SuppressWarnings("deprecation")
    private static void refresh(World world, int chunkX, int chunkZ) {
        world.refreshChunk(chunkX, chunkZ);
    }
}
