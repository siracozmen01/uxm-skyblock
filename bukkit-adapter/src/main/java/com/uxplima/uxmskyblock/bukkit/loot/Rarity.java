package com.uxplima.uxmskyblock.bukkit.loot;

import java.util.Objects;

/**
 * A rarity an operator names, how often a roll lands on it against the others, and the entry in effects.conf it
 * fires for the player who finds it. An empty effect fires nothing.
 */
public record Rarity(String name, int weight, String effects) {

    /** The rarity of a table that names none, and of an entry that names none. */
    public static final String COMMON = "common";

    public Rarity {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(effects, "effects");
        if (weight < 0) {
            throw new IllegalArgumentException("the weight of " + name + " is below zero: " + weight);
        }
    }
}
