package com.uxplima.uxmskyblock.bukkit.tradewinds;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

import com.uxplima.uxmskyblock.bukkit.config.TradeWindsConfiguration;
import com.uxplima.uxmskyblock.bukkit.schematic.IslandStart;
import com.uxplima.uxmskyblock.core.application.gamemode.CreationActionProvider;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.tradewinds.VesselService;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/**
 * The creation action that makes an island a TradeWinds vessel: records it, lays the sea it floats on and
 * builds the ship, with players arriving on its deck.
 *
 * <p>The sea fills only air, from a floor up to three blocks under the arrival. The ship lies in it along
 * the east and west: a hull of planks with a dry hold under the deck, rails along the deck's edges and a
 * mast with a sail a little forward of where players arrive.
 *
 * <p>Every chunk is laid by the thread of the region that owns it, the centre chunk at once so the deck
 * is there when players arrive. Each chunk is filled with sea first and then gets its part of the ship,
 * so the hold is never flooded.
 */
public final class VesselStart implements CreationActionProvider<IslandStart> {

    /** The name a preset's start list writes for this action. */
    public static final String ACTION = "uxm:vessel";

    /** How far the hull reaches fore and aft of the arrival. */
    static final int HALF_LENGTH = 6;

    /** How far the hull reaches to either side, amidships. */
    static final int HALF_BEAM = 2;

    /** Where the mast stands, forward of the arrival. */
    static final int MAST = 2;

    /** How tall the mast is above the deck. */
    static final int MAST_HEIGHT = 8;

    private static final Logger LOGGER = Logger.getLogger(VesselStart.class.getName());

    private final VesselService service;
    private final SchedulerPort scheduler;
    private final TradeWindsConfiguration.Sea sea;

    public VesselStart(VesselService service, SchedulerPort scheduler, TradeWindsConfiguration.Sea sea) {
        this.service = Objects.requireNonNull(service, "service must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.sea = Objects.requireNonNull(sea, "sea must not be null");
    }

    @Override
    public String actionId() {
        return ACTION;
    }

    /** The sea the vessel sails on reaches as far as tradewinds.conf says. */
    @Override
    public java.util.List<com.uxplima.uxmskyblock.core.application.gamemode.CreationReach> reach(IslandStart start) {
        return java.util.List.of(new com.uxplima.uxmskyblock.core.application.gamemode.CreationReach(
                start.world().getName(), sea.radius()));
    }

    @Override
    public void apply(IslandStart start) {
        IslandId islandId = start.islandId();
        scheduler.async(() -> {
            try {
                service.start(islandId);
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, e, () -> "Island " + islandId + " could not be made a TradeWinds vessel.");
            }
        });
        Material floor = Material.matchMaterial(sea.floor());
        if (floor == null || !floor.isBlock() || floor.hasGravity()) {
            LOGGER.warning(() -> "modules/tradewinds.conf names the sea floor '" + sea.floor()
                    + "', which is no block or one that falls. The sea lies on sandstone.");
            floor = Material.SANDSTONE;
        }
        World world = start.world();
        int surface = start.y() - 3;
        int bottom = surface - sea.depth() + 1;
        Map<Long, List<Placed>> ship = ship(start.centerX(), start.y(), start.centerZ());
        int minX = start.centerX() - sea.radius();
        int maxX = start.centerX() + sea.radius();
        int minZ = start.centerZ() - sea.radius();
        int maxZ = start.centerZ() + sea.radius();
        int centreChunkX = start.centerX() >> 4;
        int centreChunkZ = start.centerZ() >> 4;
        Material bed = floor;
        for (int chunkX = minX >> 4; chunkX <= maxX >> 4; chunkX++) {
            for (int chunkZ = minZ >> 4; chunkZ <= maxZ >> 4; chunkZ++) {
                int fromX = Math.max(minX, chunkX << 4);
                int toX = Math.min(maxX, (chunkX << 4) + 15);
                int fromZ = Math.max(minZ, chunkZ << 4);
                int toZ = Math.min(maxZ, (chunkZ << 4) + 15);
                List<Placed> part = ship.getOrDefault(key(chunkX, chunkZ), List.of());
                Runnable lay = () -> {
                    fill(world, fromX, toX, fromZ, toZ, bottom, surface, bed);
                    for (Placed block : part) {
                        // No physics: a hull laid a chunk at a time must not let the sea in between.
                        world.getBlockAt(block.x(), block.y(), block.z()).setType(block.material(), false);
                    }
                };
                if (chunkX == centreChunkX && chunkZ == centreChunkZ) {
                    lay.run();
                } else {
                    scheduler.onRegion(world.getName(), chunkX, chunkZ, lay);
                }
            }
        }
    }

    /** A block of the ship. */
    record Placed(int x, int y, int z, Material material) {}

    /** The ship around an arrival at {@code (x, y, z)}, by the chunk each block falls in. */
    static Map<Long, List<Placed>> ship(int x, int y, int z) {
        Map<Long, List<Placed>> byChunk = new HashMap<>();
        for (int dx = -HALF_LENGTH; dx <= HALF_LENGTH; dx++) {
            int beam = beamAt(dx);
            for (int dz = -beam; dz <= beam; dz++) {
                boolean side = Math.abs(dz) == beam || Math.abs(dx) == HALF_LENGTH;
                put(byChunk, x + dx, y - 4, z + dz, Material.SPRUCE_PLANKS);
                put(byChunk, x + dx, y - 3, z + dz, side ? Material.SPRUCE_PLANKS : Material.AIR);
                put(byChunk, x + dx, y - 2, z + dz, side ? Material.SPRUCE_PLANKS : Material.AIR);
                put(byChunk, x + dx, y - 1, z + dz, Material.OAK_PLANKS);
                if (side) {
                    put(byChunk, x + dx, y, z + dz, Material.OAK_FENCE);
                }
            }
        }
        for (int dy = 0; dy < MAST_HEIGHT; dy++) {
            put(byChunk, x + MAST, y + dy, z, Material.OAK_LOG);
        }
        for (int dz = -HALF_BEAM; dz <= HALF_BEAM; dz++) {
            for (int dy = 3; dy < MAST_HEIGHT - 1; dy++) {
                if (dz != 0) {
                    put(byChunk, x + MAST, y + dy, z + dz, Material.WHITE_WOOL);
                }
            }
        }
        return byChunk;
    }

    /** How far the hull reaches to either side at {@code dx} from amidships: narrower toward bow and stern. */
    static int beamAt(int dx) {
        int fromEnd = HALF_LENGTH - Math.abs(dx);
        return Math.min(HALF_BEAM, fromEnd);
    }

    private static void put(Map<Long, List<Placed>> byChunk, int x, int y, int z, Material material) {
        byChunk.computeIfAbsent(key(x >> 4, z >> 4), chunk -> new ArrayList<>()).add(new Placed(x, y, z, material));
    }

    private static long key(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xffffffffL);
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
            // No physics: a sea laid a chunk at a time must not flow into the chunk laid next.
            block.setType(material, false);
        }
    }
}
