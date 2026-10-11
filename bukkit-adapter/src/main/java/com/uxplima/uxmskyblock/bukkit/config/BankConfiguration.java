package com.uxplima.uxmskyblock.bukkit.config;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

import com.uxplima.uxmlib.common.Durations;
import com.uxplima.uxmskyblock.core.domain.bank.IslandUpkeepPolicy;
import org.spongepowered.configurate.ConfigurationNode;

/**
 * Configuration holder for island bank and upkeep failure policy (Section 2.39).
 */
public record BankConfiguration(
        IslandUpkeepPolicy upkeepPolicy,
        Duration operationRetention,
        List<BankCurrencySpec> currencies,
        BankMoneySpec money) {

    public static final boolean DEFAULT_ENABLED = false;
    public static final Duration DEFAULT_INTERVAL = Duration.ofHours(24);
    public static final long DEFAULT_BASE_FEE_MINOR = 50_000L;
    public static final long DEFAULT_PER_MEMBER_FEE_MINOR = 10_000L;
    public static final Duration DEFAULT_GRACE_DURATION = Duration.ofDays(3);
    public static final boolean DEFAULT_AUTO_REMEDIATE = true;

    /**
     * How long a settled idempotency record is kept.
     *
     * <p>Every deposit, withdrawal, upgrade purchase, shop trade and upkeep charge writes one so a
     * retry of it is answered rather than applied twice. It has to outlive every retry anything can
     * make, and the web store's own window is a day.
     */
    public static final Duration DEFAULT_OPERATION_RETENTION = Duration.ofDays(30);

    public BankConfiguration {
        currencies = List.copyOf(Objects.requireNonNull(currencies, "currencies must not be null"));
        Objects.requireNonNull(money, "money must not be null");
        Objects.requireNonNull(upkeepPolicy, "upkeepPolicy must not be null");
        Objects.requireNonNull(operationRetention, "operationRetention must not be null");
        if (operationRetention.isNegative()) {
            throw new IllegalArgumentException("operationRetention must not be negative: " + operationRetention);
        }
    }

    /** The money drawn as shipped. */
    public BankConfiguration(
            IslandUpkeepPolicy upkeepPolicy, Duration operationRetention, List<BankCurrencySpec> currencies) {
        this(upkeepPolicy, operationRetention, currencies, BankMoneySpec.DEFAULT);
    }

    /** The shape before the bank kept more than the island's own money. */
    public BankConfiguration(IslandUpkeepPolicy upkeepPolicy, Duration operationRetention) {
        this(upkeepPolicy, operationRetention, List.of());
    }

    /** The one-argument shape, for a caller that names no retention. */
    public BankConfiguration(IslandUpkeepPolicy upkeepPolicy) {
        this(upkeepPolicy, DEFAULT_OPERATION_RETENTION);
    }

    /** The currencies turned on, in order. */
    public List<BankCurrencySpec> enabledCurrencies() {
        return currencies.stream().filter(BankCurrencySpec::enabled).toList();
    }

    public static BankConfiguration defaultConfiguration() {
        return new BankConfiguration(IslandUpkeepPolicy.defaultPolicy());
    }

    public static BankConfiguration load(ConfigurationNode rootNode) {
        Objects.requireNonNull(rootNode, "rootNode must not be null");
        String retentionRaw = rootNode.node("bank", "operation-retention").getString();
        Duration operationRetention = retentionRaw != null && !retentionRaw.isBlank()
                ? Durations.parse(retentionRaw)
                : DEFAULT_OPERATION_RETENTION;

        List<BankCurrencySpec> currencies = BankCurrencySpec.load(rootNode);
        BankMoneySpec money = BankMoneySpec.load(rootNode);
        ConfigurationNode upkeepNode = rootNode.node("bank", "upkeep");
        if (upkeepNode.virtual() || upkeepNode.empty()) {
            return new BankConfiguration(IslandUpkeepPolicy.defaultPolicy(), operationRetention, currencies, money);
        }

        boolean enabled = upkeepNode.node("enabled").getBoolean(DEFAULT_ENABLED);

        String intervalRaw = upkeepNode.node("interval").getString();
        Duration interval =
                intervalRaw != null && !intervalRaw.isBlank() ? Durations.parse(intervalRaw) : DEFAULT_INTERVAL;

        double baseFee = upkeepNode.node("base-fee").getDouble(500.00);
        long baseFeeMinor = Math.round(baseFee * 100.0);

        double perMemberFee = upkeepNode.node("per-member-fee").getDouble(100.00);
        long perMemberFeeMinor = Math.round(perMemberFee * 100.0);

        String graceRaw = upkeepNode.node("grace-duration").getString();
        Duration graceDuration =
                graceRaw != null && !graceRaw.isBlank() ? Durations.parse(graceRaw) : DEFAULT_GRACE_DURATION;

        boolean autoRemediate = upkeepNode.node("auto-remediate-on-deposit").getBoolean(DEFAULT_AUTO_REMEDIATE);

        IslandUpkeepPolicy policy = new IslandUpkeepPolicy(
                enabled, interval, baseFeeMinor, perMemberFeeMinor, graceDuration, autoRemediate);

        return new BankConfiguration(policy, operationRetention, currencies, money);
    }
}
