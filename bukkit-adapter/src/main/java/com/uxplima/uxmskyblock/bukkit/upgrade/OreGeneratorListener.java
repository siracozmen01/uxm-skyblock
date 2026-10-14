package com.uxplima.uxmskyblock.bukkit.upgrade;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFormEvent;

import com.uxplima.uxmskyblock.bukkit.config.GeneratorsConfiguration;
import com.uxplima.uxmskyblock.bukkit.spatial.SpatialIslandIndex;
import com.uxplima.uxmskyblock.core.application.upgrade.IslandUpgradeService;
import com.uxplima.uxmskyblock.core.domain.booster.BoosterCategory;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.upgrade.UpgradeId;

/**
 * Event-driven cobblestone and ore generator listener.
 * Dynamically converts formed cobblestone and stone into upgraded ores based on the island's tier.
 */
public final class OreGeneratorListener implements Listener {

    private static final UpgradeId ORE_UPGRADE_ID = UpgradeId.of("ore_generator");

    private final IslandUpgradeService upgradeService;
    private final GeneratorsConfiguration configuration;
    private final SpatialIslandIndex spatialIndex;
    private volatile java.util.function.ToDoubleBiFunction<IslandId, BoosterCategory> boosters = (island, kind) -> 1.0;

    public OreGeneratorListener(
            IslandUpgradeService upgradeService,
            GeneratorsConfiguration configuration,
            SpatialIslandIndex spatialIndex) {
        this.upgradeService = Objects.requireNonNull(upgradeService, "upgradeService must not be null");
        this.configuration = Objects.requireNonNull(configuration, "configuration must not be null");
        this.spatialIndex = Objects.requireNonNull(spatialIndex, "spatialIndex must not be null");
    }

    /** Reads the ore generator booster an island has running, from memory, so ores turn up more often under one. */
    public void useBoosters(java.util.function.ToDoubleBiFunction<IslandId, BoosterCategory> boosters) {
        this.boosters = java.util.Objects.requireNonNull(boosters, "boosters must not be null");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockForm(BlockFormEvent event) {
        if (!configuration.enabled()) {
            return;
        }

        Material formedType = event.getNewState().getType();
        if (formedType != Material.COBBLESTONE && formedType != Material.STONE && formedType != Material.BASALT) {
            return;
        }

        Location loc = event.getBlock().getLocation();
        if (loc.getWorld() == null) {
            return;
        }

        Optional<Island> optIsland =
                spatialIndex.findIslandAt(loc.getWorld().getName(), loc.getBlockX(), loc.getBlockZ());
        if (optIsland.isEmpty()) {
            return;
        }

        IslandId islandId = optIsland.get().id();
        if (!upgradeService.isCached(islandId)) {
            upgradeService.refreshCacheAsync(islandId);
        }
        int tier = upgradeService.getCachedTier(islandId, ORE_UPGRADE_ID);
        if (tier <= 0) {
            return;
        }

        double roll = ThreadLocalRandom.current().nextDouble();
        Material oreMaterial = configuration.roll(
                tier,
                loc.getWorld().getEnvironment(),
                roll,
                boosters.applyAsDouble(islandId, BoosterCategory.ORE_GENERATOR));
        if (oreMaterial != formedType) {
            event.getNewState().setType(oreMaterial);
        }
    }
}
