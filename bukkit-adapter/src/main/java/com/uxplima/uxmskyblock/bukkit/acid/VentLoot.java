package com.uxplima.uxmskyblock.bukkit.acid;

import java.util.List;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Rolls the loot table an offering is answered from, at the vent, for the player it answers. */
@FunctionalInterface
public interface VentLoot {

    /** One roll of the table of that key; nothing for a table the server does not have. */
    List<ItemStack> roll(String table, Location at, Player player);
}
