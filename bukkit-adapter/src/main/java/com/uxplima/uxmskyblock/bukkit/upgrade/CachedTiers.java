package com.uxplima.uxmskyblock.bukkit.upgrade;

import java.util.Objects;

import com.uxplima.uxmskyblock.core.application.upgrade.IslandUpgradeService;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.upgrade.UpgradeId;

/**
 * The tiers an island has bought, as the upgrade service remembers them, for code on a region or player thread.
 *
 * <p>The service's own lookup reads the database when it remembers nothing, and an event thread must never wait on
 * that. This answers from memory alone: an island it does not remember is read off the thread, and until the
 * answer is in its tier is unknown, which each caller treats in the way that suits it.
 */
public final class CachedTiers {

    /** The tier of an island nothing remembers yet. */
    public static final int UNKNOWN = -1;

    private final IslandUpgradeService upgrades;

    public CachedTiers(IslandUpgradeService upgrades) {
        this.upgrades = Objects.requireNonNull(upgrades, "upgrades must not be null");
    }

    /** The tiers of {@code upgrade} the island bought, or {@link #UNKNOWN} while it is read. */
    public int bought(IslandId island, UpgradeId upgrade) {
        if (!upgrades.isCached(island)) {
            upgrades.refreshCacheAsync(island);
            return UNKNOWN;
        }
        return upgrades.getCachedTier(island, upgrade);
    }
}
