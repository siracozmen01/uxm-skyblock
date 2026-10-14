package com.uxplima.uxmskyblock.bukkit.bank;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.upgrade.UpgradeDefinition;
import com.uxplima.uxmskyblock.core.domain.upgrade.UpgradeTier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** SuperiorSkyblock2 limits what an island bank holds by an upgrade; this plugin's bank held without end. */
class ABankHoldsWhatItsLimitLetsItTest {

    private final IslandId island = IslandId.of(UUID.randomUUID());
    private final Map<IslandId, Integer> bought = new HashMap<>();

    private BankCap cap(Optional<UpgradeDefinition> definition) {
        return new BankCap(
                id -> id.equals(BankCap.BANK_LIMIT) ? definition : Optional.empty(),
                (id, upgrade) -> bought.getOrDefault(id, 0));
    }

    private static UpgradeDefinition limits(long firstCost, Map<String, Double> first, Map<String, Double> second) {
        return new UpgradeDefinition(
                BankCap.BANK_LIMIT,
                "Bank",
                List.of(new UpgradeTier(1, firstCost, "PRIMARY", first), new UpgradeTier(2, 10L, "PRIMARY", second)));
    }

    @Test
    @DisplayName("An island holds its tier's limit in money, starting on the free first tier")
    void theTierSaysHowMuch() {
        BankCap free = cap(Optional.of(limits(0L, Map.of("limit", 1_000.0), Map.of("limit", 5_000.5))));
        assertThat(free.of(island)).describedAs("nothing bought, the free tier").isEqualTo(100_000L);
        bought.put(island, 2);
        assertThat(free.of(island)).isEqualTo(500_050L);
    }

    @Test
    @DisplayName("No upgrade, no tier held, or a tier without a limit or with zero holds without end")
    void withoutEnd() {
        assertThat(cap(Optional.empty()).of(island)).isEqualTo(BankCap.UNLIMITED);
        assertThat(cap(Optional.of(limits(5L, Map.of("limit", 1_000.0), Map.of())))
                        .of(island))
                .describedAs("a paid first tier not bought")
                .isEqualTo(BankCap.UNLIMITED);
        bought.put(island, 2);
        assertThat(cap(Optional.of(limits(5L, Map.of("limit", 1_000.0), Map.of())))
                        .of(island))
                .isEqualTo(BankCap.UNLIMITED);
        assertThat(cap(Optional.of(limits(5L, Map.of("limit", 1_000.0), Map.of("limit", 0.0))))
                        .of(island))
                .isEqualTo(BankCap.UNLIMITED);
    }
}
