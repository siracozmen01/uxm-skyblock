package com.uxplima.uxmskyblock.bukkit.acid;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.loot.LootContext;
import org.bukkit.loot.LootTable;

/** The server's own loot tables, rolled where the vent is, with the player it answers as the one looting. */
public final class ServerVentLoot implements VentLoot {

    private static final Logger LOGGER = Logger.getLogger(ServerVentLoot.class.getName());

    private final Set<String> toldOf = ConcurrentHashMap.newKeySet();

    @Override
    public List<ItemStack> roll(String table, Location at, Player player) {
        NamespacedKey key = NamespacedKey.fromString(table);
        LootTable loot = key == null ? null : Bukkit.getLootTable(key);
        if (loot == null) {
            if (toldOf.add(table)) {
                LOGGER.warning(() -> "modules/acidisland.conf names the loot table " + table
                        + " for an offering, which the server does not have. The offering gives nothing back.");
            }
            return List.of();
        }
        return List.copyOf(loot.populateLoot(
                ThreadLocalRandom.current(),
                new LootContext.Builder(at).lootedEntity(player).build()));
    }
}
