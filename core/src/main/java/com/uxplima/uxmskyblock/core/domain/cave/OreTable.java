package com.uxplima.uxmskyblock.core.domain.cave;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * The ores in a rock, each with the chance that one block of the rock is it.
 *
 * <p>The chances add up in the order they are written, so one roll picks at most one ore, and a rock
 * whose chances add past one is refused.
 */
public record OreTable(List<Ore> ores) {

    public static final OreTable NONE = new OreTable(List.of());

    /**
     * One ore.
     *
     * @param block the block the ore is, by name
     * @param chance the chance that one block of the rock is this ore
     * @param below how many blocks under the block players arrive on the ore begins, above that not found; 0 for
     *     every depth, above the arrival too
     */
    public record Ore(String block, double chance, int below) {

        public Ore {
            Objects.requireNonNull(block, "block must not be null");
            if (block.isBlank() || chance < 0 || chance > 1 || below < 0) {
                throw new IllegalArgumentException(
                        "an ore names a block, has a chance between 0 and 1, and begins at no negative depth");
            }
        }

        /** An ore found at every depth. */
        public Ore(String block, double chance) {
            this(block, chance, 0);
        }

        /**
         * An ore written {@code BLOCK:chance}, such as {@code COAL_ORE:0.012}, or {@code BLOCK:chance:below}, such as
         * {@code DIAMOND_ORE:0.002:16} for one found only from 16 blocks down.
         *
         * @throws IllegalArgumentException when it is not written that way
         */
        public static Ore parse(String written) {
            String[] parts = written.trim().split(":", -1);
            if (parts.length != 2 && parts.length != 3) {
                throw new IllegalArgumentException(written + " is not BLOCK:chance or BLOCK:chance:below");
            }
            try {
                return new Ore(
                        parts[0].trim().toUpperCase(Locale.ROOT),
                        Double.parseDouble(parts[1].trim()),
                        parts.length == 3 ? Integer.parseInt(parts[2].trim()) : 0);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(written + " has no number for a chance or a depth", e);
            }
        }
    }

    public OreTable {
        ores = List.copyOf(ores);
        double total = 0;
        for (Ore ore : ores) {
            total += ore.chance();
        }
        if (total > 1) {
            throw new IllegalArgumentException("the ore chances add past one: " + total);
        }
    }

    /** The ore a roll between 0 and 1 lands on, or empty for plain rock, every ore counted at its own chance. */
    public Optional<String> pick(double roll) {
        return pick(roll, Integer.MAX_VALUE, 1);
    }

    /**
     * The ore a roll between 0 and 1 lands on at {@code depth} blocks under the block players arrive on, or empty for
     * plain rock. Only the ores begun by that depth are counted, each at its chance times {@code scale}; scaled past
     * one in all, they are scaled back to one.
     */
    public Optional<String> pick(double roll, int depth, double scale) {
        double found = 0;
        for (Ore ore : ores) {
            if (begun(ore, depth)) {
                found += ore.chance();
            }
        }
        double times = found * scale > 1 ? 1 / found : scale;
        double reached = 0;
        for (Ore ore : ores) {
            if (!begun(ore, depth)) {
                continue;
            }
            reached += ore.chance() * times;
            if (roll < reached) {
                return Optional.of(ore.block());
            }
        }
        return Optional.empty();
    }

    /** Whether an ore is found at a depth: one that begins nowhere below is found at every depth, above too. */
    private static boolean begun(Ore ore, int depth) {
        return ore.below() == 0 || depth >= ore.below();
    }
}
