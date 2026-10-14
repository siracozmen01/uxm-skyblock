package com.uxplima.uxmskyblock.bukkit.brix;

import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

import com.uxplima.uxmskyblock.bukkit.config.BrixConfiguration;
import com.uxplima.uxmskyblock.bukkit.schematic.IslandStart;
import com.uxplima.uxmskyblock.core.application.brix.BrixService;
import com.uxplima.uxmskyblock.core.application.gamemode.CreationActionProvider;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/**
 * The creation action that makes an island a Brix plot: records it and lays the flat ground it is built
 * on, its top layer at the height players arrive on, filling only air.
 *
 * <p>The chunk the island's centre stands in is laid at once, on the thread running the creation, so the
 * ground is there when players arrive. Every other chunk is laid on the thread of the region that owns
 * it. A ground of no layers lays nothing, and the plot is void.
 */
public final class PlotStart implements CreationActionProvider<IslandStart> {

    /** The name a preset's start list writes for this action. */
    public static final String ACTION = "uxm:plot";

    private static final Logger LOGGER = Logger.getLogger(PlotStart.class.getName());

    private final BrixService service;
    private final SchedulerPort scheduler;
    private final BrixConfiguration.Ground ground;

    public PlotStart(BrixService service, SchedulerPort scheduler, BrixConfiguration.Ground ground) {
        this.service = Objects.requireNonNull(service, "service must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.ground = Objects.requireNonNull(ground, "ground must not be null");
    }

    @Override
    public String actionId() {
        return ACTION;
    }

    /** The ground reaches as far as brix.conf says. */
    @Override
    public java.util.List<com.uxplima.uxmskyblock.core.application.gamemode.CreationReach> reach(IslandStart start) {
        return java.util.List.of(new com.uxplima.uxmskyblock.core.application.gamemode.CreationReach(
                start.world().getName(), ground.radius()));
    }

    @Override
    public void apply(IslandStart start) {
        IslandId islandId = start.islandId();
        scheduler.async(() -> {
            try {
                service.start(islandId);
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, e, () -> "Island " + islandId + " could not be made a Brix plot.");
            }
        });
        List<Material> layers = ground.layers();
        if (layers.isEmpty()) {
            return;
        }
        World world = start.world();
        int top = start.y();
        int minX = start.centerX() - ground.radius();
        int maxX = start.centerX() + ground.radius();
        int minZ = start.centerZ() - ground.radius();
        int maxZ = start.centerZ() + ground.radius();
        int centreChunkX = start.centerX() >> 4;
        int centreChunkZ = start.centerZ() >> 4;
        for (int chunkX = minX >> 4; chunkX <= maxX >> 4; chunkX++) {
            for (int chunkZ = minZ >> 4; chunkZ <= maxZ >> 4; chunkZ++) {
                int fromX = Math.max(minX, chunkX << 4);
                int toX = Math.min(maxX, (chunkX << 4) + 15);
                int fromZ = Math.max(minZ, chunkZ << 4);
                int toZ = Math.min(maxZ, (chunkZ << 4) + 15);
                Runnable lay = () -> lay(world, fromX, toX, fromZ, toZ, top, layers);
                if (chunkX == centreChunkX && chunkZ == centreChunkZ) {
                    lay.run();
                } else {
                    scheduler.onRegion(world.getName(), chunkX, chunkZ, lay);
                }
            }
        }
    }

    static void lay(World world, int fromX, int toX, int fromZ, int toZ, int top, List<Material> layers) {
        int lowest = Math.max(world.getMinHeight(), top - layers.size() + 1);
        for (int x = fromX; x <= toX; x++) {
            for (int z = fromZ; z <= toZ; z++) {
                for (int y = top; y >= lowest; y--) {
                    Block block = world.getBlockAt(x, y, z);
                    if (block.getType().isAir()) {
                        // No physics: sand laid a chunk at a time must not fall before the layer under it.
                        block.setType(layers.get(top - y), false);
                    }
                }
            }
        }
    }
}
