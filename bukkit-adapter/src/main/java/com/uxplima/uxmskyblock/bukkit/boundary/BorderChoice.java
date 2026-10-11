package com.uxplima.uxmskyblock.bukkit.boundary;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

/**
 * Whether a player hid the island border, and the colour they draw it in, kept on the player where the server keeps
 * them, so the choice outlives a restart and a rejoin. Read and written on the player's own thread.
 */
public final class BorderChoice {

    /** The mark a player who hid the border carries. */
    static final NamespacedKey HIDDEN = new NamespacedKey("uxmskyblock", "border_hidden");

    /** The colour a player picked, by its id. */
    static final NamespacedKey COLOUR = new NamespacedKey("uxmskyblock", "border_colour");

    private BorderChoice() {}

    /** Whether {@code player} hid the border. */
    public static boolean hidden(Player player) {
        return player.getPersistentDataContainer().has(HIDDEN, PersistentDataType.BYTE);
    }

    /** Keeps whether {@code player} shows the border. */
    public static void keep(Player player, boolean shown) {
        if (shown) {
            player.getPersistentDataContainer().remove(HIDDEN);
        } else {
            player.getPersistentDataContainer().set(HIDDEN, PersistentDataType.BYTE, (byte) 1);
        }
    }

    /** The colour {@code player} draws the border in, blue until they pick another. */
    public static BorderColour colour(Player player) {
        String id = player.getPersistentDataContainer().get(COLOUR, PersistentDataType.STRING);
        return id == null ? BorderColour.BLUE : BorderColour.of(id).orElse(BorderColour.BLUE);
    }

    /** Keeps the colour {@code player} draws the border in. */
    public static void keep(Player player, BorderColour colour) {
        player.getPersistentDataContainer().set(COLOUR, PersistentDataType.STRING, colour.id());
    }
}
