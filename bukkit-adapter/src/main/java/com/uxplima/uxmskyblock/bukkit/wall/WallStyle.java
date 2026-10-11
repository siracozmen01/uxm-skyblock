package com.uxplima.uxmskyblock.bukkit.wall;

import java.util.Objects;

/**
 * How a wall looks: a flat panel of one colour a player sees through, or a thin slab of a block.
 *
 * @param id the name the operator gave the style
 * @param kind which of the two it is
 * @param argb the colour of a colour panel, its alpha how much it hides
 * @param block the block a block panel is cut from, as {@code minecraft:light_gray_stained_glass}
 */
public record WallStyle(String id, Kind kind, int argb, String block) {

    /** What a wall is drawn with. */
    public enum Kind {
        /** A panel of one colour, as see-through as its alpha says. */
        COLOUR,
        /** A thin slab of a block, as glass or wool. */
        BLOCK
    }

    public WallStyle {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(block, "block must not be null");
    }

    /** A colour panel of {@code rgb} that hides {@code opacity} percent of what stands behind it. */
    public static WallStyle colour(String id, int rgb, int opacity) {
        int alpha = Math.round(Math.clamp(opacity, 0, 100) * 255f / 100f);
        return new WallStyle(id, Kind.COLOUR, (alpha << 24) | (rgb & 0xFFFFFF), "");
    }

    /** A block panel cut from {@code block}. */
    public static WallStyle block(String id, String block) {
        return new WallStyle(id, Kind.BLOCK, 0, block);
    }
}
