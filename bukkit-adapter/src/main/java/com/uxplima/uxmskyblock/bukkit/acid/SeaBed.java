package com.uxplima.uxmskyblock.bukkit.acid;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.logging.Logger;

import org.bukkit.Material;

import com.uxplima.uxmskyblock.bukkit.config.AcidIslandConfiguration;

/**
 * The floor of one island's acid sea: which block each column is made of, by the weights the operator wrote, and how
 * far it rises there, in gentle mounds. The same island and column always come out the same.
 */
final class SeaBed {

    /** How many blocks apart the heights the mounds are drawn between lie. */
    private static final int SPAN = 8;

    private static final java.util.regex.Pattern SPACES = java.util.regex.Pattern.compile("\\s+");

    private static final Logger LOGGER = Logger.getLogger(SeaBed.class.getName());

    /** One block of the floor and its weight. */
    record Weighted(Material block, int weight) {}

    private final List<Weighted> blocks;
    private final int total;
    private final int highest;
    private final long seed;

    /**
     * @param blocks the floor's blocks, read by {@link #read}
     * @param highest how many blocks the floor rises at most
     */
    SeaBed(List<Weighted> blocks, int highest, long seed) {
        if (blocks.isEmpty()) {
            throw new IllegalArgumentException("a floor is made of at least one block");
        }
        this.blocks = List.copyOf(blocks);
        this.total = blocks.stream().mapToInt(Weighted::weight).sum();
        this.highest = Math.max(0, highest);
        this.seed = seed;
    }

    /**
     * The blocks {@code bed} writes, each {@code BLOCK weight} or {@code BLOCK} for a weight of one. A block that is
     * none, or one that falls, is left out and said; with none left, the floor is {@code floor}.
     */
    static List<Weighted> read(AcidIslandConfiguration.Bed bed, Material floor) {
        List<Weighted> read = new ArrayList<>();
        for (String written : bed.blocks()) {
            String[] parts = SPACES.split(written.strip(), -1);
            Material block = Material.matchMaterial(parts[0].toUpperCase(Locale.ROOT));
            int weight = 1;
            try {
                weight = parts.length > 1 ? Integer.parseInt(parts[1]) : 1;
            } catch (NumberFormatException e) {
                weight = 0;
            }
            if (block == null || !block.isBlock() || block.hasGravity() || weight < 1 || parts.length > 2) {
                LOGGER.warning(() -> "modules/acidisland.conf sea bed blocks: '" + written
                        + "' is no block that stands, with a weight of 1 or more. It is left out.");
                continue;
            }
            read.add(new Weighted(block, weight));
        }
        if (read.isEmpty()) {
            read.add(new Weighted(floor, 1));
        }
        return read;
    }

    /** Every block the floor may be made of. */
    Set<Material> materials() {
        java.util.EnumSet<Material> all = java.util.EnumSet.noneOf(Material.class);
        blocks.forEach(weighted -> all.add(weighted.block()));
        return all;
    }

    /** The block the column at {@code x}, {@code z} is made of. */
    Material blockAt(int x, int z) {
        int roll = new SplittableRandom(seed ^ (x * 0xD1B54A32D192ED03L) ^ (z * 0x8CB92BA72F3D8DD7L)).nextInt(total);
        for (Weighted weighted : blocks) {
            roll -= weighted.weight();
            if (roll < 0) {
                return weighted.block();
            }
        }
        return blocks.getLast().block();
    }

    /** How many blocks the floor rises at {@code x}, {@code z}: from 0 to the most, smoothly from column to column. */
    int riseAt(int x, int z) {
        if (highest == 0) {
            return 0;
        }
        int gridX = Math.floorDiv(x, SPAN);
        int gridZ = Math.floorDiv(z, SPAN);
        double alongX = smooth((x - gridX * SPAN) / (double) SPAN);
        double alongZ = smooth((z - gridZ * SPAN) / (double) SPAN);
        double north = mix(corner(gridX, gridZ), corner(gridX + 1, gridZ), alongX);
        double south = mix(corner(gridX, gridZ + 1), corner(gridX + 1, gridZ + 1), alongX);
        return Math.min(highest, (int) Math.floor(mix(north, south, alongZ) * (highest + 1)));
    }

    private double corner(int gridX, int gridZ) {
        return new SplittableRandom(seed ^ (gridX * 0x9E3779B97F4A7C15L) ^ (gridZ * 0xC2B2AE3D27D4EB4FL) ^ 0x5DEECE66DL)
                .nextDouble();
    }

    private static double smooth(double along) {
        return along * along * (3 - 2 * along);
    }

    private static double mix(double from, double to, double along) {
        return from + (to - from) * along;
    }

    @Override
    public String toString() {
        return "SeaBed" + Objects.toString(blocks);
    }
}
