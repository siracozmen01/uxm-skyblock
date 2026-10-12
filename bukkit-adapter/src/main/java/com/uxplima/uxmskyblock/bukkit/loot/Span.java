package com.uxplima.uxmskyblock.bukkit.loot;

import java.util.Random;

/** A whole number from {@code min} to {@code max}, both included, as a file writes it: {@code "2-4"} or {@code "3"}. */
public record Span(int min, int max) {

    public Span {
        if (min < 0 || max < min) {
            throw new IllegalArgumentException("a span runs from a number to one no smaller: " + min + "-" + max);
        }
    }

    /** One number, the same every time. */
    public static Span of(int exactly) {
        return new Span(exactly, exactly);
    }

    /** The span written as {@code "2-4"} or {@code "3"}. */
    public static Span parse(String written) {
        String text = written.strip();
        int dash = text.indexOf('-', 1);
        try {
            if (dash < 0) {
                return of(Integer.parseInt(text));
            }
            return new Span(
                    Integer.parseInt(text.substring(0, dash).strip()),
                    Integer.parseInt(text.substring(dash + 1).strip()));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + written + "' is not a number or a span like 2-4", e);
        }
    }

    /** A number in the span. */
    public int pick(Random random) {
        return min == max ? min : min + random.nextInt(max - min + 1);
    }
}
