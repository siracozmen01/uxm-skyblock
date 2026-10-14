package com.uxplima.uxmskyblock.bukkit.listener;

import org.bukkit.Location;
import org.bukkit.event.inventory.InventoryType;

import org.jspecify.annotations.Nullable;

/**
 * Where an opened container stands: the block a container's inventory belongs to, and whether that kind of inventory stands in the world. Split out of {@link IslandProtectionListener}, whose services and words it uses, so neither file is over the size the
 * standards draw a line at.
 */
final class ContainerPlaces {

    private ContainerPlaces() {}

    /**
     * Whether the block at an inventory's place is the block that holds that inventory.
     *
     * <p>A menu that asks a player to type something opens an anvil window, and Paper gives that
     * window the place the player stands. Read as a real anvil there, it was refused wherever the
     * player's role could not use an anvil, and on another island or at spawn setting a home or
     * inviting a member from the menu said storage access was denied.
     */
    static boolean standsThere(InventoryType type, org.bukkit.block.Block block) {
        org.bukkit.Material material = block.getType();
        return switch (type) {
            case ANVIL -> org.bukkit.Tag.ANVIL.isTagged(material);
            case SMITHING -> material == org.bukkit.Material.SMITHING_TABLE;
            case BEACON -> material == org.bukkit.Material.BEACON;
            default -> block.getState() instanceof org.bukkit.inventory.InventoryHolder;
        };
    }
    /**
     * Where the block holding this inventory stands, or null when no block holds it.
     *
     * <p>The holder answers this and the inventory's own location does not: a window this plugin
     * draws is an inventory like any other, and asking it where it is gets an answer that means
     * nothing. A block holder is a container in the world and nothing else is.
     */
    static @Nullable Location blockLocationOf(org.bukkit.inventory.Inventory inventory) {
        return switch (inventory.getHolder()) {
            case org.bukkit.inventory.BlockInventoryHolder block ->
                block.getBlock().getLocation();
            case org.bukkit.block.DoubleChest chest -> chest.getLocation();
            case null, default -> null;
        };
    }
}
