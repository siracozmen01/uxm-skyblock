package com.uxplima.uxmskyblock.bukkit.bootstrap;

import java.util.Objects;
import java.util.function.Supplier;

import org.bukkit.plugin.java.JavaPlugin;

import com.uxplima.uxmskyblock.bukkit.antiabuse.IslandAntiAbuseListener;
import com.uxplima.uxmskyblock.bukkit.bank.IslandBankruptcyListener;
import com.uxplima.uxmskyblock.bukkit.biome.BukkitBiomeAdapter;
import com.uxplima.uxmskyblock.bukkit.booster.IslandBoosterListener;
import com.uxplima.uxmskyblock.bukkit.boundary.IslandBoundaryListener;
import com.uxplima.uxmskyblock.bukkit.boundary.WorldBorderPacketAdapter;
import com.uxplima.uxmskyblock.bukkit.dimension.IslandDimensionListener;
import com.uxplima.uxmskyblock.bukkit.freeze.BukkitIslandVisitorEvictionAdapter;
import com.uxplima.uxmskyblock.bukkit.integration.economy.SkyblockEconomyBridge;
import com.uxplima.uxmskyblock.bukkit.limit.IslandLimitListener;
import com.uxplima.uxmskyblock.bukkit.limit.IslandLimitReconciler;
import com.uxplima.uxmskyblock.bukkit.listener.IslandChatListener;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.menu.IslandBoosterMenu;
import com.uxplima.uxmskyblock.bukkit.menu.IslandMissionsMenu;
import com.uxplima.uxmskyblock.bukkit.menu.IslandResetConfirmationMenu;
import com.uxplima.uxmskyblock.bukkit.mission.IslandMissionListener;
import com.uxplima.uxmskyblock.bukkit.module.builtin.UpgradesModule;
import com.uxplima.uxmskyblock.bukkit.notification.IslandNotificationListener;
import com.uxplima.uxmskyblock.bukkit.performance.IslandRedstoneOptimizationListener;
import com.uxplima.uxmskyblock.bukkit.protection.CategoricalInteractablesListener;
import com.uxplima.uxmskyblock.bukkit.protection.ObsidianRecoveryListener;
import com.uxplima.uxmskyblock.bukkit.protection.VoidProtectionListener;
import com.uxplima.uxmskyblock.bukkit.recycle.FoliaIslandVoidingAdapter;
import com.uxplima.uxmskyblock.bukkit.recycle.NbtIslandBackupAdapter;
import com.uxplima.uxmskyblock.bukkit.schematic.StarterSchematicEngine;
import com.uxplima.uxmskyblock.bukkit.upgrade.OreGeneratorListener;
import com.uxplima.uxmskyblock.bukkit.vault.IslandVaultListener;
import com.uxplima.uxmskyblock.bukkit.vault.IslandVaultWindow;
import com.uxplima.uxmskyblock.bukkit.ward.KineticWardListener;
import com.uxplima.uxmskyblock.bukkit.world.AsyncStructureSuppressionListener;
import com.uxplima.uxmskyblock.bukkit.worth.FoliaIslandChunkScanner;
import com.uxplima.uxmskyblock.bukkit.worth.IslandWorthListener;
import com.uxplima.uxmskyblock.core.application.access.TemporaryAccessService;
import com.uxplima.uxmskyblock.core.application.activity.ActivityFeedService;
import com.uxplima.uxmskyblock.core.application.alliance.IslandAllianceService;
import com.uxplima.uxmskyblock.core.application.antiabuse.IslandAntiAbuseService;
import com.uxplima.uxmskyblock.core.application.backup.BackupService;
import com.uxplima.uxmskyblock.core.application.backup.DatabaseDisasterBackupService;
import com.uxplima.uxmskyblock.core.application.backup.IslandBackupService;
import com.uxplima.uxmskyblock.core.application.bank.IslandBankService;
import com.uxplima.uxmskyblock.core.application.bank.IslandBankruptcyService;
import com.uxplima.uxmskyblock.core.application.booster.IslandBoosterService;
import com.uxplima.uxmskyblock.core.application.border.IslandBorderService;
import com.uxplima.uxmskyblock.core.application.border.IslandSizeAllowance;
import com.uxplima.uxmskyblock.core.application.boundary.IslandBoundaryService;
import com.uxplima.uxmskyblock.core.application.chat.IslandChatService;
import com.uxplima.uxmskyblock.core.application.chat.IslandChatTransportPort;
import com.uxplima.uxmskyblock.core.application.dimension.IslandDimensionService;
import com.uxplima.uxmskyblock.core.application.freeze.IslandAdminFreezeService;
import com.uxplima.uxmskyblock.core.application.home.HomeService;
import com.uxplima.uxmskyblock.core.application.inactivity.IslandInactivityService;
import com.uxplima.uxmskyblock.core.application.island.CreateIslandUseCase;
import com.uxplima.uxmskyblock.core.application.island.IslandAccessService;
import com.uxplima.uxmskyblock.core.application.island.IslandCacheEviction;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.leaderboard.IslandLeaderboardService;
import com.uxplima.uxmskyblock.core.application.lifecycle.LifecycleService;
import com.uxplima.uxmskyblock.core.application.limit.IslandLimitService;
import com.uxplima.uxmskyblock.core.application.membership.IslandMembershipService;
import com.uxplima.uxmskyblock.core.application.membership.MemberAllowance;
import com.uxplima.uxmskyblock.core.application.mission.IslandMissionService;
import com.uxplima.uxmskyblock.core.application.name.IslandNameService;
import com.uxplima.uxmskyblock.core.application.performance.AdaptiveBackpressureController;
import com.uxplima.uxmskyblock.core.application.preset.StarterPresetCatalog;
import com.uxplima.uxmskyblock.core.application.recycle.IslandRecycleService;
import com.uxplima.uxmskyblock.core.application.reward.RewardInboxService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.season.IslandSeasonService;
import com.uxplima.uxmskyblock.core.application.shop.DynamicPricingEngine;
import com.uxplima.uxmskyblock.core.application.snapshot.IslandRestoreService;
import com.uxplima.uxmskyblock.core.application.snapshot.WorldDimensionSnapshotPort;
import com.uxplima.uxmskyblock.core.application.social.IslandSocialService;
import com.uxplima.uxmskyblock.core.application.storage.ObjectStoragePort;
import com.uxplima.uxmskyblock.core.application.upgrade.IslandUpgradeService;
import com.uxplima.uxmskyblock.core.application.vault.IslandVaultService;
import com.uxplima.uxmskyblock.core.application.ward.KineticWardService;
import com.uxplima.uxmskyblock.core.application.warp.IslandWarpService;
import com.uxplima.uxmskyblock.core.application.warp.SafeTeleportEngine;
import com.uxplima.uxmskyblock.core.application.world.SpiralWorldGridService;
import com.uxplima.uxmskyblock.core.application.worth.IslandWorthService;
import com.uxplima.uxmskyblock.core.domain.storage.StorageBucket;
import com.uxplima.uxmskyblock.core.domain.upgrade.UpgradeId;
import com.uxplima.uxmskyblock.core.domain.world.SpiralGridCoordinateAllocator;
import com.uxplima.uxmskyblock.persistence.bootstrap.PersistenceBootstrap;
import com.uxplima.uxmskyblock.persistence.storage.LocalFilesystemStorageAdapter;
import org.jspecify.annotations.Nullable;

/**
 * Composition root for gameplay subsystems: island creation, economy, social, admin, protection, and environment.
 */
public final class GameplayWiring {

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
    private final com.uxplima.uxmskyblock.bukkit.boundary.IslandEdges islandEdges;
    private static final java.util.logging.Logger LOGGER =
            java.util.logging.Logger.getLogger(GameplayWiring.class.getName());
    private final com.uxplima.uxmskyblock.bukkit.boundary.IslandEdgeWalls islandEdgeWalls;
    private final AcidIslandWiring acidIslandWiring;
    private final BoxedWiring boxedWiring;
    private final PoseidonWiring poseidonWiring;
    private final StrangerRealmsWiring strangerRealmsWiring;
    private final ParkourWiring parkourWiring;
    private final BrixWiring brixWiring;
    private final TradeWiring tradeWiring;
    private final TradeWindsWiring tradeWindsWiring;
    private final com.uxplima.uxmskyblock.bukkit.lifecycle.PlayerLifecycle playerLifecycle;
    private final com.uxplima.uxmskyblock.bukkit.arrival.ArrivalWatch arrivalWatch;
    private @Nullable AutoCloseable arrivalRounds;
    private final IslandBorderService borderService;
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
        this.poseidonWiring = new PoseidonWiring(config, persistence, scheduler, protectionListener);
        this.strangerRealmsWiring = new StrangerRealmsWiring(config, persistence, scheduler, protectionListener);
        // A creative place seals a player only once their session has put their own state on them.
        java.util.function.Predicate<org.bukkit.entity.Player> inPlay =
                player -> authority.sessionCoordinator().inPlay(player.getUniqueId());
        this.parkourWiring = new ParkourWiring(config, persistence, scheduler, protectionListener, inPlay);
        this.brixWiring = new BrixWiring(config, persistence, scheduler, protectionListener, inPlay);
        this.tradeWiring = new TradeWiring(config, persistence, authority, scheduler);
        this.tradeWindsWiring = new TradeWindsWiring(
                config, persistence, authority, scheduler, protectionListener, economicWiring.bankService());
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
                modeStartActions(config, scheduler));
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
        modeBoards(persistence.modeStandingsPort());

        // What a leave, a kick, a death and a reset do, as the operator's lifecycle rules say.
        this.playerLifecycle = new com.uxplima.uxmskyblock.bukkit.lifecycle.PlayerLifecycle(
                new LifecycleService(
                        config.lifecycleConfig().policy(),
                        persistence.lifecycleOwedEffectsPort(),
                        this.creationWiring.gameModeHierarchyService()::modeOf,
                        this.creationWiring.gameModeHierarchyService()::knownModeOf,
                        authority.profileTypes()::of,
                        authority.profileTypes()::known),
                scheduler,
                authority.sessionCoordinator()::activeProfile);
        authority.sessionCoordinator().whenSessionActive(this.playerLifecycle::onSessionActive);
        // Folia announces no teleport and no respawn; every rule about where a player arrives listens here.
        this.arrivalWatch = new com.uxplima.uxmskyblock.bukkit.arrival.ArrivalWatch(
                scheduler,
                () -> org.bukkit.Bukkit.getWorlds().getFirst().getSpawnLocation(),
                java.time.Clock.systemUTC());

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

        // The island size upgrade had five tiers, a cost for each and a radius on each, and nothing
        // read the radius: an island that paid for the top tier reached exactly as far as one that
        // had paid nothing. The edge follows the tier now, and the protection index is told, because
        // it is what answers for every block a player touches.
        // A Boxed island reaches as far as its box, a StrangerRealms island as far as its size tier and
        // its members take it, and every other island as far as its size tier.
        IslandSizeAllowance sizeAllowance = new IslandSizeAllowance(this.economicWiring.upgradeService());
        this.borderService = new IslandBorderService(
                persistence.islandStoragePort(),
                islandId -> this.boxedWiring
                        .service()
                        .radius(islandId)
                        .orElseGet(() ->
                                this.strangerRealmsWiring.claimRadius(islandId, sizeAllowance.applyAsInt(islandId))),
                persistence.islandMutationLock());
        this.boxedWiring.whenBoxChanged(islandId -> borderService
                .applyAllowance(islandId)
                .ifPresent(moved -> {
                    protectionListener
                            .spatialIndex()
                            .indexIsland(moved.island(), moved.location().worldName());
                    redrawTheEdgeFor(moved);
                }));
        this.membershipService.whenMembersChanged(islandId -> {
            if (this.strangerRealmsWiring.service().isStranger(islandId)) {
                borderService.applyAllowance(islandId).ifPresent(moved -> {
                    protectionListener
                            .spatialIndex()
                            .indexIsland(moved.island(), moved.location().worldName());
                    redrawTheEdgeFor(moved);
                });
            }
        });
        this.economicWiring.upgradeService().whenUpgraded((islandId, upgradeId, newTier) -> {
            if (!UpgradeId.SIZE.equals(upgradeId)) {
                return;
            }
            borderService.applyAllowance(islandId).ifPresent(moved -> {
                protectionListener
                        .spatialIndex()
                        .indexIsland(moved.island(), moved.location().worldName());
                redrawTheEdgeFor(moved);
            });
        });

        // The rest of the caches register as they are built, after the wiring that owns them.
        this.cacheEviction.whenForgotten(this.environmentWiring.limitService()::clearIsland);
        this.cacheEviction.whenForgotten(this.environmentWiring.dimensionService()::resetIslandDimensions);
        // The level is what a ChunkBlock island holds its chunks with, so each score is checked against it.
        this.economicWiring
                .worthService()
                .whenScored((islandId, score) ->
                        this.chunkBlockWiring.service().onLevel(islandId, score.calculatedLevel()));
        this.cacheEviction.whenForgotten(this.economicWiring.worthService()::forgetIsland);
        this.cacheEviction.whenForgotten(this.chunkBlockWiring.service()::forget);
        this.cacheEviction.whenForgotten(this.creationWiring.missionService()::forgetIsland);
        // A mode's own events count for the missions that ask for them, when there are missions.
        com.uxplima.uxmskyblock.bukkit.mission.IslandMissionListener missions = this.creationWiring.missionListener();
        if (missions != null) {
            this.oneBlockWiring.listener().useMissions(missions);
            this.boxedWiring.listener().useMissions(missions);
            // A level and a bank balance are reached rather than counted one at a time.
            missions.useMembers(this.membershipService()::members, org.bukkit.Bukkit::getPlayer);
            this.economicWiring
                    .worthService()
                    .whenScored((islandId, score) -> missions.reached(
                            islandId,
                            com.uxplima.uxmskyblock.core.domain.mission.MissionTriggerType.LEVEL_REACH,
                            score.calculatedLevel()));
            this.economicWiring
                    .bankService()
                    .whenBalanced((islandId, minor) -> missions.reached(
                            islandId,
                            com.uxplima.uxmskyblock.core.domain.mission.MissionTriggerType.BANK_BALANCE,
                            java.math.BigDecimal.valueOf(minor)
                                    .movePointLeft(
                                            com.uxplima.uxmskyblock.core.application.bank.BankCurrencies.PRIMARY_SCALE)
                                    .longValue()));
        }
        // Each island's edge is drawn the way its members chose: the client's border, a wall, or nothing. A ChunkBlock
        // island is edged by the wall around its open ground instead.
        var chunkBlock = this.chunkBlockWiring.service();
        this.islandEdges = new com.uxplima.uxmskyblock.bukkit.boundary.IslandEdges(
                persistence.islandEdgePort(),
                config.wallConfig(),
                island -> chunkBlock.inMemory(island).isPresent());
        this.islandEdgeWalls = new com.uxplima.uxmskyblock.bukkit.boundary.IslandEdgeWalls(
                islandEdges,
                protectionListener,
                scheduler,
                new com.uxplima.uxmskyblock.bukkit.wall.WallPanels(config.wallConfig()),
                persistence.islandStoragePort()::findLocationByIslandId);
        var border = environmentWiring.boundaryListener();
        if (border != null) {
            border.useBorderFor(islandEdges::drawsBorder);
            islandEdges.whenChanged(island -> refreshTheBorderOn(island, border));
        }
        islandEdges.whenChanged(islandEdgeWalls::changed);
        this.cacheEviction.whenForgotten(islandEdgeWalls::forget);
        scheduler.async(() -> {
            try {
                int count = islandEdges.prime();
                LOGGER.fine(() -> count + " islands chose how their edge is drawn.");
            } catch (RuntimeException e) {
                LOGGER.log(java.util.logging.Level.WARNING, "How the islands draw their edges could not be read.", e);
            }
        });
        var chunkSigns = this.chunkBlockWiring.signs();
        if (chunkSigns != null) {
            this.cacheEviction.whenForgotten(chunkSigns::forget);
        }
        this.cacheEviction.whenForgotten(this.acidIslandWiring.service()::forget);
        this.cacheEviction.whenForgotten(this.boxedWiring.service()::forget);
        this.cacheEviction.whenForgotten(this.boxedWiring::forgetLand);
        // An island is cleared over its border, everywhere its game mode wrote past it, a box grown past it and a
        // margin past all of them, on every path that removes it. Its starts say where they wrote.
        this.adminWiring.useWipeAreas(new com.uxplima.uxmskyblock.core.application.recycle.IslandWipeAreas(
                persistence.islandFootprintPort(), this.boxedWiring.service()::radius, clearMargin(config.rootNode())));
        this.creationWiring.schematicEngine().useFootprints(persistence.islandFootprintPort());
        this.cacheEviction.whenForgotten(this.poseidonWiring.service()::forget);
        this.cacheEviction.whenForgotten(this.strangerRealmsWiring.service()::forget);
        this.cacheEviction.whenForgotten(this.parkourWiring.service()::forget);
        this.cacheEviction.whenForgotten(this.brixWiring.service()::forget);
        this.cacheEviction.whenForgotten(this.tradeWindsWiring.service()::forget);
        this.cacheEviction.whenForgotten(this.tradeWindsWiring.market()::forget);
        this.cacheEviction.whenForgotten(this.tradeWindsWiring.contracts()::forget);
        // Three more that hold something for every island a player has merely walked on. Each of
        // them answers a question on the movement or interaction path, and each of them remembers
        // the answer so the path is not a query. An island id is a fresh uuid every time, so an
        // erased island's entry is never read again and nothing was letting go of it.
        this.cacheEviction.whenForgotten(temporaryAccessService::forgetIsland);
        this.cacheEviction.whenForgotten(this.adminWiring.antiAbuseService()::forgetIsland);
        this.cacheEviction.whenForgotten(this.economicWiring.bankruptcyService()::forgetIsland);
        this.cacheEviction.whenForgotten(freezeService::forgetIsland);
        this.cacheEviction.whenForgotten(this.oneBlockWiring.service()::forgetIsland);

        this.protectionWiring = new GameplayProtectionWiring(
                config, persistence, authority, protectionListener, temporaryAccessService);
    }

    public SchedulerPort scheduler() {
        return scheduler;
    }

    /** Which game mode instance each profile plays, and so which mode its island is. */
    public com.uxplima.uxmskyblock.core.application.gamemode.GameModeHierarchyService gameModeHierarchyService() {
        return creationWiring.gameModeHierarchyService();
    }

    /** What carries out the operator's lifecycle rules on players. */
    public com.uxplima.uxmskyblock.bukkit.arrival.ArrivalWatch arrivalWatch() {
        return arrivalWatch;
    }

    /** Starts looking at where players are. Called once every rule is in. */
    public void startArrivals() {
        if (arrivalRounds == null) {
            arrivalRounds = arrivalWatch.start();
        }
    }

    /** Stops looking at where players are. */
    public void stopArrivals() {
        AutoCloseable rounds = arrivalRounds;
        arrivalRounds = null;
        if (rounds != null) {
            try {
                rounds.close();
            } catch (Exception e) {
                java.util.logging.Logger.getLogger(GameplayWiring.class.getName())
                        .log(java.util.logging.Level.WARNING, "The arrival watch did not stop cleanly", e);
            }
        }
    }

    public com.uxplima.uxmskyblock.bukkit.lifecycle.PlayerLifecycle playerLifecycle() {
        return playerLifecycle;
    }

    /** How far past everything an island wrote it is cleared, as {@code grid.clear-margin} says. */
    private static int clearMargin(org.spongepowered.configurate.@Nullable ConfigurationNode root) {
        return root == null ? 16 : Math.max(0, root.node("grid", "clear-margin").getInt(16));
    }

    /**
     * The actions of the game modes that are only a way of building an island: CaveBlock's rock and
     * SkyGrid's grid, each while its module is enabled.
     */
    private static java.util.stream.Stream<
                    com.uxplima.uxmskyblock.core.application.gamemode.CreationActionProvider<
                            com.uxplima.uxmskyblock.bukkit.schematic.IslandStart>>
            caveBlockStart(ConfigurationWiring config, SchedulerPort scheduler) {
        com.uxplima.uxmskyblock.bukkit.config.CaveBlockConfiguration cave = config.caveBlockConfig();
        com.uxplima.uxmskyblock.bukkit.config.SkyGridConfiguration grid = config.skyGridConfig();
        return java.util.stream.Stream.concat(
                cave.enabled()
                        ? java.util.stream.Stream.of(
                                new com.uxplima.uxmskyblock.bukkit.cave.CavernStart(scheduler, cave))
                        : java.util.stream.Stream.empty(),
                grid.enabled()
                        ? java.util.stream.Stream.of(
                                new com.uxplima.uxmskyblock.bukkit.grid.SkyGridStart(scheduler, grid))
                        : java.util.stream.Stream.empty());
    }

    /** Every creation action the game modes bring, each while its mode is enabled. */
    private java.util.List<
                    com.uxplima.uxmskyblock.core.application.gamemode.CreationActionProvider<
                            com.uxplima.uxmskyblock.bukkit.schematic.IslandStart>>
            modeStartActions(ConfigurationWiring config, SchedulerPort scheduler) {
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
        caveBlockStart(config, scheduler).forEach(actions::add);
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

    public StarterPresetCatalog presetCatalog() {
        return creationWiring.presetCatalog();
    }

    /** How a player picks among the kinds of {@link #presetCatalog()}, and which of them are offered. */
    public com.uxplima.uxmskyblock.bukkit.config.PresetChoices presetChoices() {
        return creationWiring.presetChoices();
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

    public StarterSchematicEngine schematicEngine() {
        return creationWiring.schematicEngine();
    }

    public SpiralGridCoordinateAllocator coordinateAllocator() {
        return creationWiring.coordinateAllocator();
    }

    public SpiralWorldGridService gridService() {
        return creationWiring.gridService();
    }

    public CreateIslandUseCase createIslandUseCase() {
        return creationWiring.createIslandUseCase();
    }

    public AdminWiring adminWiring() {
        return adminWiring;
    }

    public EconomicWiring economicWiring() {
        return economicWiring;
    }

    /** Records the visits a rating needs. */
    public com.uxplima.uxmskyblock.bukkit.listener.IslandVisitRecorder visitRecorder() {
        return visitRecorder;
    }

    public SocialWiring socialWiring() {
        return socialWiring;
    }

    public IslandLocationService locationService() {
        return creationWiring.locationService();
    }

    public IslandBankService bankService() {
        return economicWiring.bankService();
    }

    /** Each mode's own boards, for the modes this server runs, on the boards and to every plugin that reads them. */
    private void modeBoards(com.uxplima.uxmskyblock.core.application.leaderboard.ModeStandingsPort standings) {
        var metrics = creationWiring.leaderboardMetrics();
        if (this.oneBlockWiring.enabled()) {
            var oneBlock = this.oneBlockWiring.service();
            com.uxplima.uxmskyblock.core.application.leaderboard.ModeMetrics.oneBlock(
                    metrics,
                    standings,
                    island -> oneBlock.phasesInMemory(island).orElse(oneBlock.phases()));
        }
        if (this.parkourWiring.enabled()) {
            com.uxplima.uxmskyblock.core.application.leaderboard.ModeMetrics.parkour(metrics, standings);
        }
        if (this.boxedWiring.enabled()) {
            com.uxplima.uxmskyblock.core.application.leaderboard.ModeMetrics.boxed(
                    metrics, standings, this.boxedWiring.service().rules());
        }
        if (this.chunkBlockWiring.enabled()) {
            com.uxplima.uxmskyblock.core.application.leaderboard.ModeMetrics.chunkBlock(metrics, standings);
        }
    }

    /** Every metric the plugin ranks, its own and those another plugin registered. */
    public com.uxplima.uxmskyblock.core.application.leaderboard.LeaderboardMetricRegistry leaderboardMetrics() {
        return creationWiring.leaderboardMetrics();
    }

    public IslandLeaderboardService leaderboardService() {
        return creationWiring.leaderboardService();
    }

    public IslandSeasonService seasonService() {
        return creationWiring.seasonService();
    }

    public ActivityFeedService activityFeedService() {
        return socialWiring.activityFeedService();
    }

    /** The inbox a player reads on their next join. */
    public com.uxplima.uxmskyblock.core.application.notification.NotificationService notificationService() {
        return socialWiring.notificationService();
    }

    public IslandNotificationListener notificationListener() {
        return socialWiring.notificationListener();
    }

    public @Nullable IslandVaultWindow vaultWindow() {
        return socialWiring.vaultWindow();
    }

    public @Nullable IslandVaultListener vaultListener() {
        return socialWiring.vaultListener();
    }

    public HomeService homeService() {
        return socialWiring.homeService();
    }

    public IslandSocialService socialService() {
        return socialWiring.socialService();
    }

    public IslandAllianceService allianceService() {
        return socialWiring.allianceService();
    }

    public com.uxplima.uxmskyblock.core.application.shop.IslandShopService shopService() {
        return economicWiring.shopService();
    }

    public com.uxplima.uxmskyblock.bukkit.menu.@Nullable IslandShopMenu shopMenu() {
        return economicWiring.shopMenu();
    }

    public DynamicPricingEngine dynamicPricingEngine() {
        return economicWiring.dynamicPricingEngine();
    }

    public TemporaryAccessService temporaryAccessService() {
        return socialWiring.temporaryAccessService();
    }

    public BukkitIslandVisitorEvictionAdapter visitorEvictionAdapter() {
        return visitorEvictionAdapter;
    }

    public IslandAdminFreezeService freezeService() {
        return adminWiring.freezeService();
    }

    /** One place that forgets an island, for the callers that learn one changed elsewhere. */
    public IslandCacheEviction cacheEviction() {
        return cacheEviction;
    }

    public IslandProtectionListener protectionListener() {
        return protectionListener;
    }

    public BukkitBiomeAdapter biomeAdapter() {
        return environmentWiring.biomeAdapter();
    }

    public IslandMissionService missionService() {
        return creationWiring.missionService();
    }

    public @Nullable IslandMissionsMenu missionsMenu() {
        return creationWiring.missionsMenu();
    }

    public @Nullable IslandMissionListener missionListener() {
        return creationWiring.missionListener();
    }

    public WorldBorderPacketAdapter worldBorderAdapter() {
        return environmentWiring.worldBorderAdapter();
    }

    /**
     * Shows the new edge to the members standing on the island when it grew.
     *
     * <p>Where a member is is a Bukkit question and the rule itself is not, so the rule lives in
     * its own class and this hands it the answer.
     */
    private void redrawTheEdgeFor(IslandBorderService.Moved moved) {
        var unused = new com.uxplima.uxmskyblock.bukkit.boundary.IslandEdgeRedraw(
                        environmentWiring.boundaryService(), GameplayWiring::whereTheyStand)
                .show(moved);
        var after = moved.location().bounds();
        islandEdgeWalls.moved(
                moved.island().id(),
                moved.location().worldName(),
                com.uxplima.uxmskyblock.core.domain.island.IslandBounds.fromCenterAndRadius(
                        after.centerX(), after.centerZ(), moved.fromRadius()),
                after);
    }

    /** Shows everybody standing on {@code island} its edge the new way, each on their own thread. */
    private void refreshTheBorderOn(
            com.uxplima.uxmskyblock.core.domain.identity.IslandId island, IslandBoundaryListener border) {
        for (org.bukkit.entity.Player online : org.bukkit.Bukkit.getOnlinePlayers()) {
            scheduler.onEntity(com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid.of(online.getUniqueId()), () -> {
                org.bukkit.Location at = online.getLocation();
                if (at != null
                        && protectionListener
                                .findIslandAt(at)
                                .map(found -> found.id().equals(island))
                                .orElse(false)) {
                    border.refresh(online);
                }
            });
        }
    }

    /** How each island's edge is drawn, as its members chose. */
    public com.uxplima.uxmskyblock.bukkit.boundary.IslandEdges islandEdges() {
        return islandEdges;
    }

    /** The walls along the edges of islands that chose a wall. */
    public com.uxplima.uxmskyblock.bukkit.boundary.IslandEdgeWalls islandEdgeWalls() {
        return islandEdgeWalls;
    }

    private static java.util.Optional<com.uxplima.uxmskyblock.bukkit.boundary.IslandEdgeRedraw.StandingAt>
            whereTheyStand(com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid playerUuid) {
        org.bukkit.entity.Player online = org.bukkit.Bukkit.getPlayer(playerUuid.value());
        if (online == null) {
            return java.util.Optional.empty();
        }
        org.bukkit.Location standing = online.getLocation();
        if (standing == null) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new com.uxplima.uxmskyblock.bukkit.boundary.IslandEdgeRedraw.StandingAt(
                online.getWorld().getName(), standing.getBlockX(), standing.getBlockZ()));
    }

    public IslandBoundaryService boundaryService() {
        return environmentWiring.boundaryService();
    }

    public @Nullable IslandBoundaryListener boundaryListener() {
        return environmentWiring.boundaryListener();
    }

    public FoliaIslandVoidingAdapter voidingAdapter() {
        return adminWiring.voidingAdapter();
    }

    public NbtIslandBackupAdapter islandBackupAdapter() {
        return adminWiring.islandBackupAdapter();
    }

    public IslandRecycleService recycleService() {
        return adminWiring.recycleService();
    }

    public @Nullable IslandResetConfirmationMenu resetConfirmationMenu() {
        return adminWiring.resetConfirmationMenu();
    }

    public @Nullable FoliaIslandChunkScanner chunkScanner() {
        return economicWiring.chunkScanner();
    }

    public IslandWorthService worthService() {
        return economicWiring.worthService();
    }

    public @Nullable IslandWorthListener worthListener() {
        return economicWiring.worthListener();
    }

    public IslandDimensionService dimensionService() {
        return environmentWiring.dimensionService();
    }

    public @Nullable IslandDimensionListener dimensionListener() {
        return environmentWiring.dimensionListener();
    }

    public IslandLimitService limitService() {
        return environmentWiring.limitService();
    }

    public @Nullable IslandLimitListener limitListener() {
        return environmentWiring.limitListener();
    }

    public @Nullable IslandLimitReconciler limitReconciler() {
        return environmentWiring.limitReconciler();
    }

    public IslandAntiAbuseService antiAbuseService() {
        return adminWiring.antiAbuseService();
    }

    public @Nullable IslandAntiAbuseListener antiAbuseListener() {
        return adminWiring.antiAbuseListener();
    }

    public IslandBoosterService boosterService() {
        return economicWiring.boosterService();
    }

    public @Nullable IslandBoosterListener boosterListener() {
        return economicWiring.boosterListener();
    }

    public @Nullable IslandBoosterMenu boosterMenu() {
        return economicWiring.boosterMenu();
    }

    public IslandBankruptcyService bankruptcyService() {
        return economicWiring.bankruptcyService();
    }

    public @Nullable IslandBankruptcyListener bankruptcyListener() {
        return economicWiring.bankruptcyListener();
    }

    public IslandNameService islandNameService() {
        return creationWiring.islandNameService();
    }

    public KineticWardService kineticWardService() {
        return protectionWiring.kineticWardService();
    }

    public KineticWardListener kineticWardListener() {
        return protectionWiring.kineticWardListener();
    }

    public ObsidianRecoveryListener obsidianRecoveryListener() {
        return protectionWiring.obsidianRecoveryListener();
    }

    public VoidProtectionListener voidProtectionListener() {
        return protectionWiring.voidProtectionListener();
    }

    public com.uxplima.uxmskyblock.bukkit.listener.IslandActionPermissionListener actionPermissionListener() {
        return protectionWiring.actionPermissionListener();
    }

    public CategoricalInteractablesListener categoricalInteractablesListener() {
        return protectionWiring.categoricalInteractablesListener();
    }

    public IslandRedstoneOptimizationListener redstoneOptimizationListener() {
        return protectionWiring.redstoneOptimizationListener();
    }

    public AsyncStructureSuppressionListener structureSuppressionListener() {
        return protectionWiring.structureSuppressionListener();
    }

    public RewardInboxService rewardInboxService() {
        return economicWiring.rewardInboxService();
    }

    public IslandUpgradeService upgradeService() {
        return economicWiring.upgradeService();
    }

    public @Nullable OreGeneratorListener oreGeneratorListener() {
        return economicWiring.oreGeneratorListener();
    }

    public UpgradesModule upgradesModule() {
        return economicWiring.upgradesModule();
    }

    public SafeTeleportEngine safeTeleportEngine() {
        return socialWiring.safeTeleportEngine();
    }

    public IslandWarpService warpService() {
        return socialWiring.warpService();
    }

    public com.uxplima.uxmskyblock.bukkit.menu.@Nullable IslandWarpBrowseMenu warpBrowseMenu() {
        return socialWiring.warpBrowseMenu();
    }

    public IslandVaultService vaultService() {
        return socialWiring.vaultService();
    }

    public IslandChatService chatService() {
        return socialWiring.chatService();
    }

    public @Nullable IslandChatListener chatListener() {
        return socialWiring.chatListener();
    }

    public IslandInactivityService inactivityService() {
        return adminWiring.inactivityService();
    }

    public WorldDimensionSnapshotPort worldDimensionSnapshotPort() {
        return adminWiring.worldDimensionSnapshotPort();
    }

    public ObjectStoragePort objectStoragePort() {
        return adminWiring.objectStoragePort();
    }

    /** The bucket the operator named for backups, so a restore reads back from where one was written. */
    public StorageBucket backupBucket() {
        return backupBucket;
    }

    public BackupService backupService() {
        return adminWiring.backupService();
    }

    public IslandRestoreService islandRestoreService() {
        return adminWiring.islandRestoreService();
    }

    public DatabaseDisasterBackupService databaseBackupService() {
        return adminWiring.databaseBackupService();
    }

    public IslandBackupService islandBackupService() {
        return adminWiring.islandBackupService();
    }

    public IslandMembershipService membershipService() {
        return membershipService;
    }
}
