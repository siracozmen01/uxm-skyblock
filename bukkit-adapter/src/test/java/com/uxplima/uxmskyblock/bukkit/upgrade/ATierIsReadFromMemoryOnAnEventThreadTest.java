package com.uxplima.uxmskyblock.bukkit.upgrade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import com.uxplima.uxmskyblock.core.application.upgrade.IslandUpgradeService;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.upgrade.UpgradeId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The fly and the island effects ask for a tier on a player's thread, where the database must never be waited on. */
class ATierIsReadFromMemoryOnAnEventThreadTest {

    private static final UpgradeId WINGS = UpgradeId.of("island_fly");

    @Test
    @DisplayName("An island nothing remembers is unknown and read off the thread; one remembered answers its tier")
    void fromMemoryOnly() {
        IslandUpgradeService upgrades = mock(IslandUpgradeService.class);
        IslandId island = IslandId.of(UUID.randomUUID());
        CachedTiers tiers = new CachedTiers(upgrades);

        assertThat(tiers.bought(island, WINGS)).isEqualTo(CachedTiers.UNKNOWN);
        verify(upgrades).refreshCacheAsync(island);
        verify(upgrades, never()).getCurrentTier(island, WINGS);

        when(upgrades.isCached(island)).thenReturn(true);
        when(upgrades.getCachedTier(island, WINGS)).thenReturn(2);
        assertThat(tiers.bought(island, WINGS)).isEqualTo(2);
    }
}
