package com.uxplima.uxmskyblock.bukkit.integration.economy;

import java.util.Map;
import java.util.Objects;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import com.uxplima.uxmlib.condition.Wallet;
import org.jspecify.annotations.Nullable;

/**
 * An item in the player's inventory as a currency: diamonds, emeralds, a server's own token item.
 *
 * <p>Only the plain item counts. A renamed or enchanted stack of the same material is something the player made or
 * was given, not money, and taking it as money would take what they meant to keep. It lives in the player, so it is
 * read and moved on the thread that owns them, and a player who is not online holds none.
 */
final class ItemWallet implements Wallet {

    private final Material material;

    ItemWallet(Material material) {
        this.material = Objects.requireNonNull(material, "material must not be null");
        if (material.isAir() || !material.isItem()) {
            throw new IllegalArgumentException(material + " is not an item a player can carry");
        }
    }

    @Override
    public double balance(@Nullable Player player, String currency) {
        return player == null ? 0 : carried(player);
    }

    @Override
    public boolean withdraw(@Nullable Player player, String currency, double amount) {
        int wanted = whole(amount);
        if (player == null || wanted < 0 || carried(player) < wanted) {
            return false;
        }
        if (wanted == 0) {
            return true;
        }
        // What removeItem could not find comes back. A part payment is no payment: what it did take goes back.
        Map<Integer, ItemStack> shortfall = player.getInventory().removeItem(new ItemStack(material, wanted));
        int missing = shortfall.values().stream().mapToInt(ItemStack::getAmount).sum();
        if (missing > 0) {
            player.getInventory().addItem(new ItemStack(material, wanted - missing));
            return false;
        }
        return true;
    }

    /** Pays the items in, and drops at the player's feet whatever their inventory has no room for. */
    @Override
    public boolean deposit(@Nullable Player player, String currency, double amount) {
        int given = whole(amount);
        if (player == null || given < 0 || com.uxplima.uxmskyblock.bukkit.creative.SealedInventory.holds(player)) {
            // Paid into a sealed player's hands, it would go with the creative place's things at the unseal.
            return false;
        }
        int left = given;
        while (left > 0) {
            int stack = Math.min(left, material.getMaxStackSize());
            for (ItemStack rest : player.getInventory()
                    .addItem(new ItemStack(material, stack))
                    .values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), rest);
            }
            left -= stack;
        }
        return true;
    }

    private int carried(Player player) {
        if (com.uxplima.uxmskyblock.bukkit.creative.SealedInventory.holds(player)) {
            // What a sealed player holds was made in a creative place, and pays for nothing.
            return 0;
        }
        ItemStack plain = new ItemStack(material);
        int total = 0;
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack != null && stack.isSimilar(plain)) {
                total += stack.getAmount();
            }
        }
        return total;
    }

    /** A whole number of items, or -1 for an amount that is not one. */
    private static int whole(double amount) {
        if (amount < 0 || amount != Math.rint(amount) || amount > Integer.MAX_VALUE) {
            return -1;
        }
        return (int) amount;
    }
}
