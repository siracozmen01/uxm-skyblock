package com.uxplima.uxmskyblock.core.domain.recycle;

import java.util.Objects;

/**
 * A square of one world an island wrote blocks in: its centre and how far it reaches along either axis.
 *
 * @param world the world
 * @param centerX the centre's x
 * @param centerZ the centre's z
 * @param radius how far from the centre, along either axis
 */
public record IslandFootprint(String world, int centerX, int centerZ, int radius) {

    public IslandFootprint {
        Objects.requireNonNull(world, "world must not be null");
        if (radius < 0) {
            throw new IllegalArgumentException("radius must not be negative: " + radius);
        }
    }

    /** The same square, reaching at least {@code other} far. */
    public IslandFootprint atLeast(int other) {
        return radius >= other ? this : new IslandFootprint(world, centerX, centerZ, other);
    }
}
