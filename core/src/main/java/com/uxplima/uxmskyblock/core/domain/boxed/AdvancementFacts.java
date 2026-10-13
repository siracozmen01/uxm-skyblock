package com.uxplima.uxmskyblock.core.domain.boxed;

import java.util.Objects;

/**
 * What a box needs to know of an advancement to say what it is worth.
 *
 * @param key the advancement's key, as {@code minecraft:story/mine_stone}
 * @param depth how many steps it lies from the root of its tab, 0 for the root itself
 * @param shown whether it has a place in the advancements screen; one with none is a trigger working behind it
 */
public record AdvancementFacts(String key, int depth, boolean shown) {

    public AdvancementFacts {
        Objects.requireNonNull(key, "key must not be null");
        if (depth < 0) {
            throw new IllegalArgumentException("an advancement lies 0 or more steps from its root: " + depth);
        }
    }

    /** Whether it is a recipe unlocked, which the game keeps as an advancement under {@code recipes/}. */
    public boolean recipe() {
        int colon = key.indexOf(':');
        return key.startsWith("recipes/", colon + 1);
    }
}
