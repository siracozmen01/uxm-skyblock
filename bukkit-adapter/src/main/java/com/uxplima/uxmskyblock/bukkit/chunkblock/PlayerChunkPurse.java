package com.uxplima.uxmskyblock.bukkit.chunkblock;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import com.uxplima.uxmskyblock.bukkit.integration.economy.BankWallets;
import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkRequirement;

/**
 * A player's own purse, as a chunk's price reaches into it: the server's money through the economy, a currency of
 * {@code modules/bank.conf} through the wallet it lives in, and plain items out of the inventory. An item that was
 * renamed, enchanted or worn is not plain, and is never taken.
 */
public final class PlayerChunkPurse implements ChunkPurse {

    /** The player's own money, in the economy's units. */
    public interface Money {
        double balance(Player player);

        boolean withdraw(Player player, double amount);

        boolean deposit(Player player, double amount);
    }

    private final Money money;
    private final Function<String, Optional<BankWallets.Kept>> currencies;

    /** @param currencies the currency of {@code modules/bank.conf} kept under an id, while it can be reached */
    public PlayerChunkPurse(Money money, Function<String, Optional<BankWallets.Kept>> currencies) {
        this.money = Objects.requireNonNull(money, "money must not be null");
        this.currencies = Objects.requireNonNull(currencies, "currencies must not be null");
    }

    @Override
    public long carried(Player player, ChunkRequirement line) {
        return switch (line) {
            case ChunkRequirement.Money ignored -> (long) Math.floor(money.balance(player) * 100.0 + 1e-6);
            case ChunkRequirement.Currency currency ->
                currencies
                        .apply(currency.id())
                        .map(kept -> (long) Math.floor(kept.wallet().balance(player, kept.pool())))
                        .orElse(0L);
            case ChunkRequirement.Items items ->
                // What a sealed player holds was made in a creative place, and pays for nothing.
                com.uxplima.uxmskyblock.bukkit.creative.SealedInventory.holds(player)
                        ? 0L
                        : material(items)
                                .map(type -> count(player.getInventory(), type))
                                .orElse(0L);
            default -> 0L;
        };
    }

    @Override
    public long take(Player player, ChunkRequirement line, long wanted) {
        long amount = Math.min(wanted, carried(player, line));
        if (amount <= 0) {
            return 0;
        }
        return switch (line) {
            case ChunkRequirement.Money ignored -> money.withdraw(player, amount / 100.0) ? amount : 0;
            case ChunkRequirement.Currency currency ->
                currencies
                        .apply(currency.id())
                        .filter(kept -> kept.wallet().withdraw(player, kept.pool(), (double) amount))
                        .map(kept -> amount)
                        .orElse(0L);
            case ChunkRequirement.Items items ->
                material(items)
                        .map(type -> remove(player.getInventory(), type, amount))
                        .orElse(0L);
            default -> 0L;
        };
    }

    @Override
    public void giveBack(Player player, ChunkRequirement line, long amount) {
        if (amount <= 0) {
            return;
        }
        switch (line) {
            case ChunkRequirement.Money ignored -> money.deposit(player, amount / 100.0);
            case ChunkRequirement.Currency currency ->
                currencies
                        .apply(currency.id())
                        .ifPresent(kept -> kept.wallet().deposit(player, kept.pool(), (double) amount));
            case ChunkRequirement.Items items -> material(items).ifPresent(type -> give(player, type, amount));
            default -> {
                // Points and levels are the island's, and never left the player.
            }
        }
    }

    /** The item {@code items} names, or nothing for a name this server does not know as an item. */
    static Optional<Material> material(ChunkRequirement.Items items) {
        NamespacedKey key = NamespacedKey.fromString(items.material());
        if (key == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(Registry.MATERIAL.get(key)).filter(Material::isItem);
    }

    private static boolean plain(ItemStack stack, Material type) {
        return stack != null && stack.getType() == type && stack.isSimilar(new ItemStack(type));
    }

    private static long count(PlayerInventory inventory, Material type) {
        long count = 0;
        for (ItemStack stack : inventory.getStorageContents()) {
            if (plain(stack, type)) {
                count += stack.getAmount();
            }
        }
        return count;
    }

    private static long remove(PlayerInventory inventory, Material type, long wanted) {
        ItemStack[] contents = inventory.getStorageContents();
        long left = wanted;
        for (int slot = 0; slot < contents.length && left > 0; slot++) {
            ItemStack stack = contents[slot];
            if (!plain(stack, type)) {
                continue;
            }
            int taken = (int) Math.min(stack.getAmount(), left);
            left -= taken;
            if (taken == stack.getAmount()) {
                contents[slot] = null;
            } else {
                stack.setAmount(stack.getAmount() - taken);
            }
        }
        inventory.setStorageContents(contents);
        return wanted - left;
    }

    private static void give(Player player, Material type, long amount) {
        long left = amount;
        int stackSize = type.getMaxStackSize();
        while (left > 0) {
            int size = (int) Math.min(stackSize, left);
            left -= size;
            Map<Integer, ItemStack> spilled = player.getInventory().addItem(new ItemStack(type, size));
            spilled.values().forEach(stack -> player.getWorld().dropItemNaturally(player.getLocation(), stack));
        }
    }
}
