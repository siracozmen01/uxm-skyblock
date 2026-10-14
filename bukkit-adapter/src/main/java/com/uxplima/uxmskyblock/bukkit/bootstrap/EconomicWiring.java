package com.uxplima.uxmskyblock.bukkit.bootstrap;

import java.util.List;
import java.util.function.Supplier;

import org.bukkit.plugin.java.JavaPlugin;

import com.uxplima.uxmskyblock.bukkit.bank.IslandBankruptcyListener;
import com.uxplima.uxmskyblock.bukkit.booster.IslandBoosterListener;
import com.uxplima.uxmskyblock.bukkit.integration.economy.SkyblockEconomyBridge;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.menu.IslandBoosterMenu;
import com.uxplima.uxmskyblock.bukkit.menu.IslandShopMenu;
import com.uxplima.uxmskyblock.bukkit.module.builtin.UpgradesModule;
import com.uxplima.uxmskyblock.bukkit.reward.CosmeticRewardDeliveryHandler;
import com.uxplima.uxmskyblock.bukkit.reward.ExternalVaultRewardDeliveryHandler;
import com.uxplima.uxmskyblock.bukkit.reward.ItemRewardDeliveryHandler;
import com.uxplima.uxmskyblock.bukkit.reward.PermissionRewardDeliveryHandler;
import com.uxplima.uxmskyblock.bukkit.reward.SqlCurrencyRewardDeliveryHandler;
import com.uxplima.uxmskyblock.bukkit.upgrade.OreGeneratorListener;
import com.uxplima.uxmskyblock.bukkit.worth.FoliaIslandChunkScanner;
import com.uxplima.uxmskyblock.bukkit.worth.IslandWorthListener;
import com.uxplima.uxmskyblock.core.application.bank.IslandBankService;
import com.uxplima.uxmskyblock.core.application.bank.IslandBankruptcyService;
import com.uxplima.uxmskyblock.core.application.booster.IslandBoosterService;
import com.uxplima.uxmskyblock.core.application.reward.RewardClaimCoordinator;
import com.uxplima.uxmskyblock.core.application.reward.RewardInboxService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.shop.DynamicPricingEngine;
import com.uxplima.uxmskyblock.core.application.shop.IslandShopService;
import com.uxplima.uxmskyblock.core.application.upgrade.IslandUpgradeService;
import com.uxplima.uxmskyblock.core.application.worth.IslandWorthService;
import com.uxplima.uxmskyblock.core.domain.level.MaterialValuationIndex;
import com.uxplima.uxmskyblock.persistence.bootstrap.PersistenceBootstrap;
import org.jspecify.annotations.Nullable;

/**
 * Encapsulates island bank, bankruptcy, worth calculation, upgrades, boosters,
 * dynamic pricing, and reward delivery pipelines.
 */
public final class EconomicWiring {

    private final IslandBankService bankService;
    private final DynamicPricingEngine dynamicPricingEngine;
    private final IslandShopService shopService;
    private final @Nullable IslandShopMenu shopMenu;
    private final IslandBankruptcyService bankruptcyService;
    private final @Nullable IslandBankruptcyListener bankruptcyListener;
    private final @Nullable FoliaIslandChunkScanner chunkScanner;
    private final IslandWorthService worthService;
    private final @Nullable IslandWorthListener worthListener;
    private final IslandBoosterService boosterService;
    private final @Nullable IslandBoosterListener boosterListener;
    private final @Nullable IslandBoosterMenu boosterMenu;
    private final RewardInboxService rewardInboxService;
    private final IslandUpgradeService upgradeService;
    private final @Nullable OreGeneratorListener oreGeneratorListener;
    private final UpgradesModule upgradesModule;

    public EconomicWiring(
            JavaPlugin plugin,
            ConfigurationWiring config,
            PersistenceBootstrap persistence,
            AuthorityWiring authority,
            IslandProtectionListener protectionListener,
            SchedulerPort scheduler,
            Supplier<SkyblockEconomyBridge> economyBridgeSupplier) {
        this.bankService = new IslandBankService(
                persistence.islandBankPort(),
                persistence.islandStoragePort(),
                persistence.islandAuthorityPort(),
                persistence.outboxPort());
        this.dynamicPricingEngine = new DynamicPricingEngine(config.shopConfig().dampingFactor());
        // The engine had a damping factor, an elasticity and a stock baseline, and nothing ever put
        // an item in it: every price it could be asked for was absent. What it trades is the
        // operator's file, read here, once.
        config.shopConfig().items().forEach(this.dynamicPricingEngine::registerItem);
        this.shopService = new IslandShopService(this.dynamicPricingEngine, this.bankService);
        this.shopMenu = config.moduleSettings().isModuleEnabled("shop")
                ? new IslandShopMenu(
                        this.shopService,
                        persistence.islandStoragePort(),
                        authority.sessionCoordinator(),
                        scheduler,
                        config.nodeConfig().nodeId(),
                        config.messages())
                : null;
        this.upgradeService = new IslandUpgradeService(
                persistence.islandUpgradeStoragePort(),
                config.upgradesConfig().definitions(),
                persistence.islandBankPort(),
                persistence.islandAuthorityPort());

        this.bankruptcyService = new IslandBankruptcyService(
                persistence.islandBankruptcyStoragePort(),
                persistence.islandBankPort(),
                persistence.islandAuthorityPort(),
                config.bankConfig()::upkeepPolicy,
                (int) config.nodeConfig().authorityLease().toSeconds());
        this.bankruptcyListener = config.moduleSettings().isModuleEnabled("bank-upkeep")
                ? new IslandBankruptcyListener(
                        this.bankruptcyService,
                        protectionListener,
                        persistence.islandStoragePort(),
                        authority.sessionCoordinator(),
                        scheduler,
                        config.messages())
                : null;
        if (this.bankruptcyListener != null) {
            authority.sessionCoordinator().whenSessionActive(this.bankruptcyListener::onSessionActive);
        }

        boolean worthEnabled = config.moduleSettings().isModuleEnabled("worth");
        this.chunkScanner = worthEnabled
                ? new FoliaIslandChunkScanner(
                        scheduler,
                        config.levelConfig().blockWeights().keySet(),
                        config.levelConfig().spawnerWeights().keySet())
                : null;
        MaterialValuationIndex valuationIndex = new MaterialValuationIndex();
        config.levelConfig().blockWeights().forEach(valuationIndex::setWeight);
        config.levelConfig().basePricesMinorUnits().forEach(valuationIndex::setPrice);
        this.worthService = new IslandWorthService(
                valuationIndex,
                config.levelConfig().spawnerWeights(),
                config.levelConfig().defaultSpawnerWeight(),
                config.levelConfig().questWeight(),
                config.levelConfig().pointsPerLevel(),
                config.levelConfig().bankMinorUnitsPerPoint(),
                config.levelConfig().dampingFactor(),
                persistence.islandLeaderboardPort(),
                this.chunkScanner);
        this.worthListener = worthEnabled ? new IslandWorthListener(this.worthService, protectionListener) : null;

        boolean boostersEnabled = config.moduleSettings().isModuleEnabled("boosters");
        this.boosterService = new IslandBoosterService(
                persistence.islandBoosterStoragePort(),
                config.boosterConfig()::policy,
                config.boosterConfig().pauseWhenEmpty());
        this.boosterListener = boostersEnabled
                ? new IslandBoosterListener(
                        persistence.islandStoragePort(),
                        this.boosterService,
                        config.boosterConfig(),
                        authority.sessionCoordinator(),
                        scheduler)
                : null;
        if (this.boosterListener != null) {
            authority.sessionCoordinator().whenSessionActive(this.boosterListener::onSessionActive);
            authority.sessionCoordinator().whenProfileLeft(this.boosterListener::onProfileLeft);
        }
        this.boosterMenu = boostersEnabled
                ? new IslandBoosterMenu(
                        persistence.islandStoragePort(),
                        this.boosterService,
                        config.boosterConfig(),
                        authority.sessionCoordinator(),
                        scheduler,
                        config.messages())
                : null;

        ItemRewardDeliveryHandler itemDeliveryHandler = new ItemRewardDeliveryHandler(
                authority.sessionCoordinator(),
                persistence.mutationJournalPort(),
                config.nodeConfig().nodeId(),
                scheduler);

        SqlCurrencyRewardDeliveryHandler currencyDeliveryHandler = new SqlCurrencyRewardDeliveryHandler(
                persistence.islandStoragePort(),
                this.bankService,
                config.nodeConfig().nodeId(),
                persistence.profileSwitchPort());

        ExternalVaultRewardDeliveryHandler vaultDeliveryHandler = new ExternalVaultRewardDeliveryHandler(
                economyBridgeSupplier,
                persistence.economySagaPort(),
                persistence.profileSwitchPort(),
                persistence.islandStoragePort());

        CosmeticRewardDeliveryHandler cosmeticDeliveryHandler =
                new CosmeticRewardDeliveryHandler(persistence.profileCosmeticStoragePort());

        PermissionRewardDeliveryHandler permDeliveryHandler = new PermissionRewardDeliveryHandler(
                PermissionRewardDeliveryHandler::fromVault,
                persistence.profileSwitchPort(),
                authority.sessionCoordinator());

        RewardClaimCoordinator rewardClaimCoordinator = new RewardClaimCoordinator(
                persistence.rewardStoragePort(),
                List.of(
                        itemDeliveryHandler,
                        currencyDeliveryHandler,
                        vaultDeliveryHandler,
                        cosmeticDeliveryHandler,
                        permDeliveryHandler),
                config.rewardConfig().claimRecoveryWindow());

        this.rewardInboxService = new RewardInboxService(persistence.rewardStoragePort(), rewardClaimCoordinator);
        if (this.shopMenu != null) {
            this.shopMenu.keepUnreturnedIn(this.rewardInboxService);
        }

        boolean upgradesEnabled = config.moduleSettings().isModuleEnabled("upgrades");
        this.oreGeneratorListener = upgradesEnabled
                ? new OreGeneratorListener(
                        this.upgradeService, config.generatorsConfig(), protectionListener.spatialIndex())
                : null;
        if (this.oreGeneratorListener != null && this.boosterListener != null) {
            this.oreGeneratorListener.useBoosters(this.boosterListener::cachedMultiplier);
        }
        if (boostersEnabled) {
            // Worked out off the event threads, so the booster is read where it is kept.
            IslandBoosterService boosters = this.boosterService;
            this.worthService.useWorthBoost(island -> boosters.getEffectiveMultiplier(
                    island,
                    com.uxplima.uxmskyblock.core.domain.booster.BoosterCategory.ISLAND_WORTH,
                    java.time.Instant.now()));
        }
        this.upgradesModule = new UpgradesModule(
                this.upgradeService,
                config.upgradesConfig(),
                config.generatorsConfig(),
                this.oreGeneratorListener,
                plugin);
    }

    public IslandBankService bankService() {
        return bankService;
    }

    /** The shop as a window, when the operator left the module on. */
    public @Nullable IslandShopMenu shopMenu() {
        return shopMenu;
    }

    /** Buying and selling, settled against the island bank. */
    public IslandShopService shopService() {
        return shopService;
    }

    public DynamicPricingEngine dynamicPricingEngine() {
        return dynamicPricingEngine;
    }

    public IslandBankruptcyService bankruptcyService() {
        return bankruptcyService;
    }

    public @Nullable IslandBankruptcyListener bankruptcyListener() {
        return bankruptcyListener;
    }

    public @Nullable FoliaIslandChunkScanner chunkScanner() {
        return chunkScanner;
    }

    public IslandWorthService worthService() {
        return worthService;
    }

    public @Nullable IslandWorthListener worthListener() {
        return worthListener;
    }

    public IslandBoosterService boosterService() {
        return boosterService;
    }

    public @Nullable IslandBoosterListener boosterListener() {
        return boosterListener;
    }

    public @Nullable IslandBoosterMenu boosterMenu() {
        return boosterMenu;
    }

    public RewardInboxService rewardInboxService() {
        return rewardInboxService;
    }

    public IslandUpgradeService upgradeService() {
        return upgradeService;
    }

    public @Nullable OreGeneratorListener oreGeneratorListener() {
        return oreGeneratorListener;
    }

    public UpgradesModule upgradesModule() {
        return upgradesModule;
    }
}
