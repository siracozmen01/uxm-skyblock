package com.uxplima.uxmskyblock.core.domain.wall;

import java.util.Objects;

/**
 * One straight stretch of wall on the block grid, standing on one side of the ground it closes in.
 *
 * <p>A wall stands on a plane between two columns of blocks. {@code line} is that plane: a z for a north or south
 * wall, an x for a west or east one. The stretch runs from {@code from} to {@code to} along the other axis, so a wall
 * along the north edge of the chunk at x 0 to 15 runs from 0 to 16.
 *
 * @param side the side of the closed ground the wall stands on
 * @param line where the wall's plane is
 * @param from where the stretch starts along the wall
 * @param to where it ends, past {@code from}
 */
public record WallSegment(Side side, int line, int from, int to) {

    /** The side of the closed ground a wall stands on. */
    public enum Side {
        /** At the lowest z, the ground to the south of it. */
        NORTH,
        /** At the highest z, the ground to the north of it. */
        SOUTH,
        /** At the lowest x, the ground to the east of it. */
        WEST,
        /** At the highest x, the ground to the west of it. */
        EAST;

        /** Whether a wall on this side runs along x, its plane a z. */
        public boolean alongX() {
            return this == NORTH || this == SOUTH;
        }
    }

    public WallSegment {
        Objects.requireNonNull(side, "side must not be null");
        if (to <= from) {
            throw new IllegalArgumentException("a wall runs from " + from + " to past it, not to " + to);
        }
    }

    /** How long the stretch is, in blocks. */
    public int length() {
        return to - from;
    }
}
