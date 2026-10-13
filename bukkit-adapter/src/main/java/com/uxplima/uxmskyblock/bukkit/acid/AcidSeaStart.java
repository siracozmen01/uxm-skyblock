package com.uxplima.uxmskyblock.bukkit.acid;

import java.util.Objects;
import java.util.SplittableRandom;
import java.util.logging.Logger;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.BubbleColumn;

import com.uxplima.uxmskyblock.bukkit.config.AcidIslandConfiguration;
import com.uxplima.uxmskyblock.bukkit.schematic.IslandStart;
import com.uxplima.uxmskyblock.core.application.acid.AcidIslandService;
import com.uxplima.uxmskyblock.core.application.gamemode.CreationActionProvider;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;

/**
 * The creation action that makes an island an AcidIsland island: records it and fills the air around it
 * with the acid sea, on a floor.
 *
 * <p>Where the sea fills a column from its floor to its surface, the column is painted with the sea's biome from the
 * floor to the sky, so the water, the fog and the sky above it take its colours; the island's own columns keep theirs.
 *
 * <p>The sea spans many chunks, and on Folia each chunk belongs to the region that owns it, so each
 * chunk's column of sea is laid on that region's thread. A block the island already has is kept.
 */
public final class AcidSeaStart implements CreationActionProvider<IslandStart> {

    /** The name a preset's start list writes for this action. */
    public static final String ACTION = "uxm:acid-sea";

    /** The block a sulfur vent is on the sea floor. */
    public static final Material VENT = Material.MAGMA_BLOCK;

    /** The block a geyser is on the sea floor. */
    public static final Material GEYSER = Material.SOUL_SAND;

    private static final Logger LOGGER = Logger.getLogger(AcidSeaStart.class.getName());

    /** How the players who see a chunk are sent it again once its biome changed. */
    @FunctionalInterface
    public interface Resend {
        void chunk(World world, int chunkX, int chunkZ);
    }

    /** A biome is kept per cell of four blocks each way, so one block in four is enough to paint it. */
    private static final int CELL = 4;

    private final AcidIslandService service;
    private final SchedulerPort scheduler;
    private final AcidIslandConfiguration.Sea sea;
    private final @org.jspecify.annotations.Nullable Biome biome;
    private final Resend resend;

    /** A sea that keeps the biome its world made. */
    public AcidSeaStart(AcidIslandService service, SchedulerPort scheduler, AcidIslandConfiguration.Sea sea) {
        this(service, scheduler, sea, null, (world, chunkX, chunkZ) -> {});
    }

    /** @param biome the biome painted over the sea, or none to keep the one its world made */
    public AcidSeaStart(
            AcidIslandService service,
            SchedulerPort scheduler,
            AcidIslandConfiguration.Sea sea,
            @org.jspecify.annotations.Nullable Biome biome,
            Resend resend) {
        this.service = Objects.requireNonNull(service, "service must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.sea = Objects.requireNonNull(sea, "sea must not be null");
        this.biome = biome;
        this.resend = Objects.requireNonNull(resend, "resend must not be null");
    }

    @Override
    public String actionId() {
        return ACTION;
    }

    /** The sea reaches as far as acidisland.conf says. */
    @Override
    public java.util.List<com.uxplima.uxmskyblock.core.application.gamemode.CreationReach> reach(IslandStart start) {
        return java.util.List.of(new com.uxplima.uxmskyblock.core.application.gamemode.CreationReach(
                start.world().getName(), sea.radius()));
    }

    @Override
    public void apply(IslandStart start) {
        int surface = start.y() - sea.belowIsland();
        service.add(start.islandId(), surface);
        Material floor = Material.matchMaterial(sea.floor());
        if (floor == null || !floor.isBlock() || floor.hasGravity()) {
            // A floor that falls leaves nothing under the sea, and the sea drains away through it.
            LOGGER.warning(() -> "modules/acidisland.conf names the floor '" + sea.floor()
                    + "', which is no block or one that falls. The sea stands on sandstone.");
            floor = Material.SANDSTONE;
        }
        World world = start.world();
        int minX = start.centerX() - sea.radius();
        int maxX = start.centerX() + sea.radius();
        int minZ = start.centerZ() - sea.radius();
        int maxZ = start.centerZ() + sea.radius();
        Material bed = floor;
        for (int chunkX = minX >> 4; chunkX <= maxX >> 4; chunkX++) {
            for (int chunkZ = minZ >> 4; chunkZ <= maxZ >> 4; chunkZ++) {
                int fromX = Math.max(minX, chunkX << 4);
                int toX = Math.min(maxX, (chunkX << 4) + 15);
                int fromZ = Math.max(minZ, chunkZ << 4);
                int toZ = Math.min(maxZ, (chunkZ << 4) + 15);
                long seed = start.islandId().value().getMostSignificantBits()
                        ^ start.islandId().value().getLeastSignificantBits();
                int cx = chunkX;
                int cz = chunkZ;
                scheduler.onRegion(world.getName(), chunkX, chunkZ, () -> {
                    fill(world, fromX, toX, fromZ, toZ, surface, bed, seed);
                    if (biome != null) {
                        paint(world, fromX, toX, fromZ, toZ, surface, biome);
                        resend.chunk(world, cx, cz);
                    }
                });
            }
        }
    }

    private void fill(World world, int fromX, int toX, int fromZ, int toZ, int surface, Material floor, long seed) {
        int bottom = surface - sea.depth() + 1;
        for (int x = fromX; x <= toX; x++) {
            for (int z = fromZ; z <= toZ; z++) {
                boolean open = setIfAir(world.getBlockAt(x, bottom - 1, z), floor);
                for (int y = bottom; y <= surface; y++) {
                    open &= setIfAir(world.getBlockAt(x, y, z), Material.WATER);
                }
                if (open) {
                    // Only a column the sea filled from the floor to the surface can hold a vent or a
                    // geyser, so neither is ever laid under the island.
                    feature(world, x, z, bottom, surface, seed);
                }
            }
        }
    }

    /**
     * Paints each cell of the area whose corner column the sea filled, from the floor to the top of the world. A cell
     * the island stands in keeps its biome.
     */
    private void paint(World world, int fromX, int toX, int fromZ, int toZ, int surface, Biome painted) {
        int floor = surface - sea.depth();
        for (int x = Math.floorDiv(fromX + CELL - 1, CELL) * CELL; x <= toX; x += CELL) {
            for (int z = Math.floorDiv(fromZ + CELL - 1, CELL) * CELL; z <= toZ; z += CELL) {
                if (!isSea(world.getBlockAt(x, surface, z).getType())) {
                    continue;
                }
                for (int y = Math.floorDiv(floor, CELL) * CELL; y < world.getMaxHeight(); y += CELL) {
                    world.setBiome(x, y, z, painted);
                }
            }
        }
    }

    private static boolean isSea(Material type) {
        return type == Material.WATER || type == Material.BUBBLE_COLUMN;
    }

    /**
     * Makes the column a vent or a geyser, by its chance. The same island and column always roll the
     * same, so a sea laid again comes out the same.
     */
    private void feature(World world, int x, int z, int bottom, int surface, long seed) {
        double roll = new SplittableRandom(seed ^ (x * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL)).nextDouble();
        boolean vent = roll < sea.vents().chance();
        if (!vent && roll >= sea.vents().chance() + sea.geyserChance()) {
            return;
        }
        // Magma pulls whoever swims over it down and soul sand throws them up: the server's own
        // bubble columns, laid here because a sea set without physics would not raise them.
        world.getBlockAt(x, bottom - 1, z).setType(vent ? VENT : GEYSER, false);
        BubbleColumn column = (BubbleColumn) Material.BUBBLE_COLUMN.createBlockData();
        column.setDrag(vent);
        for (int y = bottom; y <= surface; y++) {
            world.getBlockAt(x, y, z).setBlockData(column, false);
        }
    }

    /** Sets the block when it is air, and says whether it did. */
    private static boolean setIfAir(Block block, Material material) {
        if (block.getType().isAir()) {
            // No physics: a sea laid a chunk at a time must not flow into the chunk laid next.
            block.setType(material, false);
            return true;
        }
        return false;
    }
}
