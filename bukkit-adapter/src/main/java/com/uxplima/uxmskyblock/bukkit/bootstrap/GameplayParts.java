package com.uxplima.uxmskyblock.bukkit.bootstrap;

import com.uxplima.uxmskyblock.bukkit.antiabuse.IslandAntiAbuseListener;
import com.uxplima.uxmskyblock.bukkit.bank.IslandBankruptcyListener;
import com.uxplima.uxmskyblock.bukkit.biome.BukkitBiomeAdapter;
import com.uxplima.uxmskyblock.bukkit.booster.IslandBoosterListener;
import com.uxplima.uxmskyblock.bukkit.boundary.IslandBoundaryListener;
import com.uxplima.uxmskyblock.bukkit.boundary.WorldBorderPacketAdapter;
import com.uxplima.uxmskyblock.bukkit.dimension.IslandDimensionListener;
import com.uxplima.uxmskyblock.bukkit.limit.IslandLimitListener;
import com.uxplima.uxmskyblock.bukkit.limit.IslandLimitReconciler;
import com.uxplima.uxmskyblock.bukkit.listener.IslandChatListener;
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
import com.uxplima.uxmskyblock.core.application.boundary.IslandBoundaryService;
import com.uxplima.uxmskyblock.core.application.chat.IslandChatService;
import com.uxplima.uxmskyblock.core.application.dimension.IslandDimensionService;
import com.uxplima.uxmskyblock.core.application.freeze.IslandAdminFreezeService;
import com.uxplima.uxmskyblock.core.application.home.HomeService;
import com.uxplima.uxmskyblock.core.application.inactivity.IslandInactivityService;
import com.uxplima.uxmskyblock.core.application.island.CreateIslandUseCase;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.leaderboard.IslandLeaderboardService;
import com.uxplima.uxmskyblock.core.application.limit.IslandLimitService;
import com.uxplima.uxmskyblock.core.application.mission.IslandMissionService;
import com.uxplima.uxmskyblock.core.application.name.IslandNameService;
import com.uxplima.uxmskyblock.core.application.preset.StarterPresetCatalog;
import com.uxplima.uxmskyblock.core.application.recycle.IslandRecycleService;
import com.uxplima.uxmskyblock.core.application.reward.RewardInboxService;
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
import com.uxplima.uxmskyblock.core.domain.world.SpiralGridCoordinateAllocator;
import org.jspecify.annotations.Nullable;

/**
 * What {@link GameplayWiring} hands out that one of its parts built: the economy's services, the creation path's, the
 * social ones, the operator's, the environment's and the protection listeners. Each is read off the part that owns it.
 * Split out of the gameplay wiring, which extends this and builds the parts, so neither file is over the size the
 * standards draw a line at, and every caller still asks the gameplay wiring.
 */
abstract class GameplayParts {

    abstract EconomicWiring economicWiring();

    abstract GameplayCreationWiring creationWiring();

    abstract SocialWiring socialWiring();

    abstract AdminWiring adminWiring();

    abstract GameplayEnvironmentWiring environmentWiring();

    abstract GameplayProtectionWiring protectionWiring();

    abstract PlayerRulesWiring playerRules();

    abstract IslandEdgeWiring edges();

    /** How each island's edge is drawn, as its members chose. */
    public com.uxplima.uxmskyblock.bukkit.boundary.IslandEdges islandEdges() {
        return edges().islandEdges();
    }

    /** The walls along the edges of islands that chose a wall. */
    public com.uxplima.uxmskyblock.bukkit.boundary.IslandEdgeWalls islandEdgeWalls() {
        return edges().islandEdgeWalls();
    }

    /** The crop, spawner and drop rates an island pays for, at work. */
    public com.uxplima.uxmskyblock.bukkit.upgrade.IslandRates islandRates() {
        return playerRules().islandRates();
    }

    /** {@code /is fly}, and the watch that sets a flier down off their island. */
    public com.uxplima.uxmskyblock.bukkit.fly.IslandFly islandFly() {
        return playerRules().islandFly();
    }

    /** What carries out the operator's lifecycle rules on players. */
    public com.uxplima.uxmskyblock.bukkit.arrival.ArrivalWatch arrivalWatch() {
        return playerRules().arrivalWatch();
    }

    /** Starts looking at where players are. Called once every rule is in. */
    public void startArrivals() {
        playerRules().startArrivals();
    }

    /** Stops looking at where players are. */
    public void stopArrivals() {
        playerRules().stopArrivals();
    }

    public com.uxplima.uxmskyblock.bukkit.lifecycle.PlayerLifecycle playerLifecycle() {
        return playerRules().playerLifecycle();
    }

    /** Which game mode instance each profile plays, and so which mode its island is. */
    public com.uxplima.uxmskyblock.core.application.gamemode.GameModeHierarchyService gameModeHierarchyService() {
        return creationWiring().gameModeHierarchyService();
    }

    public StarterPresetCatalog presetCatalog() {
        return creationWiring().presetCatalog();
    }
    /** How a player picks among the kinds of {@link #presetCatalog()}, and which of them are offered. */
    public com.uxplima.uxmskyblock.bukkit.config.PresetChoices presetChoices() {
        return creationWiring().presetChoices();
    }

    public StarterSchematicEngine schematicEngine() {
        return creationWiring().schematicEngine();
    }

    public SpiralGridCoordinateAllocator coordinateAllocator() {
        return creationWiring().coordinateAllocator();
    }

    public SpiralWorldGridService gridService() {
        return creationWiring().gridService();
    }

    public CreateIslandUseCase createIslandUseCase() {
        return creationWiring().createIslandUseCase();
    }

    public IslandLocationService locationService() {
        return creationWiring().locationService();
    }

    public IslandBankService bankService() {
        return economicWiring().bankService();
    }
    /** Every metric the plugin ranks, its own and those another plugin registered. */
    public com.uxplima.uxmskyblock.core.application.leaderboard.LeaderboardMetricRegistry leaderboardMetrics() {
        return creationWiring().leaderboardMetrics();
    }

    public IslandLeaderboardService leaderboardService() {
        return creationWiring().leaderboardService();
    }

    public IslandSeasonService seasonService() {
        return creationWiring().seasonService();
    }

    public ActivityFeedService activityFeedService() {
        return socialWiring().activityFeedService();
    }
    /** The inbox a player reads on their next join. */
    public com.uxplima.uxmskyblock.core.application.notification.NotificationService notificationService() {
        return socialWiring().notificationService();
    }

    public IslandNotificationListener notificationListener() {
        return socialWiring().notificationListener();
    }

    public @Nullable IslandVaultWindow vaultWindow() {
        return socialWiring().vaultWindow();
    }

    public @Nullable IslandVaultListener vaultListener() {
        return socialWiring().vaultListener();
    }

    public HomeService homeService() {
        return socialWiring().homeService();
    }

    public IslandSocialService socialService() {
        return socialWiring().socialService();
    }

    public IslandAllianceService allianceService() {
        return socialWiring().allianceService();
    }

    public com.uxplima.uxmskyblock.core.application.shop.IslandShopService shopService() {
        return economicWiring().shopService();
    }

    public com.uxplima.uxmskyblock.bukkit.menu.@Nullable IslandShopMenu shopMenu() {
        return economicWiring().shopMenu();
    }

    public DynamicPricingEngine dynamicPricingEngine() {
        return economicWiring().dynamicPricingEngine();
    }

    public TemporaryAccessService temporaryAccessService() {
        return socialWiring().temporaryAccessService();
    }

    public IslandAdminFreezeService freezeService() {
        return adminWiring().freezeService();
    }

    public BukkitBiomeAdapter biomeAdapter() {
        return environmentWiring().biomeAdapter();
    }

    public IslandMissionService missionService() {
        return creationWiring().missionService();
    }

    public @Nullable IslandMissionsMenu missionsMenu() {
        return creationWiring().missionsMenu();
    }

    public @Nullable IslandMissionListener missionListener() {
        return creationWiring().missionListener();
    }

    public WorldBorderPacketAdapter worldBorderAdapter() {
        return environmentWiring().worldBorderAdapter();
    }

    public IslandBoundaryService boundaryService() {
        return environmentWiring().boundaryService();
    }

    public @Nullable IslandBoundaryListener boundaryListener() {
        return environmentWiring().boundaryListener();
    }

    public FoliaIslandVoidingAdapter voidingAdapter() {
        return adminWiring().voidingAdapter();
    }

    public NbtIslandBackupAdapter islandBackupAdapter() {
        return adminWiring().islandBackupAdapter();
    }

    public IslandRecycleService recycleService() {
        return adminWiring().recycleService();
    }

    public @Nullable IslandResetConfirmationMenu resetConfirmationMenu() {
        return adminWiring().resetConfirmationMenu();
    }

    public @Nullable FoliaIslandChunkScanner chunkScanner() {
        return economicWiring().chunkScanner();
    }

    public IslandWorthService worthService() {
        return economicWiring().worthService();
    }

    public @Nullable IslandWorthListener worthListener() {
        return economicWiring().worthListener();
    }

    public IslandDimensionService dimensionService() {
        return environmentWiring().dimensionService();
    }

    public @Nullable IslandDimensionListener dimensionListener() {
        return environmentWiring().dimensionListener();
    }

    public IslandLimitService limitService() {
        return environmentWiring().limitService();
    }

    public @Nullable IslandLimitListener limitListener() {
        return environmentWiring().limitListener();
    }

    public @Nullable IslandLimitReconciler limitReconciler() {
        return environmentWiring().limitReconciler();
    }

    public IslandAntiAbuseService antiAbuseService() {
        return adminWiring().antiAbuseService();
    }

    public @Nullable IslandAntiAbuseListener antiAbuseListener() {
        return adminWiring().antiAbuseListener();
    }

    public IslandBoosterService boosterService() {
        return economicWiring().boosterService();
    }

    public @Nullable IslandBoosterListener boosterListener() {
        return economicWiring().boosterListener();
    }

    public @Nullable IslandBoosterMenu boosterMenu() {
        return economicWiring().boosterMenu();
    }

    public IslandBankruptcyService bankruptcyService() {
        return economicWiring().bankruptcyService();
    }

    public @Nullable IslandBankruptcyListener bankruptcyListener() {
        return economicWiring().bankruptcyListener();
    }

    public IslandNameService islandNameService() {
        return creationWiring().islandNameService();
    }

    public KineticWardService kineticWardService() {
        return protectionWiring().kineticWardService();
    }

    public KineticWardListener kineticWardListener() {
        return protectionWiring().kineticWardListener();
    }

    public ObsidianRecoveryListener obsidianRecoveryListener() {
        return protectionWiring().obsidianRecoveryListener();
    }

    public VoidProtectionListener voidProtectionListener() {
        return protectionWiring().voidProtectionListener();
    }

    public com.uxplima.uxmskyblock.bukkit.listener.IslandActionPermissionListener actionPermissionListener() {
        return protectionWiring().actionPermissionListener();
    }

    public CategoricalInteractablesListener categoricalInteractablesListener() {
        return protectionWiring().categoricalInteractablesListener();
    }

    public IslandRedstoneOptimizationListener redstoneOptimizationListener() {
        return protectionWiring().redstoneOptimizationListener();
    }

    public AsyncStructureSuppressionListener structureSuppressionListener() {
        return protectionWiring().structureSuppressionListener();
    }

    public RewardInboxService rewardInboxService() {
        return economicWiring().rewardInboxService();
    }

    public IslandUpgradeService upgradeService() {
        return economicWiring().upgradeService();
    }

    public @Nullable OreGeneratorListener oreGeneratorListener() {
        return economicWiring().oreGeneratorListener();
    }

    public UpgradesModule upgradesModule() {
        return economicWiring().upgradesModule();
    }

    public SafeTeleportEngine safeTeleportEngine() {
        return socialWiring().safeTeleportEngine();
    }

    public IslandWarpService warpService() {
        return socialWiring().warpService();
    }

    public com.uxplima.uxmskyblock.bukkit.menu.@Nullable IslandWarpBrowseMenu warpBrowseMenu() {
        return socialWiring().warpBrowseMenu();
    }

    public IslandVaultService vaultService() {
        return socialWiring().vaultService();
    }

    public IslandChatService chatService() {
        return socialWiring().chatService();
    }

    public @Nullable IslandChatListener chatListener() {
        return socialWiring().chatListener();
    }

    public IslandInactivityService inactivityService() {
        return adminWiring().inactivityService();
    }

    public WorldDimensionSnapshotPort worldDimensionSnapshotPort() {
        return adminWiring().worldDimensionSnapshotPort();
    }

    public ObjectStoragePort objectStoragePort() {
        return adminWiring().objectStoragePort();
    }

    public BackupService backupService() {
        return adminWiring().backupService();
    }

    public IslandRestoreService islandRestoreService() {
        return adminWiring().islandRestoreService();
    }

    public DatabaseDisasterBackupService databaseBackupService() {
        return adminWiring().databaseBackupService();
    }

    public IslandBackupService islandBackupService() {
        return adminWiring().islandBackupService();
    }
}
