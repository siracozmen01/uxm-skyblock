package com.uxplima.uxmskyblock.bukkit.config;

import java.util.List;
import java.util.Objects;

import org.spongepowered.configurate.ConfigurationNode;

/**
 * How the island's own money is drawn in the bank window, as {@code bank.money} in {@code modules/bank.conf} says.
 *
 * <p>The money is the server's economy and is always kept, so nothing here turns it on or off. It stands in the
 * window among the other currencies, as one of them, under the name, item and place the operator gives it.
 *
 * @param displayName its name, a catalogue key written {@code @key} or the words themselves
 * @param icon the item its tile wears
 * @param order where it stands among the other currencies, the lowest first
 * @param quick the amounts its window moves in one click, the smallest first
 */
public record BankMoneySpec(String displayName, String icon, int order, List<Long> quick) {

    /** The money as the plugin ships it: first, a gold ingot, a hundred and a thousand a click. */
    public static final BankMoneySpec DEFAULT =
            new BankMoneySpec("@bank.currencies.money", "GOLD_INGOT", 0, List.of(100L, 1000L));

    public BankMoneySpec {
        Objects.requireNonNull(displayName, "displayName must not be null");
        Objects.requireNonNull(icon, "icon must not be null");
        quick = List.copyOf(Objects.requireNonNull(quick, "quick must not be null"));
    }

    /** Reads {@code bank.money}, every key it leaves out as shipped. */
    public static BankMoneySpec load(ConfigurationNode root) {
        ConfigurationNode node =
                Objects.requireNonNull(root, "root must not be null").node("bank", "money");
        return new BankMoneySpec(
                node.node("display-name").getString(DEFAULT.displayName()),
                node.node("icon").getString(DEFAULT.icon()),
                node.node("order").getInt(DEFAULT.order()),
                BankCurrencySpec.quickOf(node.node("quick"), DEFAULT.quick()));
    }
}
