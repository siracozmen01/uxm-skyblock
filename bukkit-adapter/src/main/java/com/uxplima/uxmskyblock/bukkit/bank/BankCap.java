package com.uxplima.uxmskyblock.bukkit.bank;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.ToIntBiFunction;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.upgrade.UpgradeDefinition;
import com.uxplima.uxmskyblock.core.domain.upgrade.UpgradeId;

/**
 * How much money an island bank may hold, by the tier of the bank limit upgrade the island stands on.
 *
 * <p>The tier's {@code limit} property is the most the bank holds, in money as a player writes it; a tier without
 * one, or with zero, holds without end. An island that bought nothing stands on the upgrade's free first tier, if it
 * has one, and on no limit if it has none.
 */
public final class BankCap {

    /** The upgrade whose tiers say how much an island bank holds. */
    public static final UpgradeId BANK_LIMIT = UpgradeId.of("bank_limit");

    /** What a bank with no limit holds. */
    public static final long UNLIMITED = Long.MAX_VALUE;

    private final Function<UpgradeId, Optional<UpgradeDefinition>> definitions;
    private final ToIntBiFunction<IslandId, UpgradeId> bought;

    public BankCap(
            Function<UpgradeId, Optional<UpgradeDefinition>> definitions, ToIntBiFunction<IslandId, UpgradeId> bought) {
        this.definitions = Objects.requireNonNull(definitions, "definitions must not be null");
        this.bought = Objects.requireNonNull(bought, "bought must not be null");
    }

    /** The most the island's bank may hold, in minor units, or {@link #UNLIMITED}. */
    public long of(IslandId island) {
        Optional<UpgradeDefinition> definition = definitions.apply(BANK_LIMIT);
        if (definition.isEmpty()) {
            return UNLIMITED;
        }
        UpgradeDefinition upgrade = definition.get();
        double limit = upgrade.getTier(upgrade.effectiveTier(bought.applyAsInt(island, BANK_LIMIT)))
                .map(tier -> tier.properties().getOrDefault("limit", 0.0))
                .orElse(0.0);
        return limit > 0 ? Math.round(limit * 100) : UNLIMITED;
    }
}
