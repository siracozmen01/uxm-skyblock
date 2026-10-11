package com.uxplima.uxmskyblock.bukkit.boundary;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

/**
 * Whether a player hid the island border, kept on the player where the server keeps them, so the choice outlives a
 * restart and a rejoin. Read and written on the player's own thread.
 */
public final class BorderChoice {

    /** The mark a player who hid the border carries. */
    static final NamespacedKey HIDDEN = new NamespacedKey("uxmskyblock", "border_hidden");

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
}
