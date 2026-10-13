package com.uxplima.uxmskyblock.bukkit.config;

import java.util.Objects;

import org.bukkit.block.Biome;

/**
 * The biome a OneBlock phase gives its island when it begins, as the phase file writes it under {@code biome} and
 * {@code biome-area}: over the whole island, or over the blocks within a distance of the island's block.
 *
 * @param biome the biome, one the server knows
 * @param radius how many blocks around the block take it, or {@link #ISLAND} for every block of the island
 */
public record OneBlockBiome(Biome biome, int radius) {

    /** The radius that stands for the whole island. */
    public static final int ISLAND = -1;

    public OneBlockBiome {
        Objects.requireNonNull(biome, "biome must not be null");
        if (radius < ISLAND) {
            throw new IllegalArgumentException("a radius is not below nothing: " + radius);
        }
    }

    /** Whether the whole island takes the biome, rather than the blocks around its block. */
    public boolean wholeIsland() {
        return radius == ISLAND;
    }
}
