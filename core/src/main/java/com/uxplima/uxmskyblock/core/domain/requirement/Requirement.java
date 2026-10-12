package com.uxplima.uxmskyblock.core.domain.requirement;

import java.time.Duration;
import java.util.Locale;
import java.util.Objects;

/**
 * One line of what something asks: something to have, or something to hand over.
 *
 * <p>A line {@link Mode#HAVE has} to be there and is left where it is; a line to {@link Mode#TAKE take} is handed
 * over. Money and a currency come from the player or from the island's bank; items from the player; points from
 * the island. A level, a permission and a wait are only ever had.
 */
public sealed interface Requirement {

    /** Whether a line is held or handed over. */
    enum Mode {
        HAVE,
        TAKE
    }

    /** Whose purse a line is paid from. */
    enum Source {
        PLAYER,
        ISLAND
    }

    /** Whether the line is had or taken. */
    Mode mode();

    /** Money in minor units, from the player or the island's bank. */
    record Money(long minor, Source from, Mode mode) implements Requirement {
        public Money {
            positive(minor);
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(mode, "mode");
        }
    }

    /** A currency the bank keeps, by its id in bank.conf, from the player or the island's bank. */
    record Currency(String id, long amount, Source from, Mode mode) implements Requirement {
        public Currency {
            id = Objects.requireNonNull(id, "id").strip().toLowerCase(Locale.ROOT);
            if (id.isEmpty()) {
                throw new IllegalArgumentException("a currency line names its currency");
            }
            positive(amount);
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(mode, "mode");
        }
    }

    /** Items of one material from the player's inventory, the material namespaced. */
    record Items(String material, long amount, Mode mode) implements Requirement {
        public Items {
            material = Objects.requireNonNull(material, "material").strip().toLowerCase(Locale.ROOT);
            if (material.isEmpty()) {
                throw new IllegalArgumentException("an item line names its material");
            }
            if (!material.contains(":")) {
                material = "minecraft:" + material;
            }
            positive(amount);
            Objects.requireNonNull(mode, "mode");
        }
    }

    /** The island's own points, as a mode keeps them. */
    record Points(long amount, Mode mode) implements Requirement {
        public Points {
            positive(amount);
            Objects.requireNonNull(mode, "mode");
        }
    }

    /** An island level, or the level the mode's ladder sets when {@link #LADDER}. Only ever had. */
    record Level(long level) implements Requirement {
        /** The level the mode's own ladder sets for the step, rather than a number. */
        public static final long LADDER = -1;

        public Level {
            if (level < 0 && level != LADDER) {
                throw new IllegalArgumentException("a level is a number from zero, or the ladder: " + level);
            }
        }

        @Override
        public Mode mode() {
            return Mode.HAVE;
        }
    }

    /** A permission node the player holds. Only ever had. */
    record Permission(String node) implements Requirement {
        public Permission {
            node = Objects.requireNonNull(node, "node").strip();
            if (node.isEmpty()) {
                throw new IllegalArgumentException("a permission line names its node");
            }
        }

        @Override
        public Mode mode() {
            return Mode.HAVE;
        }
    }

    /** A time that has to have passed, since whatever the line is about last happened. Only ever had. */
    record Wait(Duration time) implements Requirement {
        public Wait {
            Objects.requireNonNull(time, "time");
            if (time.isNegative() || time.isZero()) {
                throw new IllegalArgumentException("a wait is longer than nothing: " + time);
            }
        }

        @Override
        public Mode mode() {
            return Mode.HAVE;
        }
    }

    private static void positive(long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("an amount is more than nothing: " + amount);
        }
    }
}
