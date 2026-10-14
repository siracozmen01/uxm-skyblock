package com.uxplima.uxmskyblock.bukkit.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import com.uxplima.uxmskyblock.core.domain.limit.LimitQuota;
import com.uxplima.uxmskyblock.core.domain.upgrade.UpgradeId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

/**
 * The limits file named seven upgrades that no upgrades file shipped, so no island could buy one and no quota ever
 * rose above its base. Every upgrade a limit names is one an island can buy.
 */
class EveryLimitNamesAShippedUpgradeTest {

    @Test
    @DisplayName("Every upgrade a shipped limit names is an upgrade the shipped upgrades file holds")
    void theShippedFilesAgree() throws Exception {
        LimitConfiguration limits = LimitConfiguration.load(HoconConfigurationLoader.builder()
                .path(Path.of("src/main/resources/modules/limits.conf"))
                .build()
                .load());
        UpgradesConfiguration upgrades = UpgradesConfiguration.load(HoconConfigurationLoader.builder()
                .path(Path.of("src/main/resources/modules/upgrades.conf"))
                .build()
                .load());

        assertThat(named(limits)).hasSize(7).isSubsetOf(upgrades.definitions().keySet());
        assertThat(upgrades.getDefinition(UpgradeId.of("hopper_limit")))
                .map(com.uxplima.uxmskyblock.core.domain.upgrade.UpgradeDefinition::maxTier)
                .contains(3);
    }

    @Test
    @DisplayName("The built-in fallbacks agree too, so a server with no files can buy every limit upgrade")
    void theFallbacksAgree() {
        assertThat(named(LimitConfiguration.defaultConfiguration()))
                .isSubsetOf(UpgradesConfiguration.defaultConfiguration()
                        .definitions()
                        .keySet());
    }

    private static Set<UpgradeId> named(LimitConfiguration limits) {
        Set<UpgradeId> named = new TreeSet<>(java.util.Comparator.comparing(UpgradeId::key));
        limits.quotas().values().stream()
                .map(LimitQuota::upgradeId)
                .filter(Objects::nonNull)
                .forEach(named::add);
        return named;
    }
}
