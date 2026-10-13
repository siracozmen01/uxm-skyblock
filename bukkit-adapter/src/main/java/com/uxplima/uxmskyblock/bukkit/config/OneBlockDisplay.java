package com.uxplima.uxmskyblock.bukkit.config;

import java.time.Duration;
import java.util.Locale;
import java.util.Objects;

/**
 * How a OneBlock island's progress is shown to the player who breaks its block: a bar each player may switch, and a
 * line over the block.
 *
 * @param bar where a player's progress shows until they switch it: on a boss bar, on the action bar, or nowhere
 * @param linger how long the boss bar stays after the last break
 * @param line whether a line over the block shows the phase and how far into it the island is
 */
public record OneBlockDisplay(Bar bar, Duration linger, boolean line) {

    /** Where a player's progress shows. */
    public enum Bar {
        BOSSBAR,
        ACTIONBAR,
        NONE;

        /** The way a file and the catalogue name it. */
        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** The one after this, as a player's switch goes round them. */
        public Bar next() {
            return switch (this) {
                case BOSSBAR -> ACTIONBAR;
                case ACTIONBAR -> NONE;
                case NONE -> BOSSBAR;
            };
        }

        /** The one {@code written} names, or nothing. */
        public static java.util.Optional<Bar> named(String written) {
            for (Bar bar : values()) {
                if (bar.key().equals(written.strip().toLowerCase(Locale.ROOT))) {
                    return java.util.Optional.of(bar);
                }
            }
            return java.util.Optional.empty();
        }
    }

    /** As shipped: a boss bar that stays ten seconds, and a line over the block. */
    public static final OneBlockDisplay SHIPPED = new OneBlockDisplay(Bar.BOSSBAR, Duration.ofSeconds(10), true);

    public OneBlockDisplay {
        Objects.requireNonNull(bar, "bar");
        Objects.requireNonNull(linger, "linger");
        if (linger.isNegative() || linger.isZero()) {
            throw new IllegalArgumentException("a boss bar stays a while: " + linger);
        }
    }
}
