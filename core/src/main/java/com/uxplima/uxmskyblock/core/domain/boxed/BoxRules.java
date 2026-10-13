package com.uxplima.uxmskyblock.core.domain.boxed;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * How big a Boxed island's box starts, and how far each advancement its players make pushes it out.
 *
 * <p>An advancement counts once for an island, whoever on it makes it. The operator may give any one its own worth,
 * and one of worth 0 grows nothing. One under an ignored prefix is worth nothing. Every other one is worth what
 * {@link AdvancementWorth} says of where it lies: a tab's root, a recipe and one the screen does not show each have a
 * worth of their own, and the rest are worth {@code blocksPerAdvancement}, once or for each step from the root.
 *
 * @param startRadius how far the box reaches from the island's centre when the island is made
 * @param blocksPerAdvancement how far an advancement with no worth of its own pushes the box out
 * @param maxRadius how far the box can ever reach; keep it inside half the grid spacing
 * @param worth the advancements the operator gave a worth of their own, by key
 * @param ignored the key prefixes of advancements that are worth nothing
 * @param scoring what an advancement with no worth of its own is worth, by where it lies
 */
public record BoxRules(
        int startRadius,
        int blocksPerAdvancement,
        int maxRadius,
        Map<String, Integer> worth,
        List<String> ignored,
        AdvancementWorth scoring) {

    public BoxRules {
        worth = Map.copyOf(worth);
        ignored = List.copyOf(ignored);
        Objects.requireNonNull(scoring, "scoring must not be null");
        if (startRadius < 1 || maxRadius < startRadius) {
            throw new IllegalArgumentException("the box starts at least 1 across its radius and never shrinks past it");
        }
        if (blocksPerAdvancement < 0 || worth.values().stream().anyMatch(blocks -> blocks < 0)) {
            throw new IllegalArgumentException("an advancement pushes the box out by 0 or more blocks");
        }
    }

    /**
     * What the plugin ships: a box 11 across, one block for each step an advancement lies from its tab's root, and
     * roots, recipes and hidden ones worth nothing.
     */
    public static BoxRules shipped() {
        return new BoxRules(5, 1, 400, Map.of(), List.of("minecraft:recipes/"), AdvancementWorth.SHIPPED);
    }

    /** How far the advancement pushes the box out. */
    public int blocksFor(AdvancementFacts advancement) {
        Objects.requireNonNull(advancement, "advancement must not be null");
        String key = advancement.key();
        for (String prefix : ignored) {
            if (key.startsWith(prefix)) {
                return 0;
            }
        }
        Integer own = worth.get(key);
        if (own != null) {
            return own;
        }
        if (advancement.recipe()) {
            return scoring.recipes();
        }
        if (advancement.depth() == 0) {
            return scoring.root();
        }
        if (!advancement.shown()) {
            return scoring.hidden();
        }
        return scoring.byDepth() ? blocksPerAdvancement * advancement.depth() : blocksPerAdvancement;
    }

    /** How far the box reaches once its island has earned {@code blocks}. */
    public int radius(long blocks) {
        return (int) Math.min(maxRadius, startRadius + Math.max(0, blocks));
    }
}
