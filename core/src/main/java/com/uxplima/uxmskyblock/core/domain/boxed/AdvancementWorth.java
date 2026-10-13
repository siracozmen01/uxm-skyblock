package com.uxplima.uxmskyblock.core.domain.boxed;

/**
 * What an advancement with no worth of its own is worth, by where it lies.
 *
 * @param byDepth whether it is worth the blocks for one advancement for each step it lies from its tab's root, so a
 *     hard one deep in a tab is worth more than an easy one by the root, rather than the blocks for one each
 * @param root what a tab's root is worth, which every player is given on joining
 * @param recipes what a recipe unlocked is worth
 * @param hidden what one with no place in the advancements screen is worth
 */
public record AdvancementWorth(boolean byDepth, int root, int recipes, int hidden) {

    /** What the plugin ships: by depth, and a root, a recipe and a hidden one worth nothing. */
    public static final AdvancementWorth SHIPPED = new AdvancementWorth(true, 0, 0, 0);

    public AdvancementWorth {
        if (root < 0 || recipes < 0 || hidden < 0) {
            throw new IllegalArgumentException("an advancement pushes the box out by 0 or more blocks");
        }
    }

    /** Every advancement worth {@code each}, wherever it lies. */
    public static AdvancementWorth flat(int each) {
        return new AdvancementWorth(false, each, each, each);
    }
}
