package com.uxplima.uxmskyblock.bukkit.top;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import com.uxplima.uxmskyblock.core.domain.leaderboard.LeaderboardCategory;

/**
 * One display an operator placed: where it stands, what it is, and which place of which board it shows.
 *
 * @param rank the place on the board, from one
 */
public record TopDisplay(String world, int x, int y, int z, Kind kind, LeaderboardCategory board, int rank) {

    /** The most places a display may show down a board. */
    public static final int MOST_RANK = 100;

    /** What shows the island: a sign's lines, a head's owner, or a line of text in the air. */
    public enum Kind {
        SIGN,
        HEAD,
        TEXT;

        /** The kind a word names, any case, or nothing. */
        public static Optional<Kind> named(String word) {
            for (Kind kind : values()) {
                if (kind.name().equalsIgnoreCase(word.strip())) {
                    return Optional.of(kind);
                }
            }
            return Optional.empty();
        }

        public String word() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public TopDisplay {
        Objects.requireNonNull(world, "world must not be null");
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(board, "board must not be null");
        if (rank < 1 || rank > MOST_RANK) {
            throw new IllegalArgumentException("rank must be from 1 to " + MOST_RANK + ": " + rank);
        }
    }

    /** Whether this display stands at the block {@code x}, {@code y}, {@code z} of {@code world}. */
    public boolean standsAt(String world, int x, int y, int z) {
        return this.world.equals(world) && this.x == x && this.y == y && this.z == z;
    }

    /** Where it stands, as an operator reads it. */
    public String place() {
        return world + " " + x + " " + y + " " + z;
    }
}
