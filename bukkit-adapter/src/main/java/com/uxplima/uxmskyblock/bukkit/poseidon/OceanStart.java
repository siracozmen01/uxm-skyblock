package com.uxplima.uxmskyblock.bukkit.poseidon;

import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

import com.uxplima.uxmskyblock.bukkit.config.PoseidonConfiguration;
import com.uxplima.uxmskyblock.bukkit.schematic.IslandStart;
import com.uxplima.uxmskyblock.core.application.gamemode.CreationActionProvider;
import com.uxplima.uxmskyblock.core.application.poseidon.PoseidonService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/**
 * The creation action that makes an island a Poseidon island: records it and lays the ocean it lies at
 * the bottom of, water from the floor to well over the arrival, filling only air.
 *
 * <p>The chunk the island's centre stands in is filled at once, on the thread running the creation, so
 * the water is there when players arrive. Every other chunk is filled on the thread of the region that
 * owns it. Run in the Nether, the same action floods it.
 */
public final class OceanStart implements CreationActionProvider<IslandStart> {

    /** The name a preset's start list writes for this action. */
    public static final String ACTION = "uxm:ocean";

    private static final Logger LOGGER = Logger.getLogger(OceanStart.class.getName());

    private final PoseidonService service;
    private final SchedulerPort scheduler;
    private final PoseidonConfiguration.Ocean ocean;

    public OceanStart(PoseidonService service, SchedulerPort scheduler, PoseidonConfiguration.Ocean ocean) {
        this.service = Objects.requireNonNull(service, "service must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.ocean = Objects.requireNonNull(ocean, "ocean must not be null");
    }

    @Override
    public String actionId() {
        return ACTION;
    }

    /** The sea reaches as far as poseidon.conf says. */
    @Override
    public java.util.List<com.uxplima.uxmskyblock.core.application.gamemode.CreationReach> reach(IslandStart start) {
        return java.util.List.of(new com.uxplima.uxmskyblock.core.application.gamemode.CreationReach(
                start.world().getName(), ocean.radius()));
    }

    @Override
    public void apply(IslandStart start) {
        IslandId islandId = start.islandId();
        scheduler.async(() -> {
            try {
                service.start(islandId);
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, e, () -> "Island " + islandId + " could not be made a Poseidon island.");
            }
        });
        Material floor = Material.matchMaterial(ocean.floor());
        if (floor == null || !floor.isBlock() || floor.hasGravity()) {
            LOGGER.warning(() -> "modules/poseidon.conf names the floor '" + ocean.floor()
                    + "', which is no block or one that falls. The ocean stands on sandstone.");
            floor = Material.SANDSTONE;
        }
        World world = start.world();
        int surface = start.y() + ocean.above();
        int bottom = start.y() - ocean.depth();
        int minX = start.centerX() - ocean.radius();
        int maxX = start.centerX() + ocean.radius();
        int minZ = start.centerZ() - ocean.radius();
        int maxZ = start.centerZ() + ocean.radius();
        int centreChunkX = start.centerX() >> 4;
        int centreChunkZ = start.centerZ() >> 4;
        Material bed = floor;
        for (int chunkX = minX >> 4; chunkX <= maxX >> 4; chunkX++) {
            for (int chunkZ = minZ >> 4; chunkZ <= maxZ >> 4; chunkZ++) {
                int fromX = Math.max(minX, chunkX << 4);
                int toX = Math.min(maxX, (chunkX << 4) + 15);
                int fromZ = Math.max(minZ, chunkZ << 4);
                int toZ = Math.min(maxZ, (chunkZ << 4) + 15);
                Runnable fill = () -> fill(world, fromX, toX, fromZ, toZ, bottom, surface, bed);
                if (chunkX == centreChunkX && chunkZ == centreChunkZ) {
                    fill.run();
                } else {
                    scheduler.onRegion(world.getName(), chunkX, chunkZ, fill);
                }
            }
        }
    }

    private static void fill(
            World world, int fromX, int toX, int fromZ, int toZ, int bottom, int surface, Material floor) {
        for (int x = fromX; x <= toX; x++) {
            for (int z = fromZ; z <= toZ; z++) {
                setIfAir(world.getBlockAt(x, bottom - 1, z), floor);
                for (int y = bottom; y <= surface; y++) {
                    setIfAir(world.getBlockAt(x, y, z), Material.WATER);
                }
            }
        }
    }

    private static void setIfAir(Block block, Material material) {
        if (block.getType().isAir()) {
            // No physics: an ocean laid a chunk at a time must not flow into the chunk laid next.
            block.setType(material, false);
        }
    }
}
