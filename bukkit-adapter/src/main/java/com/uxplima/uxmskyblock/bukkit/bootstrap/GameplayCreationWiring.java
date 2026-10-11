package com.uxplima.uxmskyblock.bukkit.bootstrap;

import java.util.Objects;

import com.uxplima.uxmskyblock.bukkit.menu.IslandMissionsMenu;
import com.uxplima.uxmskyblock.bukkit.mission.IslandMissionListener;
import com.uxplima.uxmskyblock.bukkit.schematic.StarterSchematicEngine;
import com.uxplima.uxmskyblock.core.application.border.IslandSizeAllowance;
import com.uxplima.uxmskyblock.core.application.gamemode.GameModeHierarchyService;
import com.uxplima.uxmskyblock.core.application.island.CreateIslandUseCase;
import com.uxplima.uxmskyblock.core.application.island.IslandAccessService;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.leaderboard.IslandLeaderboardService;
import com.uxplima.uxmskyblock.core.application.leaderboard.IslandMetricProviders;
import com.uxplima.uxmskyblock.core.application.leaderboard.LeaderboardMetricRegistry;
import com.uxplima.uxmskyblock.core.application.mission.IslandMissionService;
import com.uxplima.uxmskyblock.core.application.name.IslandNameService;
import com.uxplima.uxmskyblock.core.application.performance.AdaptiveBackpressureController;
import com.uxplima.uxmskyblock.core.application.preset.StarterPresetCatalog;
import com.uxplima.uxmskyblock.core.application.reward.RewardInboxService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.season.IslandSeasonService;
import com.uxplima.uxmskyblock.core.application.upgrade.IslandUpgradeService;
import com.uxplima.uxmskyblock.core.application.world.SpiralWorldGridService;
import com.uxplima.uxmskyblock.core.domain.world.SpiralGridCoordinateAllocator;
import com.uxplima.uxmskyblock.persistence.bootstrap.PersistenceBootstrap;
import org.jspecify.annotations.Nullable;

/**
 * Encapsulates island creation, schematic pasting, coordinate allocation, mission, and naming subsystems.
 */
public final class GameplayCreationWiring {

    private final StarterPresetCatalog presetCatalog;
    private final com.uxplima.uxmskyblock.bukkit.config.PresetChoices presetChoices;
    private final StarterSchematicEngine schematicEngine;
    private final com.uxplima.uxmskyblock.bukkit.schematic.@org.jspecify.annotations.Nullable IslandSchematics
            islandSchematics;
    private final SpiralGridCoordinateAllocator coordinateAllocator;
    private final SpiralWorldGridService gridService;
    private final CreateIslandUseCase createIslandUseCase;
    private final GameModeHierarchyService gameModeHierarchyService;
    private final IslandLocationService locationService;
    private final IslandLeaderboardService leaderboardService;
    private final LeaderboardMetricRegistry leaderboardMetrics;
    private final IslandSeasonService seasonService;
    private final IslandMissionService missionService;
    private final @Nullable IslandMissionsMenu missionsMenu;
    private final @Nullable IslandMissionListener missionListener;
    private final IslandNameService islandNameService;

    public GameplayCreationWiring(
            ConfigurationWiring config,
            PersistenceBootstrap persistence,
            AuthorityWiring authority,
            SchedulerPort scheduler,
            AdaptiveBackpressureController backpressureController,
            IslandAccessService accessService,
            RewardInboxService rewardInboxService,
            IslandUpgradeService upgradeService,
            com.uxplima.uxmskyblock.bukkit.schematic.@org.jspecify.annotations.Nullable IslandSchematics schematics,
            java.util.List<
                            com.uxplima.uxmskyblock.core.application.gamemode.CreationActionProvider<
                                    com.uxplima.uxmskyblock.bukkit.schematic.IslandStart>>
                    gameModeStarts) {
        Objects.requireNonNull(config, "config must not be null");
        Objects.requireNonNull(persistence, "persistence must not be null");
        Objects.requireNonNull(authority, "authority must not be null");
        Objects.requireNonNull(scheduler, "scheduler must not be null");
        Objects.requireNonNull(backpressureController, "backpressureController must not be null");
        Objects.requireNonNull(accessService, "accessService must not be null");
        Objects.requireNonNull(rewardInboxService, "rewardInboxService must not be null");
        Objects.requireNonNull(upgradeService, "upgradeService must not be null");

        this.islandSchematics = schematics;
        // A platform comes from the preset's schematic where one stands, and a dimension's from the one
        // dimensions.conf names.
        java.util.Map<
                        com.uxplima.uxmskyblock.core.domain.dimension.IslandDimensionType,
                        com.uxplima.uxmskyblock.core.domain.dimension.DimensionMapping>
                dimensions = config.dimensionConfig().mappings();
        this.schematicEngine = new StarterSchematicEngine(
                backpressureController,
                schematics,
                scheduler,
                type -> java.util.Optional.ofNullable(dimensions.get(type))
                        .map(com.uxplima.uxmskyblock.core.domain.dimension.DimensionMapping::schematic));
        gameModeStarts.forEach(schematicEngine.actions()::register);
        // A preset a switched-off game mode builds names an action nobody provides here, and is not offered.
        var startable = config.presetConfig().startableWith(schematicEngine.actions()::knowsAll);
        this.presetCatalog = startable.catalogue();
        this.presetChoices = startable.choices();
        this.coordinateAllocator = persistence.gridAllocator();
        this.gridService = new SpiralWorldGridService(coordinateAllocator);

        // Every island belongs to one game mode instance, and the instance is what a backup names
        // when it says which world the island came from. Nothing ever wrote one, so every backup
        // fell through to an id synthesised from the owner's profile: a reference to a row that has
        // never existed.
        this.gameModeHierarchyService = new GameModeHierarchyService(persistence.gameModeHierarchyStoragePort());
        this.createIslandUseCase = new CreateIslandUseCase(
                persistence.islandStoragePort(),
                persistence.islandAuthorityPort(),
                persistence.islandBankPort(),
                presetCatalog,
                gridService,
                persistence.worldGridAllocationPort(),
                persistence.outboxPort(),
                this.gameModeHierarchyService,
                new IslandSizeAllowance(upgradeService),
                config.worldConfig().islandSpawnY(),
                (int) config.nodeConfig().authorityLease().toSeconds());
        this.locationService =
                new IslandLocationService(persistence.islandStoragePort(), persistence.islandMutationLock());
        this.leaderboardService = new IslandLeaderboardService(
                persistence.islandLeaderboardPort(),
                100,
                config.levelConfig().leaderboardFreshness(),
                java.time.Clock.systemUTC());
        // The level, worth and bank boards, and any a plugin adds, ranked by one registry.
        this.leaderboardMetrics = new LeaderboardMetricRegistry();
        IslandMetricProviders.registerInto(leaderboardMetrics, persistence.islandLeaderboardPort());

        this.seasonService = new IslandSeasonService(
                persistence.islandSeasonStoragePort(),
                persistence.islandLeaderboardPort(),
                persistence.islandStoragePort(),
                rewardInboxService);

        this.missionService = new IslandMissionService(persistence.islandMissionStoragePort());
        if (config.moduleSettings().isModuleEnabled("missions")) {
            this.missionService.registerMissions(config.missionConfig().missions());
            this.missionService.resetIn(config.missionConfig().resetZone());
            this.missionsMenu = new IslandMissionsMenu(
                    missionService,
                    persistence.islandStoragePort(),
                    authority.sessionCoordinator(),
                    scheduler,
                    config.messages(),
                    null);
            this.missionListener = new IslandMissionListener(
                    missionService,
                    persistence.islandStoragePort(),
                    authority.sessionCoordinator(),
                    scheduler,
                    config.messages());
            this.missionListener.useEffects(
                    config.effectsConfig(),
                    new com.uxplima.uxmskyblock.bukkit.effect.InteractionEffectPlayer(scheduler, config.messages()));
        } else {
            this.missionsMenu = null;
            this.missionListener = null;
        }

        this.islandNameService = new IslandNameService(
                persistence.islandNameStoragePort(),
                persistence.islandStoragePort(),
                accessService,
                persistence.outboxPort());
    }

    public StarterPresetCatalog presetCatalog() {
        return presetCatalog;
    }

    /** How a player picks among the kinds of {@link #presetCatalog()}, and which of them are offered. */
    public com.uxplima.uxmskyblock.bukkit.config.PresetChoices presetChoices() {
        return presetChoices;
    }

    public StarterSchematicEngine schematicEngine() {
        return schematicEngine;
    }

    public com.uxplima.uxmskyblock.bukkit.schematic.@org.jspecify.annotations.Nullable IslandSchematics
            islandSchematics() {
        return islandSchematics;
    }

    public SpiralGridCoordinateAllocator coordinateAllocator() {
        return coordinateAllocator;
    }

    public SpiralWorldGridService gridService() {
        return gridService;
    }

    /** The game mode hierarchy every island is bound into when it is created. */
    public GameModeHierarchyService gameModeHierarchyService() {
        return gameModeHierarchyService;
    }

    public CreateIslandUseCase createIslandUseCase() {
        return createIslandUseCase;
    }

    public IslandLocationService locationService() {
        return locationService;
    }

    public LeaderboardMetricRegistry leaderboardMetrics() {
        return leaderboardMetrics;
    }

    public IslandLeaderboardService leaderboardService() {
        return leaderboardService;
    }

    public IslandSeasonService seasonService() {
        return seasonService;
    }

    public IslandMissionService missionService() {
        return missionService;
    }

    public @Nullable IslandMissionsMenu missionsMenu() {
        return missionsMenu;
    }

    public @Nullable IslandMissionListener missionListener() {
        return missionListener;
    }

    public IslandNameService islandNameService() {
        return islandNameService;
    }
}
