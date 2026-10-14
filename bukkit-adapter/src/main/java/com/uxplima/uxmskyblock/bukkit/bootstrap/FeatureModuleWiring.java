package com.uxplima.uxmskyblock.bukkit.bootstrap;

import java.util.Objects;

import com.uxplima.uxmskyblock.bukkit.bank.BankInterestBeat;
import com.uxplima.uxmskyblock.bukkit.integration.economy.SkyblockEconomyBridge;
import com.uxplima.uxmskyblock.bukkit.module.BukkitModuleContext;
import com.uxplima.uxmskyblock.bukkit.module.builtin.AllianceFeatureModule;
import com.uxplima.uxmskyblock.bukkit.module.builtin.AntiAbuseFeatureModule;
import com.uxplima.uxmskyblock.bukkit.module.builtin.BankModule;
import com.uxplima.uxmskyblock.bukkit.module.builtin.BankUpkeepFeatureModule;
import com.uxplima.uxmskyblock.bukkit.module.builtin.BiomesModule;
import com.uxplima.uxmskyblock.bukkit.module.builtin.BoosterFeatureModule;
import com.uxplima.uxmskyblock.bukkit.module.builtin.BoundaryFeatureModule;
import com.uxplima.uxmskyblock.bukkit.module.builtin.ChatFeatureModule;
import com.uxplima.uxmskyblock.bukkit.module.builtin.CoreModule;
import com.uxplima.uxmskyblock.bukkit.module.builtin.DimensionFeatureModule;
import com.uxplima.uxmskyblock.bukkit.module.builtin.DiscordFeatureModule;
import com.uxplima.uxmskyblock.bukkit.module.builtin.FreezeFeatureModule;
import com.uxplima.uxmskyblock.bukkit.module.builtin.InactivityFeatureModule;
import com.uxplima.uxmskyblock.bukkit.module.builtin.LeaderboardFeatureModule;
import com.uxplima.uxmskyblock.bukkit.module.builtin.LimitFeatureModule;
import com.uxplima.uxmskyblock.bukkit.module.builtin.MissionFeatureModule;
import com.uxplima.uxmskyblock.bukkit.module.builtin.PresetsModule;
import com.uxplima.uxmskyblock.bukkit.module.builtin.RecycleFeatureModule;
import com.uxplima.uxmskyblock.bukkit.module.builtin.RewardInboxFeatureModule;
import com.uxplima.uxmskyblock.bukkit.module.builtin.SeasonFeatureModule;
import com.uxplima.uxmskyblock.bukkit.module.builtin.ShopFeatureModule;
import com.uxplima.uxmskyblock.bukkit.module.builtin.SocialFeatureModule;
import com.uxplima.uxmskyblock.bukkit.module.builtin.TemporaryAccessFeatureModule;
import com.uxplima.uxmskyblock.bukkit.module.builtin.VaultFeatureModule;
import com.uxplima.uxmskyblock.bukkit.module.builtin.WarpFeatureModule;
import com.uxplima.uxmskyblock.bukkit.module.builtin.WorthFeatureModule;
import com.uxplima.uxmskyblock.core.application.module.ModuleRegistry;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import com.uxplima.uxmskyblock.persistence.bootstrap.PersistenceBootstrap;
import org.jspecify.annotations.Nullable;

/**
 * Manages modular feature subsystem registration, configuration toggles,
 * and lifecycle enablement / disablement.
 */
public final class FeatureModuleWiring implements AutoCloseable {

    private final ModuleRegistry moduleRegistry;
    private final BukkitModuleContext moduleContext;
    private final @Nullable BankUpkeepFeatureModule bankUpkeepFeatureModule;
    private final @Nullable BankInterestBeat bankInterest;
    private final com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort scheduler;

    public FeatureModuleWiring(
            ConfigurationWiring config,
            PersistenceBootstrap persistence,
            GameplayWiring gameplay,
            IntegrationWiring integration) {
        Objects.requireNonNull(config, "config must not be null");
        Objects.requireNonNull(persistence, "persistence must not be null");
        Objects.requireNonNull(gameplay, "gameplay must not be null");
        Objects.requireNonNull(integration, "integration must not be null");

        ServerNodeId serverNodeId = config.nodeConfig().nodeId();

        this.moduleRegistry = new ModuleRegistry();
        this.moduleContext = new BukkitModuleContext("1.0.0");

        if (config.moduleSettings().isModuleEnabled("bank-upkeep")) {
            this.bankUpkeepFeatureModule = new BankUpkeepFeatureModule(
                    gameplay.bankruptcyService(),
                    config.bankConfig(),
                    gameplay.scheduler(),
                    persistence.islandStoragePort(),
                    config.islandWorlds(),
                    serverNodeId);
            // Interest on the money an island bank holds, paid to an island one of whose members played lately.
            var cap = new com.uxplima.uxmskyblock.bukkit.bank.BankCap(
                    gameplay.upgradeService()::getDefinition, gameplay.upgradeService()::getCurrentTier);
            this.bankInterest = new BankInterestBeat(
                    new com.uxplima.uxmskyblock.core.application.bank.IslandBankInterest(
                            persistence.islandBankPort(),
                            gameplay.bankService(),
                            new com.uxplima.uxmskyblock.bukkit.inactivity.BukkitPlayerActivityProvider(),
                            cap::of),
                    () -> config.bankPayoutsConfig().interest(),
                    () -> config.islandWorlds().stream()
                            .flatMap(world -> persistence.islandStoragePort().findAllByWorld(world).stream())
                            .toList(),
                    serverNodeId);
            // A deleted island pays its owner back the share of its bank money the operator set, zero by default.
            SkyblockEconomyBridge wallets = integration.economyBridge();
            gameplay.recycleService()
                    .useBeforeDelete(new com.uxplima.uxmskyblock.bukkit.bank.DeleteRefund(
                            persistence.islandBankPort(),
                            gameplay.bankService(),
                            () -> config.bankPayoutsConfig().deleteRefundShare(),
                            (owner, minorUnits) -> wallets.depositWallet(
                                    org.bukkit.Bukkit.getOfflinePlayer(owner.value()), minorUnits / 100.0),
                            serverNodeId));
        } else {
            this.bankUpkeepFeatureModule = null;
            this.bankInterest = null;
        }
        this.scheduler = gameplay.scheduler();

        if (config.moduleSettings().isModuleEnabled("core")) {
            this.moduleRegistry.register(new CoreModule(gameplay.createIslandUseCase()));
        }
        if (config.moduleSettings().isModuleEnabled("bank")) {
            this.moduleRegistry.register(new BankModule(
                    gameplay.bankService(),
                    persistence.islandBankPort(),
                    gameplay.scheduler(),
                    config.bankConfig().operationRetention()));
        }
        if (config.moduleSettings().isModuleEnabled("upgrades")) {
            this.moduleRegistry.register(gameplay.upgradesModule());
        }
        if (config.moduleSettings().isModuleEnabled("biomes")) {
            this.moduleRegistry.register(new BiomesModule(gameplay.biomeAdapter()));
        }
        if (config.moduleSettings().isModuleEnabled("presets")) {
            this.moduleRegistry.register(new PresetsModule(gameplay.presetCatalog(), gameplay.schematicEngine()));
        }
        if (config.moduleSettings().isModuleEnabled("seasons")) {
            this.moduleRegistry.register(
                    new SeasonFeatureModule(gameplay.seasonService(), gameplay.scheduler(), config.seasonConfig()));
        }
        if (config.moduleSettings().isModuleEnabled("social")) {
            this.moduleRegistry.register(new SocialFeatureModule(gameplay.socialService()));
        }
        if (config.moduleSettings().isModuleEnabled("discord")) {
            this.moduleRegistry.register(new DiscordFeatureModule(integration.discordService()));
        }
        if (config.moduleSettings().isModuleEnabled("alliances")) {
            this.moduleRegistry.register(new AllianceFeatureModule(
                    gameplay.allianceService(),
                    gameplay.scheduler(),
                    config.allianceConfig().expiredInviteSweepInterval()));
        }
        if (config.moduleSettings().isModuleEnabled("leaderboards")) {
            // Nothing built a board except the player who asked for one, so the first ask after a
            // restart paid for a sort across every island while that player waited.
            this.moduleRegistry.register(new LeaderboardFeatureModule(
                    gameplay.leaderboardService(),
                    gameplay.scheduler(),
                    config.levelConfig().leaderboardRebuildInterval(),
                    integration.discordService()));
        }
        if (config.moduleSettings().isModuleEnabled("shop")) {
            this.moduleRegistry.register(new ShopFeatureModule(gameplay.dynamicPricingEngine()));
        }
        if (config.moduleSettings().isModuleEnabled("temporary-access")) {
            this.moduleRegistry.register(new TemporaryAccessFeatureModule(
                    gameplay.temporaryAccessService(), gameplay.scheduler(), config.temporaryAccessConfig()));
        }
        // The name the operator's file publishes, which is the name that has to be asked for.
        // This asked for "reward-inbox" and the file says "rewards", and an unknown module is
        // treated as on, so an operator who turned the reward inbox off still had it.
        if (config.moduleSettings().isModuleEnabled("rewards")) {
            this.moduleRegistry.register(new RewardInboxFeatureModule(
                    gameplay.rewardInboxService(), gameplay.scheduler(), config.rewardConfig()));
        }
        if (config.moduleSettings().isModuleEnabled("warps")) {
            this.moduleRegistry.register(
                    new WarpFeatureModule(gameplay.warpService(), gameplay.safeTeleportEngine(), config.warpConfig()));
        }
        if (config.moduleSettings().isModuleEnabled("vault")) {
            this.moduleRegistry.register(
                    new VaultFeatureModule(gameplay.vaultService(), config.vaultConfig(), gameplay.scheduler()));
        }
        if (config.moduleSettings().isModuleEnabled("chat")) {
            this.moduleRegistry.register(new ChatFeatureModule(gameplay.chatService(), config.chatConfig()));
        }
        if (config.moduleSettings().isModuleEnabled("inactivity")) {
            this.moduleRegistry.register(new InactivityFeatureModule(
                    gameplay.inactivityService(),
                    gameplay.scheduler(),
                    config.inactivityConfig(),
                    config.islandWorlds()));
        }
        // Likewise: this asked for "freeze" and the file says "admin-freeze".
        if (config.moduleSettings().isModuleEnabled("admin-freeze")) {
            this.moduleRegistry.register(new FreezeFeatureModule(gameplay.freezeService()));
        }
        if (config.moduleSettings().isModuleEnabled("missions")) {
            this.moduleRegistry.register(
                    new MissionFeatureModule(gameplay.missionService(), config.missionConfig(), gameplay.scheduler()));
        }
        if (config.moduleSettings().isModuleEnabled("boundary") && gameplay.boundaryListener() != null) {
            this.moduleRegistry.register(new BoundaryFeatureModule(
                    gameplay.boundaryService(), gameplay.boundaryListener(), gameplay.scheduler()));
        }
        if (config.moduleSettings().isModuleEnabled("recycle")) {
            this.moduleRegistry.register(new RecycleFeatureModule(gameplay.recycleService()));
        }
        if (config.moduleSettings().isModuleEnabled("worth")) {
            this.moduleRegistry.register(new WorthFeatureModule(gameplay.worthService()));
        }
        if (config.moduleSettings().isModuleEnabled("dimensions")) {
            this.moduleRegistry.register(new DimensionFeatureModule(gameplay.dimensionService()));
        }
        if (config.moduleSettings().isModuleEnabled("limits")) {
            this.moduleRegistry.register(new LimitFeatureModule(gameplay.limitService()));
        }
        if (config.moduleSettings().isModuleEnabled("anti-abuse")) {
            this.moduleRegistry.register(new AntiAbuseFeatureModule(gameplay.antiAbuseService()));
        }
        if (config.moduleSettings().isModuleEnabled("boosters")) {
            this.moduleRegistry.register(
                    new BoosterFeatureModule(gameplay.boosterService(), config.boosterConfig(), gameplay.scheduler()));
        }
        if (this.bankUpkeepFeatureModule != null) {
            this.moduleRegistry.register(this.bankUpkeepFeatureModule);
        }

        this.moduleRegistry.configure(
                config.moduleSettings().moduleToggles(), config.moduleSettings().selectedProviders());
    }

    public void enable() {
        moduleRegistry.enableModules(moduleContext);
        if (bankInterest != null) {
            bankInterest.start(scheduler);
        }
    }

    public ModuleRegistry moduleRegistry() {
        return moduleRegistry;
    }

    public BukkitModuleContext moduleContext() {
        return moduleContext;
    }

    public @Nullable BankUpkeepFeatureModule bankUpkeepFeatureModule() {
        return bankUpkeepFeatureModule;
    }

    @Override
    public void close() {
        if (bankInterest != null) {
            bankInterest.close();
        }
        moduleRegistry.disableModules();
    }
}
