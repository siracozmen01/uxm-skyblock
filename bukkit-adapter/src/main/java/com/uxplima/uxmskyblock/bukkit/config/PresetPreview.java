package com.uxplima.uxmskyblock.bukkit.config;

import java.time.Duration;
import java.util.Objects;

import com.uxplima.uxmlib.common.Durations;
import org.spongepowered.configurate.ConfigurationNode;

/**
 * How a player looks at a kind of island before choosing it: as a spectator over its start island, for a set time,
 * then back where they stood. Each kind's start island stands in a showroom of its own, far from every island.
 *
 * @param enabled whether a player may look at all
 * @param time how long a look lasts
 * @param radius how far from the showroom's middle a spectator may drift before they are put back over it
 * @param cooldown how long a player waits between two looks
 * @param world the world the showrooms stand in, the server's island world when blank
 * @param x where the first showroom's middle stands
 * @param z where the first showroom's middle stands
 * @param y the height a start island is laid at there
 * @param spacing how far apart two showrooms stand, along x
 */
public record PresetPreview(
        boolean enabled, Duration time, int radius, Duration cooldown, String world, int x, int z, int y, int spacing) {

    /** As shipped: twenty seconds over the start island, a far corner of the island world. */
    public static final PresetPreview SHIPPED = new PresetPreview(
            true, Duration.ofSeconds(20), 48, Duration.ofSeconds(30), "", -1_000_000, -1_000_000, 100, 1024);

    public PresetPreview {
        Objects.requireNonNull(time, "time must not be null");
        Objects.requireNonNull(cooldown, "cooldown must not be null");
        world = Objects.requireNonNull(world, "world must not be null").strip();
        if (time.isNegative() || time.isZero()) {
            throw new IllegalArgumentException("a look lasts longer than nothing: " + time);
        }
        if (cooldown.isNegative()) {
            throw new IllegalArgumentException("a cooldown is not below nothing: " + cooldown);
        }
        if (radius < 8) {
            throw new IllegalArgumentException("a spectator drifts at least eight blocks: " + radius);
        }
        if (spacing < 2 * radius) {
            throw new IllegalArgumentException("showrooms stand at least two radii apart: " + spacing);
        }
    }

    /** The middle of the showroom of the kind at {@code index}, along x from the first. */
    public int xOf(int index) {
        return Math.addExact(x, Math.multiplyExact(index, spacing));
    }

    /**
     * Reads {@code presets.preview}. A file without it gets the shipped look; a value that cannot stand is said in
     * the console, and the shipped value is used for it.
     */
    public static PresetPreview load(ConfigurationNode node) {
        Objects.requireNonNull(node, "node must not be null");
        if (node.virtual()) {
            return SHIPPED;
        }
        PresetPreview shipped = SHIPPED;
        Duration time = duration(node.node("time"), shipped.time());
        Duration cooldown = duration(node.node("cooldown"), shipped.cooldown());
        try {
            return new PresetPreview(
                    node.node("enabled").getBoolean(shipped.enabled()),
                    time,
                    node.node("radius").getInt(shipped.radius()),
                    cooldown,
                    node.node("world").getString(shipped.world()),
                    node.node("x").getInt(shipped.x()),
                    node.node("z").getInt(shipped.z()),
                    node.node("y").getInt(shipped.y()),
                    node.node("spacing").getInt(shipped.spacing()));
        } catch (IllegalArgumentException wrong) {
            LOGGER.warning(() -> "presets.preview cannot stand as written (" + wrong.getMessage()
                    + "), so the shipped preview is used.");
            return shipped;
        }
    }

    /** A time written as "20s" or "1m30s", or {@code fallback} when it is left out or reads as none. */
    private static Duration duration(ConfigurationNode node, Duration fallback) {
        String raw = node.getString();
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Durations.parse(raw.strip());
        } catch (RuntimeException unreadable) {
            LOGGER.warning(() -> "presets.preview." + node.key() + " is '" + raw + "', which is no time, so "
                    + fallback.toSeconds() + " seconds is used.");
            return fallback;
        }
    }

    private static final java.util.logging.Logger LOGGER =
            java.util.logging.Logger.getLogger(PresetPreview.class.getName());
}
