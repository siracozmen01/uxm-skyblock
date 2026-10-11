package com.uxplima.uxmskyblock.core.application.gamemode;

import java.util.Objects;

/**
 * How far from an island's centre a creation action writes blocks, sideways, and in which world.
 *
 * <p>An action may write well past the island's border: a SkyGrid's grid reaches further than the island a player
 * starts with, and an Upside Down is laid in the Nether. What an action writes stays in the world after the island
 * is gone unless the island is cleared as far as its actions reached, so each says how far that is.
 *
 * @param world the world the blocks are written in
 * @param radius how far from the island's centre, along either axis
 */
public record CreationReach(String world, int radius) {

    public CreationReach {
        Objects.requireNonNull(world, "world must not be null");
        if (radius < 0) {
            throw new IllegalArgumentException("radius must not be negative: " + radius);
        }
    }
}
