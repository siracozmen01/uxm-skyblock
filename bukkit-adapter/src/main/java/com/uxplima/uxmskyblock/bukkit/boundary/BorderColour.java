package com.uxplima.uxmskyblock.bukkit.boundary;

import java.util.Locale;
import java.util.Optional;

import org.bukkit.WorldBorder;

/**
 * The colours a client draws a border in. The game has three and no more: a border standing still is blue, one
 * growing is green, and one shrinking is red. A green or red border is one moving by a block over many years, so
 * it stands where the island ends and keeps its colour.
 */
public enum BorderColour {
    BLUE,
    GREEN,
    RED;

    /** How far a coloured border moves, in blocks, over {@link #FOREVER}. */
    static final double DRIFT = 1.0;

    /** How long a coloured border takes to move by {@link #DRIFT}, in seconds: some sixty years. */
    static final long FOREVER = Integer.MAX_VALUE;

    /** The colour of this name, written in any case. */
    public static Optional<BorderColour> of(String name) {
        try {
            return Optional.of(valueOf(name.strip().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException unknown) {
            return Optional.empty();
        }
    }

    /** The name a file and a catalogue key use for this colour. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Sets {@code border} to {@code width} across, drawn in this colour. */
    @SuppressWarnings({"deprecation", "removal"})
    void paint(WorldBorder border, double width) {
        border.setSize(width);
        switch (this) {
            case BLUE -> {}
            case GREEN -> border.setSize(width + DRIFT, FOREVER);
            case RED -> border.setSize(width - DRIFT, FOREVER);
        }
    }
}
