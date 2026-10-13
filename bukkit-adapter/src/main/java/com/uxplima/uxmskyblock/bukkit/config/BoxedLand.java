package com.uxplima.uxmskyblock.bukkit.config;

import java.util.Locale;
import java.util.logging.Logger;

import org.spongepowered.configurate.ConfigurationNode;

/**
 * The land a new Boxed island plays on, as {@code land} of {@code modules/boxed.conf} writes it.
 *
 * @param template whether a new island is played on the template's land, the same for every island, rather than on
 *     the land its world made where it stands
 * @param x where the template's land lies in the island's world, along x
 * @param z and along z
 * @param ahead how far past the box the land is copied ahead of it
 */
public record BoxedLand(boolean template, int x, int z, int ahead) {

    /** The template's land, far out at x and z 1000000, copied 16 blocks ahead of the box. */
    public static final BoxedLand SHIPPED = new BoxedLand(true, 1_000_000, 1_000_000, 16);

    /** The furthest a template may lie from the middle of the world, inside the world's own border. */
    private static final int REACH = 29_000_000;

    private static final Logger LOGGER = Logger.getLogger(BoxedLand.class.getName());

    public BoxedLand {
        if (Math.abs(x) > REACH || Math.abs(z) > REACH) {
            throw new IllegalArgumentException(
                    "the template lies inside the world, within " + REACH + ": " + x + ", " + z);
        }
        if (ahead < 0 || ahead > 128) {
            throw new IllegalArgumentException("the land is copied from 0 to 128 blocks ahead of the box: " + ahead);
        }
    }

    /** How many rings of chunks around an island's centre a box of {@code radius} wants copied. */
    public int rings(int radius) {
        return Math.ceilDiv(radius + ahead, 16);
    }

    /** The land {@code node} writes, or the shipped land, said, when it writes one that cannot be. */
    static BoxedLand read(ConfigurationNode node) {
        if (node.virtual()) {
            return SHIPPED;
        }
        String from = node.node("from").getString("template").strip().toLowerCase(Locale.ROOT);
        try {
            if (!from.equals("template") && !from.equals("world")) {
                throw new IllegalArgumentException("from is template or world, not " + from);
            }
            return new BoxedLand(
                    from.equals("template"),
                    node.node("template", "x").getInt(SHIPPED.x()),
                    node.node("template", "z").getInt(SHIPPED.z()),
                    node.node("ahead").getInt(SHIPPED.ahead()));
        } catch (IllegalArgumentException e) {
            LOGGER.warning(() -> "modules/boxed.conf land: " + e.getMessage() + ". The shipped land is used.");
            return SHIPPED;
        }
    }
}
