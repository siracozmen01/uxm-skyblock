package com.uxplima.uxmskyblock.bukkit.tradewinds;

import java.util.Arrays;
import java.util.Optional;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import com.uxplima.uxmskyblock.bukkit.inventory.BukkitInventorySerializer;
import com.uxplima.uxmskyblock.core.application.tradewinds.PortMarket;
import org.jspecify.annotations.Nullable;

/**
 * The goods of a serialised hold, as a port's market counts them: plain items only. A named, enchanted or
 * otherwise changed item is the crew's own and no port trades it.
 */
public final class HoldGoods implements PortMarket.Goods {

    @Override
    public int count(byte[] hold, String item) {
        ItemStack plain = plain(item);
        if (plain == null) {
            return 0;
        }
        int count = 0;
        for (ItemStack stack : read(hold, 0)) {
            if (stack != null && stack.isSimilar(plain)) {
                count += stack.getAmount();
            }
        }
        return count;
    }

    @Override
    public Optional<byte[]> take(byte[] hold, String item, int count) {
        ItemStack plain = plain(item);
        if (plain == null || count(hold, item) < count) {
            return Optional.empty();
        }
        ItemStack[] stacks = read(hold, 0);
        int left = count;
        for (int i = stacks.length - 1; i >= 0 && left > 0; i--) {
            ItemStack stack = stacks[i];
            if (stack != null && stack.isSimilar(plain)) {
                int taken = Math.min(left, stack.getAmount());
                left -= taken;
                if (taken == stack.getAmount()) {
                    stacks[i] = null;
                } else {
                    stack.setAmount(stack.getAmount() - taken);
                }
            }
        }
        return Optional.of(BukkitInventorySerializer.serializeItemStacks(stacks));
    }

    @Override
    public Optional<byte[]> stow(byte[] hold, int slots, String item, int count) {
        ItemStack plain = plain(item);
        if (plain == null) {
            return Optional.empty();
        }
        ItemStack[] stacks = read(hold, slots);
        plain.setAmount(count);
        return CargoHolds.stow(stacks, plain)
                ? Optional.of(BukkitInventorySerializer.serializeItemStacks(stacks))
                : Optional.empty();
    }

    /**
     * The hold of {@code slots} slots with every one of {@code found} stowed, or empty when they do not all fit: a
     * find goes into the hold whole or not at all.
     */
    public static Optional<byte[]> stowAll(byte[] hold, int slots, java.util.List<ItemStack> found) {
        ItemStack[] stacks = read(hold, slots);
        for (ItemStack item : found) {
            if (!CargoHolds.stow(stacks, item.clone())) {
                return Optional.empty();
            }
        }
        return Optional.of(BukkitInventorySerializer.serializeItemStacks(stacks));
    }

    /** The hold's stacks, at least {@code slots} of them. */
    private static ItemStack[] read(byte[] hold, int slots) {
        ItemStack[] items = hold.length == 0 ? new ItemStack[0] : BukkitInventorySerializer.deserializeItemStacks(hold);
        return items.length >= slots ? items : Arrays.copyOf(items, slots);
    }

    private static @Nullable ItemStack plain(String item) {
        Material material = Material.matchMaterial(item);
        return material == null || material.isAir() ? null : new ItemStack(material);
    }
}
