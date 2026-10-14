package com.uxplima.uxmskyblock.bukkit.config;

import java.time.Duration;
import java.util.Objects;

import com.uxplima.uxmskyblock.core.application.bank.IslandBankInterest;
import org.spongepowered.configurate.ConfigurationNode;

/**
 * What an island bank pays out of {@code modules/bank.conf}: interest on its money, and the share of it its owner is
 * paid back when the island is deleted.
 *
 * @param interest how interest is paid
 * @param deleteRefundShare the share of the bank's money paid to the owner when the island is deleted, zero to one
 */
public record BankPayoutsConfiguration(IslandBankInterest.Policy interest, double deleteRefundShare) {

    public BankPayoutsConfiguration {
        Objects.requireNonNull(interest, "interest must not be null");
        if (deleteRefundShare < 0 || deleteRefundShare > 1) {
            throw new IllegalArgumentException("deleteRefundShare must be from zero to one");
        }
    }

    public static BankPayoutsConfiguration defaultConfiguration() {
        return new BankPayoutsConfiguration(
                new IslandBankInterest.Policy(false, Duration.ofHours(24), 1.0, Duration.ofHours(72), 0L), 0.0);
    }

    public static BankPayoutsConfiguration load(ConfigurationNode root) {
        Objects.requireNonNull(root, "root must not be null");
        BankPayoutsConfiguration shipped = defaultConfiguration();
        IslandBankInterest.Policy fallback = shipped.interest();
        ConfigurationNode interest = root.node("bank", "interest");
        IslandBankInterest.Policy policy = interest.virtual()
                ? fallback
                : new IslandBankInterest.Policy(
                        interest.node("enabled").getBoolean(fallback.enabled()),
                        duration(interest.node("interval"), fallback.interval()),
                        Math.max(0.0, interest.node("percent").getDouble(fallback.percent())),
                        duration(interest.node("active-within"), fallback.activeWithin()),
                        Math.max(0L, Math.round(interest.node("max-per-payment").getDouble(0.0) * 100.0)));
        double share = root.node("bank", "delete-refund", "share").getDouble(shipped.deleteRefundShare());
        return new BankPayoutsConfiguration(policy, Math.max(0.0, Math.min(1.0, share)));
    }

    private static Duration duration(ConfigurationNode node, Duration fallback) {
        String written = node.getString();
        if (written == null || written.isBlank()) {
            return fallback;
        }
        try {
            Duration read = com.uxplima.uxmlib.common.Durations.parse(written);
            return read.isZero() || read.isNegative() ? fallback : read;
        } catch (RuntimeException unreadable) {
            return fallback;
        }
    }
}
