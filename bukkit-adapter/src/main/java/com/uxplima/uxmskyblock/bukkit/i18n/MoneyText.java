package com.uxplima.uxmskyblock.bukkit.i18n;

import java.util.Locale;
import java.util.Objects;
import java.util.function.DoubleFunction;

import org.jspecify.annotations.Nullable;

/**
 * An amount of the island currency as a player reads it.
 *
 * <p>Money is kept in minor units, a hundred to the unit. The bank showed it as units and cents while
 * the upgrades showed the raw count, so the same price read as 500.00 in one line and 50000 in the
 * next. Every line that shows money shows it through this.
 *
 * <p>The language files wrote a dollar sign in front of every amount, on a server whose money might be
 * coins, gems or anything else. An amount is now written the way the operator says in {@code bank.money.format}:
 * as the economy plugin writes its own money, with its own sign, or after a pattern of the operator's.
 */
public final class MoneyText {

    /** The format that hands an amount to the economy plugin to write. */
    public static final String ECONOMY = "economy";

    private static volatile DoubleFunction<String> written = MoneyText::plain;

    private MoneyText() {}

    /** {@code minorUnits} as the server writes its money. */
    public static String of(long minorUnits) {
        return written.apply(minorUnits / 100.0);
    }

    /** {@code units} whole units of money as the server writes it. */
    public static String ofUnits(long units) {
        return written.apply((double) units);
    }

    /** Writes every amount from now on with {@code writer}. */
    public static void use(DoubleFunction<String> writer) {
        written = Objects.requireNonNull(writer, "writer must not be null");
    }

    /** Writes every amount as plain units with two decimals again, as when the plugin starts. */
    public static void reset() {
        written = MoneyText::plain;
    }

    /**
     * The writer {@code format} names: {@link #ECONOMY} for the economy plugin's own, or a pattern with
     * {@code <amount>} where the number goes, a sign or a word of the operator's around it.
     *
     * @param economy how the economy plugin writes an amount, or nothing when no economy is there. An amount it
     *     cannot write, or writes as nothing, is written plain.
     */
    public static DoubleFunction<String> writer(String format, @Nullable DoubleFunction<String> economy) {
        Objects.requireNonNull(format, "format must not be null");
        if (ECONOMY.equalsIgnoreCase(format.strip())) {
            if (economy == null) {
                return MoneyText::plain;
            }
            return amount -> {
                try {
                    String own = economy.apply(amount);
                    return own == null || own.isBlank() ? plain(amount) : own;
                } catch (RuntimeException e) {
                    return plain(amount);
                }
            };
        }
        return amount -> format.replace("<amount>", plain(amount));
    }

    /** {@code units} with two decimals and its thousands set apart, as the leaderboard has always shown it. */
    static String plain(double units) {
        return String.format(Locale.ROOT, "%,.2f", units);
    }
}
