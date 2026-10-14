package com.uxplima.uxmskyblock.bukkit.bootstrap;

import java.util.Objects;
import java.util.function.Supplier;

import org.bukkit.plugin.java.JavaPlugin;

import com.uxplima.uxmskyblock.bukkit.dimension.IslandDimensionListener;
import com.uxplima.uxmskyblock.bukkit.freeze.BukkitIslandVisitorEvictionAdapter;
import com.uxplima.uxmskyblock.bukkit.integration.economy.SkyblockEconomyBridge;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.core.application.access.TemporaryAccessService;
import com.uxplima.uxmskyblock.core.application.alliance.IslandAllianceService;
import com.uxplima.uxmskyblock.core.application.chat.IslandChatTransportPort;
import com.uxplima.uxmskyblock.core.application.freeze.IslandAdminFreezeService;
import com.uxplima.uxmskyblock.core.application.island.IslandAccessService;
import com.uxplima.uxmskyblock.core.application.island.IslandCacheEviction;
import com.uxplima.uxmskyblock.core.application.membership.IslandMembershipService;
import com.uxplima.uxmskyblock.core.application.membership.MemberAllowance;
import com.uxplima.uxmskyblock.core.application.performance.AdaptiveBackpressureController;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.storage.ObjectStoragePort;
import com.uxplima.uxmskyblock.core.domain.storage.StorageBucket;
import com.uxplima.uxmskyblock.persistence.bootstrap.PersistenceBootstrap;
import com.uxplima.uxmskyblock.persistence.storage.LocalFilesystemStorageAdapter;

/**
 * Composition root for gameplay subsystems: island creation, economy, social, admin, protection, and environment.
 */
public final class GameplayWiring extends GameplayParts {

    private final SchedulerPort scheduler;
    private final AdaptiveBackpressureController backpressureController;
    private final IslandAccessService accessService;
    private final BukkitIslandVisitorEvictionAdapter visitorEvictionAdapter;
    private final IslandProtectionListener protectionListener;

    private final GameplayCreationWiring creationWiring;
    private final AdminWiring adminWiring;
    private final StorageBucket backupBucket;
    private final IslandCacheEviction cacheEviction;
    private final OneBlockWiring oneBlockWiring;
    private final ChunkBlockWiring chunkBlockWiring;
    private final AcidIslandWiring acidIslandWiring;
    private final BoxedWiring boxedWiring;
    /** CaveBlock's rock and SkyGrid's grid, and the structure schematics both lay. */
    private final CaveAndGridWiring caveAndGrid;

    private final PoseidonWiring poseidonWiring;
    private final StrangerRealmsWiring strangerRealmsWiring;
    private final ParkourWiring parkourWiring;
    private final BrixWiring brixWiring;
    private final TradeWiring tradeWiring;
    private final TradeWindsWiring tradeWindsWiring;
    private final PlayerRulesWiring playerRules;
    private final IslandEdgeWiring edgeWiring;
    private final IslandMembershipService membershipService;
    private final EconomicWiring economicWiring;
    private final SocialWiring socialWiring;
    private final com.uxplima.uxmskyblock.bukkit.listener.IslandVisitRecorder visitRecorder;
    private final GameplayProtectionWiring protectionWiring;
    private final GameplayEnvironmentWiring environmentWiring;

    public GameplayWiring(
            JavaPlugin plugin,
            ConfigurationWiring config,
            PersistenceBootstrap persistence,
            AuthorityWiring authority,
            IslandProtectionListener protectionListener,
            IslandAccessService accessService,
            IslandAllianceService allianceService,
            TemporaryAccessService temporaryAccessService,
            BukkitIslandVisitorEvictionAdapter visitorEvictionAdapter,
            IslandAdminFreezeService freezeService,
            SchedulerPort scheduler,
            AdaptiveBackpressureController backpressureController,
            Supplier<SkyblockEconomyBridge> economyBridgeSupplier,
            IslandChatTransportPort chatTransport) {
        this(
                plugin,
                config,
                persistence,
                authority,
                protectionListener,
                accessService,
                allianceService,
                temporaryAccessService,
                visitorEvictionAdapter,
                freezeService,
                scheduler,
                backpressureController,
                economyBridgeSupplier,
                chatTransport,
                new LocalFilesystemStorageAdapter(
                        plugin.getDataFolder().toPath().resolve("backups")),
                new StorageBucket(PersistenceWiring.DEFAULT_BACKUP_BUCKET));
    }

    public GameplayWiring(
            JavaPlugin plugin,
            ConfigurationWiring config,
            PersistenceBootstrap persistence,
            AuthorityWiring authority,
            IslandProtectionListener protectionListener,
            IslandAccessService accessService,
            IslandAllianceService allianceService,
            TemporaryAccessService temporaryAccessService,
            BukkitIslandVisitorEvictionAdapter visitorEvictionAdapter,
            IslandAdminFreezeService freezeService,
            SchedulerPort scheduler,
            AdaptiveBackpressureController backpressureController,
            Supplier<SkyblockEconomyBridge> economyBridgeSupplier,
            IslandChatTransportPort chatTransport,
            ObjectStoragePort objectStorage,
            StorageBucket backupBucket) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.backpressureController =
                Objects.requireNonNull(backpressureController, "backpressureController must not be null");
        this.accessService = Objects.requireNonNull(accessService, "accessService must not be null");
        this.visitorEvictionAdapter =
                Objects.requireNonNull(visitorEvictionAdapter, "visitorEvictionAdapter must not be null");
        this.protectionListener = Objects.requireNonNull(protectionListener, "protectionListener must not be null");

        String worldName = config.nodeConfig().worldName();

        this.economicWiring = new EconomicWiring(
                plugin, config, persistence, authority, protectionListener, scheduler, economyBridgeSupplier);

        // Before creation, because a game mode brings the actions that build its islands.
        this.oneBlockWiring = new OneBlockWiring(
                config, persistence, scheduler, protectionListener, authority.sessionCoordinator()::activeProfile);
        this.chunkBlockWiring = new ChunkBlockWiring(
                config, persistence, scheduler, protectionListener, authority.sessionCoordinator()::activeProfile);
        this.acidIslandWiring = new AcidIslandWiring(config, persistence, scheduler, protectionListener);
        this.boxedWiring = new BoxedWiring(
                config, persistence, scheduler, protectionListener, authority.sessionCoordinator()::activeProfile);
        this.poseidonWiring = new PoseidonWiring(config, persistence, scheduler, protectionListener, plugin);
        this.strangerRealmsWiring = new StrangerRealmsWiring(config, persistence, scheduler, protectionListener);
        // A creative place seals a player only once their session has put their own state on them.
        java.util.function.Predicate<org.bukkit.entity.Player> inPlay =
                player -> authority.sessionCoordinator().inPlay(player.getUniqueId());
        this.parkourWiring = new ParkourWiring(
                config,
                persistence,
                scheduler,
                protectionListener,
                inPlay,
                new com.uxplima.uxmskyblock.bukkit.parkour.ServerGhostStage(
                        plugin, config.parkourConfig().ghost()));
        this.brixWiring = new BrixWiring(config, persistence, scheduler, protectionListener, inPlay);
        this.tradeWiring = new TradeWiring(config, persistence, authority, scheduler);
        this.tradeWindsWiring = new TradeWindsWiring(
                config, persistence, authority, scheduler, protectionListener, economicWiring.bankService());
        this.caveAndGrid = new CaveAndGridWiring(config, scheduler);
        this.creationWiring = new GameplayCreationWiring(
                config,
                persistence,
                authority,
                scheduler,
                backpressureController,
                accessService,
                this.economicWiring.rewardInboxService(),
                this.economicWiring.upgradeService(),
                islandSchematics(plugin, config, scheduler),
                modeStartActions());
        if (this.tradeWindsWiring.enabled()) {
            // The vessels that traded most, on the boards and to every plugin that reads them.
            creationWiring
                    .leaderboardMetrics()
                    .register(new com.uxplima.uxmskyblock.core.application.tradewinds.TradeVolumeMetric(
                            persistence.marketOrdersPort()));
        }
        if (this.parkourWiring.enabled()) {
            // The courses run most, on the boards and to every plugin that reads them.
            creationWiring
                    .leaderboardMetrics()
                    .register(new com.uxplima.uxmskyblock.core.application.parkour.ParkourRunsMetric(
                            this.parkourWiring.service()));
        }
        GameplayLinks.modeBoards(
                creationWiring.leaderboardMetrics(),
                persistence.modeStandingsPort(),
                oneBlockWiring,
                parkourWiring,
                boxedWiring,
                chunkBlockWiring);

        // What happens to players: the lifecycle rules, where they arrive, flight, the rates and effects an island pays
        // for, and a finished mission's reward.
        this.playerRules = new PlayerRulesWiring(
                config,
                persistence,
                authority,
                scheduler,
                protectionListener,
                this.economicWiring,
                this.creationWiring);

        this.socialWiring = new SocialWiring(
                config,
                persistence,
                authority,
                allianceService,
                temporaryAccessService,
                this.economicWiring.upgradeService(),
                chatTransport,
                scheduler);
        this.visitRecorder = new com.uxplima.uxmskyblock.bukkit.listener.IslandVisitRecorder(
                protectionListener::findIslandAt,
                authority.sessionCoordinator()::activeProfile,
                this.socialWiring.socialService(),
                scheduler,
                java.time.Clock.systemUTC());

        this.backupBucket = java.util.Objects.requireNonNull(backupBucket, "backupBucket must not be null");
        // One place that forgets an island. Each service says once how to forget one, and erasing
        // an island says it happened.
        this.cacheEviction = new IslandCacheEviction();
        this.cacheEviction.whenForgotten(this.economicWiring.upgradeService()::invalidateCache);

        this.adminWiring = new AdminWiring(
                plugin,
                config,
                persistence,
                authority,
                protectionListener,
                visitorEvictionAdapter,
                freezeService,
                scheduler,
                this.backpressureController,
                this.cacheEviction,
                objectStorage);

        // A skyblock with no way to make a team. The cap is the member limit upgrade's own tier
        // property, so an operator raises it by editing upgrades.conf rather than by asking for a
        // release.
        this.membershipService = new IslandMembershipService(
                persistence.islandStoragePort(),
                persistence.islandMutationLock(),
                new MemberAllowance(this.economicWiring.upgradeService()));
        // Joining an island and making one hold the same lock per profile, so nobody does both.
        this.membershipService.shareProfileLock(
                creationWiring.createIslandUseCase().profileLock());

        this.environmentWiring = new GameplayEnvironmentWiring(
                config,
                persistence,
                authority,
                protectionListener,
                creationWiring.locationService(),
                creationWiring.schematicEngine(),
                scheduler,
                worldName,
                this.economicWiring.upgradeService());
        IslandDimensionListener dimensions = this.environmentWiring.dimensionListener();
        if (dimensions != null) {
            // The preset an island was made from is the one its owner's game mode instance names, so
            // each island starts its other dimensions the way its own preset says.
            com.uxplima.uxmskyblock.core.application.preset.StarterPresetCatalog presets =
                    this.creationWiring.presetCatalog();
            com.uxplima.uxmskyblock.core.application.gamemode.GameModeHierarchyService hierarchy =
                    this.creationWiring.gameModeHierarchyService();
            dimensions.usePresets(islandId -> persistence
                    .islandStoragePort()
                    .findIslandById(islandId)
                    .flatMap(island -> hierarchy.findInstanceByProfile(island.ownerProfileId()))
                    .flatMap(instance -> presets.findById(instance.rulesetConfig()))
                    .orElse(presets.defaultPreset()));
        }

        // Each island's edge: how far it reaches, as its tier, its box or its realm says, and how it is drawn.
        this.edgeWiring = new IslandEdgeWiring(
                config,
                persistence,
                scheduler,
                protectionListener,
                this.environmentWiring,
                this.economicWiring,
                this.boxedWiring,
                this.strangerRealmsWiring,
                this.chunkBlockWiring,
                this.membershipService,
                this.cacheEviction);

        this.protectionWiring = new GameplayProtectionWiring(
                config, persistence, authority, protectionListener, temporaryAccessService);
        // Every cache lets an erased island go, and the modes' events count for missions, once every part is built.
        GameplayLinks.link(this, config, persistence, temporaryAccessService, freezeService);
    }

    public SchedulerPort scheduler() {
        return scheduler;
    }

    @Override
    IslandEdgeWiring edges() {
        return edgeWiring;
    }

    @Override
    PlayerRulesWiring playerRules() {
        return playerRules;
    }

    /** Every creation action the game modes bring, each while its mode is enabled. */
    private java.util.List<
                    com.uxplima.uxmskyblock.core.application.gamemode.CreationActionProvider<
                            com.uxplima.uxmskyblock.bukkit.schematic.IslandStart>>
            modeStartActions() {
        java.util.List<
                        com.uxplima.uxmskyblock.core.application.gamemode.CreationActionProvider<
                                com.uxplima.uxmskyblock.bukkit.schematic.IslandStart>>
                actions = new java.util.ArrayList<>();
        actions.addAll(this.oneBlockWiring.startActions());
        actions.addAll(this.chunkBlockWiring.startActions());
        actions.addAll(this.acidIslandWiring.startActions());
        actions.addAll(this.boxedWiring.startActions());
        actions.addAll(this.poseidonWiring.startActions());
        actions.addAll(this.strangerRealmsWiring.startActions());
        actions.addAll(this.parkourWiring.startActions());
        actions.addAll(this.brixWiring.startActions());
        actions.addAll(this.tradeWindsWiring.startActions());
        caveAndGrid.starts().forEach(actions::add);
        return actions;
    }

    public BrixWiring brixWiring() {
        return brixWiring;
    }

    /** The TradeWinds game mode. */
    public TradeWindsWiring tradeWindsWiring() {
        return tradeWindsWiring;
    }

    /** Trading between players. */
    public TradeWiring tradeWiring() {
        return tradeWiring;
    }

    public ParkourWiring parkourWiring() {
        return parkourWiring;
    }

    public StrangerRealmsWiring strangerRealmsWiring() {
        return strangerRealmsWiring;
    }

    /**
     * Writes the shipped cave structures where no file stands yet, then reads every schematic the kinds name. Off the
     * main thread; a kind whose file is not read yet is not laid.
     */
    public java.util.concurrent.CompletableFuture<Integer> primeCaveTemplates(
            com.uxplima.uxmskyblock.bukkit.schematic.IslandSchematics files, int dataVersion) {
        return caveAndGrid.primeTemplates(files, dataVersion);
    }

    public PoseidonWiring poseidonWiring() {
        return poseidonWiring;
    }

    public BoxedWiring boxedWiring() {
        return boxedWiring;
    }

    public AcidIslandWiring acidIslandWiring() {
        return acidIslandWiring;
    }

    public ChunkBlockWiring chunkBlockWiring() {
        return chunkBlockWiring;
    }

    /** The OneBlock game mode's service and the schedule that writes its counts. */
    public OneBlockWiring oneBlockWiring() {
        return oneBlockWiring;
    }

    public AdaptiveBackpressureController backpressureController() {
        return backpressureController;
    }

    public IslandAccessService accessService() {
        return accessService;
    }

    /**
     * The server's structure files, pasted and saved through uxmLib on the region that owns each chunk, with
     * no WorldEdit or FAWE on the server.
     */
    private static com.uxplima.uxmskyblock.bukkit.schematic.IslandSchematics islandSchematics(
            JavaPlugin plugin, ConfigurationWiring config, SchedulerPort scheduler) {
        com.uxplima.uxmlib.scheduler.Scheduler regions = new com.uxplima.uxmlib.scheduler.PaperScheduler(plugin);
        return new com.uxplima.uxmskyblock.bukkit.schematic.IslandSchematics(
                plugin.getDataFolder().toPath(),
                scheduler::async,
                new com.uxplima.uxmlib.schematic.paper.SchematicPaster(regions)::paste,
                new com.uxplima.uxmlib.schematic.paper.SchematicCapture(regions)::capture,
                config.presetConfig().paste().options());
    }

    /** The server's structure files, which the admin's schematic command saves and pastes. */
    public com.uxplima.uxmskyblock.bukkit.schematic.@org.jspecify.annotations.Nullable IslandSchematics
            islandSchematics() {
        return creationWiring.islandSchematics();
    }

    @Override
    GameplayCreationWiring creationWiring() {
        return creationWiring;
    }

    @Override
    GameplayEnvironmentWiring environmentWiring() {
        return environmentWiring;
    }

    @Override
    GameplayProtectionWiring protectionWiring() {
        return protectionWiring;
    }

    @Override
    public AdminWiring adminWiring() {
        return adminWiring;
    }

    @Override
    public EconomicWiring economicWiring() {
        return economicWiring;
    }

    /** Records the visits a rating needs. */
    public com.uxplima.uxmskyblock.bukkit.listener.IslandVisitRecorder visitRecorder() {
        return visitRecorder;
    }

    @Override
    public SocialWiring socialWiring() {
        return socialWiring;
    }

    /** The leaderboard displays operators place, drawn once a minute; nothing with the leaderboards turned off. */
    private com.uxplima.uxmskyblock.bukkit.top.@org.jspecify.annotations.Nullable TopDisplayBeat topDisplays;

    public com.uxplima.uxmskyblock.bukkit.top.@org.jspecify.annotations.Nullable TopDisplayBeat topDisplays() {
        return topDisplays;
    }

    /** Reads the displays placed and starts drawing them, when the leaderboards are on. */
    void startTopDisplays(ConfigurationWiring config) {
        if (!config.moduleSettings().isModuleEnabled("leaderboards") || topDisplays != null) {
            return;
        }
        this.topDisplays = GameplayLinks.topDisplays(config, leaderboardService(), locationService(), scheduler);
        this.topDisplays.start();
    }

    /** Stops drawing the displays. */
    public void stopTopDisplays() {
        var running = topDisplays;
        if (running != null) {
            running.close();
        }
    }

    public BukkitIslandVisitorEvictionAdapter visitorEvictionAdapter() {
        return visitorEvictionAdapter;
    }

    /** One place that forgets an island, for the callers that learn one changed elsewhere. */
    public IslandCacheEviction cacheEviction() {
        return cacheEviction;
    }

    public IslandProtectionListener protectionListener() {
        return protectionListener;
    }

    /** The bucket the operator named for backups, so a restore reads back from where one was written. */
    public StorageBucket backupBucket() {
        return backupBucket;
    }

    public IslandMembershipService membershipService() {
        return membershipService;
    }
}
