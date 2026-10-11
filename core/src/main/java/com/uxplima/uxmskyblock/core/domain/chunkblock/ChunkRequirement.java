package com.uxplima.uxmskyblock.core.domain.chunkblock;

import java.util.Locale;
import java.util.Objects;

/**
 * One line of what a chunk costs. Every line but the level is paid, and may be paid a part at a time: what has been
 * paid of it is kept under its {@link #key()}. The level is held, as it always was, and paid nothing.
 */
public sealed interface ChunkRequirement {

    /** Whose purse a line is paid from. */
    enum Source {
        /** The player who pays. */
        PLAYER,
        /** The island's bank. */
        ISLAND
    }

    /** The name what has been paid of this line is kept under, the same every time the file is read. */
    String key();

    /** How much the line asks for. */
    long amount();

    /** Whether the line is paid, rather than held as the level is. */
    default boolean paid() {
        return true;
    }

    /** Money, from the player or the island's bank. */
    record Money(long amount, Source from) implements ChunkRequirement {
        public Money {
            positive(amount);
            Objects.requireNonNull(from, "from must not be null");
        }

        @Override
        public String key() {
            return "money:" + from.name().toLowerCase(Locale.ROOT);
        }
    }

    /** A currency the bank keeps, by its id in bank.conf, from the player or the island's bank. */
    record Currency(String id, long amount, Source from) implements ChunkRequirement {
        public Currency {
            id = Objects.requireNonNull(id, "id must not be null").strip();
            if (id.isEmpty()) {
                throw new IllegalArgumentException("a currency line names its currency");
            }
            positive(amount);
            Objects.requireNonNull(from, "from must not be null");
        }

        @Override
        public String key() {
            return "currency:" + id + ":" + from.name().toLowerCase(Locale.ROOT);
        }
    }

    /** Items of one material, handed in from the player's inventory. */
    record Items(String material, long amount) implements ChunkRequirement {
        public Items {
            material = Objects.requireNonNull(material, "material must not be null")
                    .strip()
                    .toLowerCase(Locale.ROOT);
            if (material.isEmpty()) {
                throw new IllegalArgumentException("an item line names its material");
            }
            if (!material.contains(":")) {
                material = "minecraft:" + material;
            }
            positive(amount);
        }

        @Override
        public String key() {
            return "item:" + material;
        }
    }

    /** Chunk points, the island's own, earned by opening chunks whose reward gives them. */
    record Points(long amount) implements ChunkRequirement {
        public Points {
            positive(amount);
        }

        @Override
        public String key() {
            return "points";
        }
    }

    /**
     * The island's level, held rather than paid: a chunk opened by level alone closes again when the level falls
     * below it. {@link #LADDER} asks the level the island's unlock ladder names for its next held chunk.
     */
    record Level(long amount) implements ChunkRequirement {

        /** The level the ladder in chunkblock.conf names for the next chunk, rather than one written here. */
        public static final long LADDER = -1;

        public Level {
            if (amount < 0 && amount != LADDER) {
                throw new IllegalArgumentException("a level is not below zero: " + amount);
            }
        }

        @Override
        public String key() {
            return "level";
        }

        @Override
        public boolean paid() {
            return false;
        }

        /** The level this line asks of an island whose next held chunk the ladder prices at {@code ladder}. */
        public long of(long ladder) {
            return amount == LADDER ? ladder : amount;
        }
    }

    private static void positive(long amount) {
        if (amount < 1) {
            throw new IllegalArgumentException("a line asks for at least one, not " + amount);
        }
    }
}
