package com.uxplima.uxmskyblock.core.domain.boxed;

import java.util.Locale;
import java.util.Optional;

/**
 * The side a Boxed island's box grows toward. Growing toward a side moves the box that way by as much as it grows, so
 * the opposite edge stays where it is and all the growth lands on the chosen side; toward a corner, the two opposite
 * edges stay. {@link #ALL} grows every edge alike, as a box does by default.
 */
public enum BoxSide {
    ALL(0, 0),
    NORTH(0, -1),
    SOUTH(0, 1),
    EAST(1, 0),
    WEST(-1, 0),
    NORTH_EAST(1, -1),
    NORTH_WEST(-1, -1),
    SOUTH_EAST(1, 1),
    SOUTH_WEST(-1, 1);

    private final int stepX;
    private final int stepZ;

    BoxSide(int stepX, int stepZ) {
        this.stepX = stepX;
        this.stepZ = stepZ;
    }

    /** Which way along x the box moves as it grows: -1, 0 or 1. */
    public int stepX() {
        return stepX;
    }

    /** Which way along z the box moves as it grows: -1 is north, 0 or 1 south. */
    public int stepZ() {
        return stepZ;
    }

    /** The side a word names, {@code north_east} or {@code north-east} alike, or nothing. */
    public static Optional<BoxSide> named(String word) {
        String written = word.strip().toUpperCase(Locale.ROOT).replace('-', '_');
        for (BoxSide side : values()) {
            if (side.name().equals(written)) {
                return Optional.of(side);
            }
        }
        return Optional.empty();
    }

    /** The word for this side, {@code north_east}. */
    public String word() {
        return name().toLowerCase(Locale.ROOT);
    }
}
