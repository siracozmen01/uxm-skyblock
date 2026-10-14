package com.uxplima.uxmskyblock.bukkit.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.spongepowered.configurate.ConfigurationNode;

/**
 * How a list or a period is read from a file: the shipped value kept when the file names nothing usable. Split out of {@link AcidIslandConfiguration}, whose services and words it uses, so neither file is over the size the
 * standards draw a line at.
 */
final class AcidIslandValues {

    private AcidIslandValues() {}

    /** A span written as {@code 500ms}, {@code 2s}, {@code 5m}, {@code 1h}, {@code 3d} or a plain number of seconds. */
    static Duration durationOf(String written, Duration fallback) {
        String raw = written.trim().toLowerCase(java.util.Locale.ROOT);
        if (raw.isEmpty()) {
            return fallback;
        }
        try {
            if (raw.endsWith("ms")) {
                return Duration.ofMillis(
                        Long.parseLong(raw.substring(0, raw.length() - 2).trim()));
            }
            if (raw.endsWith("s")) {
                return Duration.ofMillis(Math.round(
                        Double.parseDouble(raw.substring(0, raw.length() - 1).trim()) * 1000));
            }
            if (raw.endsWith("m")) {
                return Duration.ofMillis(Math.round(
                        Double.parseDouble(raw.substring(0, raw.length() - 1).trim()) * 60_000));
            }
            if (raw.endsWith("h")) {
                return Duration.ofMillis(Math.round(
                        Double.parseDouble(raw.substring(0, raw.length() - 1).trim()) * 3_600_000));
            }
            if (raw.endsWith("d")) {
                return Duration.ofMillis(Math.round(
                        Double.parseDouble(raw.substring(0, raw.length() - 1).trim()) * 86_400_000));
            }
            // Java reads "3f" as a number; a unit this does not know is no number of seconds.
            if (Character.isLetter(raw.charAt(raw.length() - 1))) {
                return fallback;
            }
            return Duration.ofMillis(Math.round(Double.parseDouble(raw) * 1000));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
    /** The strings of a list, or the fallback when the list is not written at all. */
    static List<String> strings(ConfigurationNode node, List<String> fallback) {
        if (node.virtual()) {
            return fallback;
        }
        List<String> read = new ArrayList<>();
        for (ConfigurationNode child : node.childrenList()) {
            String value = child.getString("").trim();
            if (!value.isEmpty()) {
                read.add(value);
            }
        }
        return read;
    }
}
