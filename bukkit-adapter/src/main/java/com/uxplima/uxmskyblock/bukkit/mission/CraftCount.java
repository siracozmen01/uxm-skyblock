package com.uxplima.uxmskyblock.bukkit.mission;

import org.bukkit.inventory.ItemStack;

import org.jspecify.annotations.Nullable;

/**
 * How many items one craft made, for a craft mission.
 *
 * <p>A plain click makes the recipe's result once. A shift click makes it as many times as the grid holds the
 * ingredients for, and no more than the player's inventory has room to take: a craft the inventory cannot hold makes
 * nothing, and counting it would pay a mission for items never made.
 */
final class CraftCount {

    private CraftCount() {}

    /**
     * @param grid the crafting grid as it stood, an empty slot as {@code null} or air
     * @param storage the player's inventory storage, an empty slot as {@code null} or air
     */
    static long made(ItemStack result, @Nullable ItemStack[] grid, boolean shiftClick, @Nullable ItemStack[] storage) {
        if (result.getType().isAir() || result.getAmount() <= 0) {
            return 0;
        }
        if (!shiftClick) {
            return result.getAmount();
        }
        int times = Integer.MAX_VALUE;
        for (ItemStack ingredient : grid) {
            if (ingredient != null && !ingredient.getType().isAir()) {
                times = Math.min(times, ingredient.getAmount());
            }
        }
        if (times == Integer.MAX_VALUE) {
            return 0;
        }
        long room = 0;
        int most = result.getMaxStackSize();
        for (ItemStack slot : storage) {
            if (slot == null || slot.getType().isAir()) {
                room += most;
            } else if (slot.isSimilar(result)) {
                room += Math.max(0, most - slot.getAmount());
            }
        }
        long wanted = (long) times * result.getAmount();
        // The inventory takes whole crafts only.
        long fits = room / result.getAmount() * result.getAmount();
        return Math.min(wanted, fits);
    }
}
