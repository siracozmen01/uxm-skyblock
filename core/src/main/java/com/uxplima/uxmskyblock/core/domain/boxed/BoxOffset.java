package com.uxplima.uxmskyblock.core.domain.boxed;

/**
 * How far a Boxed island's box lies from the island's centre, as an ender pearl moved it.
 *
 * @param dx blocks along x
 * @param dz blocks along z
 */
public record BoxOffset(int dx, int dz) {

    /** A box where the island was made. */
    public static final BoxOffset NONE = new BoxOffset(0, 0);

    /** The furthest the box's middle lies from the island's centre along either way. */
    public int reach() {
        return Math.max(Math.abs(dx), Math.abs(dz));
    }
}
