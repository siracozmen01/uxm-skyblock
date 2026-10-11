package com.uxplima.uxmskyblock.bukkit.command;

import java.util.Objects;
import java.util.UUID;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.uxplima.uxmlib.command.Cmd;
import com.uxplima.uxmlib.command.CommandRegistrar;
import com.uxplima.uxmskyblock.bukkit.bootstrap.SkyblockReloader;
import com.uxplima.uxmskyblock.bukkit.config.HomeConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.TemporaryAccessConfiguration;
import com.uxplima.uxmskyblock.bukkit.dimension.IslandDimensionListener;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.integration.economy.SkyblockEconomyBridge;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.permission.CatalogPermissions;
import com.uxplima.uxmskyblock.bukkit.schematic.StarterSchematicEngine;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.bukkit.vault.IslandVaultWindow;
import com.uxplima.uxmskyblock.bukkit.webmap.IslandMarkerSynchroniser;
import com.uxplima.uxmskyblock.core.application.activity.ActivityFeedService;
import com.uxplima.uxmskyblock.core.application.alliance.IslandAllianceService;
import com.uxplima.uxmskyblock.core.application.antiabuse.IslandAntiAbuseService;
import com.uxplima.uxmskyblock.core.application.backup.BackupService;
import com.uxplima.uxmskyblock.core.application.backup.IslandBackupService;
import com.uxplima.uxmskyblock.core.application.bank.IslandBankService;
import com.uxplima.uxmskyblock.core.application.bank.IslandBankruptcyService;
import com.uxplima.uxmskyblock.core.application.biome.BiomeModificationPort;
import com.uxplima.uxmskyblock.core.application.flag.IslandFlagService;
import com.uxplima.uxmskyblock.core.application.home.HomeService;
import com.uxplima.uxmskyblock.core.application.island.CreateIslandUseCase;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.leaderboard.IslandLeaderboardService;
import com.uxplima.uxmskyblock.core.application.limit.IslandLimitService;
import com.uxplima.uxmskyblock.core.application.membership.IslandMembershipService;
import com.uxplima.uxmskyblock.core.application.mission.IslandMissionService;
import com.uxplima.uxmskyblock.core.application.name.IslandNameService;
import com.uxplima.uxmskyblock.core.application.network.IslandNetworkRouter;
import com.uxplima.uxmskyblock.core.application.preset.StarterPresetCatalog;
import com.uxplima.uxmskyblock.core.application.reward.RewardInboxService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.season.IslandSeasonService;
import com.uxplima.uxmskyblock.core.application.snapshot.IslandRestoreService;
import com.uxplima.uxmskyblock.core.application.social.IslandSocialService;
import com.uxplima.uxmskyblock.core.application.upgrade.IslandUpgradeStoragePort;
import com.uxplima.uxmskyblock.core.application.warp.IslandWarpService;
import com.uxplima.uxmskyblock.core.application.worth.IslandWorthService;
import com.uxplima.uxmskyblock.core.domain.access.CurrentNodeProcessIdentity;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import com.uxplima.uxmskyblock.core.domain.storage.StorageBucket;
import org.jspecify.annotations.Nullable;

/**
 * Paper Brigadier command tree for {@code /island} and {@code /is}.
 *
 * <p>Coordinates subcommand groups: {@link IslandBankCommands}, {@link IslandChatCommands},
 * {@link IslandAdminCommands}, {@link IslandLifecycleCommands}, {@link IslandNavigationCommands},
 * {@link IslandProgressionCommands}, and {@link IslandMechanicsCommands}.
 */
public final class IslandCommandTree {

    // The fields below carry no modifier on purpose. CommandGroupBuilder is this class's other half,
    // split out only because holding both what the verbs are and where they hang put one file back
    // over the size the standards draw a line at. It lives in this package and nowhere else, and it
    // reads these directly rather than through thirty accessors that would exist for nothing.

    final CreateIslandUseCase createIslandUseCase;
    final IslandLocationService islandLocationService;
    final IslandFlagService flagService;
    final IslandBankService islandBankService;
    private final IslandUpgradeStoragePort islandUpgradePort;
    final IslandLeaderboardService islandLeaderboardService;
    final BiomeModificationPort biomeModificationPort;
    final StarterPresetCatalog presetCatalog;
    final StarterSchematicEngine schematicEngine;
    final IslandProtectionListener protectionListener;
    final PlayerSessionCoordinator sessionCoordinator;
    final SchedulerPort schedulerPort;
    final Messages messages;
    final HomeConfiguration homeConfiguration;
    volatile @Nullable HomeService homeService;
    /** How many log lines a caller who names no number gets. */
    private static final int DEFAULT_VAULT_LOG_LINES = 10;

    private volatile @Nullable IslandVaultWindow vaultWindow;
    volatile @Nullable ActivityFeedService activityFeedService;
    final ServerNodeId serverNodeId;
    final String worldName;
    final SkyblockEconomyBridge economyBridge;
    final IslandFeatures features;
    final CommandWindows windows;
    volatile @Nullable IslandBankruptcyService bankruptcyService;
    volatile @Nullable IslandNameService nameService;
    volatile @Nullable IslandNetworkRouter networkRouter;
    volatile @Nullable IslandRestoreService restoreService;
    volatile @Nullable BackupService backupService;
    volatile @Nullable StorageBucket backupBucket;
    volatile @Nullable IslandBackupService islandBackupService;

    /** The whole database, for the day the database is gone. */
    volatile com.uxplima.uxmskyblock.core.application.backup.@Nullable DatabaseDisasterBackupService
            databaseBackupService;

    volatile @Nullable IslandMembershipService membershipService;
    volatile @Nullable IslandSeasonService seasonService;
    volatile @Nullable SkyblockReloader reloader;
    volatile java.util.function.Supplier<java.util.List<com.uxplima.uxmlib.health.HealthCheck>> healthChecks =
            java.util.List::of;
    volatile @Nullable IslandWarpService warpService;

    /** What the operator wrote for the milestones the commands reach. */
    volatile com.uxplima.uxmskyblock.bukkit.effect.@Nullable InteractionEffects interactionEffects;

    /** The bypass permissions and the cooldowns the operator named for the anti abuse rules. */
    volatile com.uxplima.uxmskyblock.bukkit.config.@Nullable AntiAbuseConfiguration antiAbuseConfiguration;

    /** Which biomes the operator offers and what an island has to reach first. */
    volatile com.uxplima.uxmskyblock.bukkit.config.@Nullable BiomeConfiguration biomeConfiguration;

    volatile java.time.Duration recalculationCooldown =
            com.uxplima.uxmskyblock.core.application.worth.RecalculationGate.DEFAULT_COOLDOWN;

    /** The window the public warp directory opens in, so its icons are drawn. */
    volatile com.uxplima.uxmskyblock.bukkit.menu.@Nullable IslandWarpBrowseMenu warpBrowseMenu;

    volatile com.uxplima.uxmskyblock.bukkit.menu.@Nullable HomeList homeList;
    volatile com.uxplima.uxmskyblock.bukkit.menu.@Nullable TopList topList;
    volatile com.uxplima.uxmskyblock.bukkit.menu.@Nullable PresetList presetList;
    volatile com.uxplima.uxmskyblock.bukkit.menu.@Nullable SocialWindows socialWindows;
    volatile com.uxplima.uxmskyblock.core.application.membership.@Nullable IslandRoleShaper roleShaper;
    volatile com.uxplima.uxmskyblock.bukkit.config.RoleConfiguration roleConfiguration =
            com.uxplima.uxmskyblock.bukkit.config.RoleConfiguration.defaults();
    volatile com.uxplima.uxmskyblock.bukkit.config.PresetChoices presetChoices =
            com.uxplima.uxmskyblock.bukkit.config.PresetChoices.DEFAULT;

    volatile @Nullable IslandSocialService socialService;
    volatile @Nullable IslandAllianceService allianceService;
    volatile @Nullable RewardInboxService rewardInboxService;
    volatile @Nullable IslandMarkerSynchroniser markerSynchroniser;
    volatile @Nullable IslandMissionService missionService;

    /** What a trust grant carries and how long it may last. Set before the tree is registered. */
    volatile @Nullable TemporaryAccessConfiguration temporaryAccessConfiguration;

    /** Which node and which boot this is, for a grant that lasts until the node restarts. */
    volatile @Nullable CurrentNodeProcessIdentity nodeProcessIdentity;

    /** Which ruleset a profile plays under, so a grant cannot cross a ruleset boundary. */
    volatile com.uxplima.uxmskyblock.core.application.profile.@Nullable ProfileTypes profileTypes;

    /** Where a notice goes for a player who is not here to be told. */
    volatile com.uxplima.uxmskyblock.core.application.notification.@Nullable NotificationService notificationService;

    public IslandCommandTree(
            CreateIslandUseCase createIslandUseCase,
            IslandLocationService islandLocationService,
            IslandFlagService flagService,
            IslandBankService islandBankService,
            IslandUpgradeStoragePort islandUpgradePort,
            IslandLeaderboardService islandLeaderboardService,
            BiomeModificationPort biomeModificationPort,
            StarterPresetCatalog presetCatalog,
            StarterSchematicEngine schematicEngine,
            IslandProtectionListener protectionListener,
            PlayerSessionCoordinator sessionCoordinator,
            SchedulerPort schedulerPort,
            Messages messages,
            HomeConfiguration homeConfiguration,
            ServerNodeId serverNodeId,
            String worldName) {
        this(
                createIslandUseCase,
                islandLocationService,
                flagService,
                islandBankService,
                islandUpgradePort,
                islandLeaderboardService,
                biomeModificationPort,
                presetCatalog,
                schematicEngine,
                protectionListener,
                sessionCoordinator,
                schedulerPort,
                messages,
                homeConfiguration,
                serverNodeId,
                worldName,
                SkyblockEconomyBridge.createDefault(islandBankService, schedulerPort),
                IslandFeatures.none());
    }

    public IslandCommandTree(
            CreateIslandUseCase createIslandUseCase,
            IslandLocationService islandLocationService,
            IslandFlagService flagService,
            IslandBankService islandBankService,
            IslandUpgradeStoragePort islandUpgradePort,
            IslandLeaderboardService islandLeaderboardService,
            BiomeModificationPort biomeModificationPort,
            StarterPresetCatalog presetCatalog,
            StarterSchematicEngine schematicEngine,
            IslandProtectionListener protectionListener,
            PlayerSessionCoordinator sessionCoordinator,
            SchedulerPort schedulerPort,
            Messages messages,
            HomeConfiguration homeConfiguration,
            ServerNodeId serverNodeId,
            String worldName,
            SkyblockEconomyBridge economyBridge,
            IslandFeatures features) {
        this.createIslandUseCase = Objects.requireNonNull(createIslandUseCase, "createIslandUseCase must not be null");
        this.islandLocationService =
                Objects.requireNonNull(islandLocationService, "islandLocationService must not be null");
        this.flagService = Objects.requireNonNull(flagService, "flagService must not be null");
        this.islandBankService = Objects.requireNonNull(islandBankService, "islandBankService must not be null");
        this.islandUpgradePort = Objects.requireNonNull(islandUpgradePort, "islandUpgradePort must not be null");
        this.islandLeaderboardService =
                Objects.requireNonNull(islandLeaderboardService, "islandLeaderboardService must not be null");
        this.biomeModificationPort =
                Objects.requireNonNull(biomeModificationPort, "biomeModificationPort must not be null");
        this.presetCatalog = Objects.requireNonNull(presetCatalog, "presetCatalog must not be null");
        this.schematicEngine = Objects.requireNonNull(schematicEngine, "schematicEngine must not be null");
        this.protectionListener = Objects.requireNonNull(protectionListener, "protectionListener must not be null");
        this.sessionCoordinator = Objects.requireNonNull(sessionCoordinator, "sessionCoordinator must not be null");
        this.schedulerPort = Objects.requireNonNull(schedulerPort, "schedulerPort must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
        this.homeConfiguration = Objects.requireNonNull(homeConfiguration, "homeConfiguration must not be null");
        this.serverNodeId = Objects.requireNonNull(serverNodeId, "serverNodeId must not be null");
        this.worldName = Objects.requireNonNull(worldName, "worldName must not be null");
        this.economyBridge = Objects.requireNonNull(economyBridge, "economyBridge must not be null");
        this.features = Objects.requireNonNull(features, "features must not be null");
        this.windows = new CommandWindows(features::controlMenu);
    }

    public IslandUpgradeStoragePort islandUpgradePort() {
        return islandUpgradePort;
    }

    public @Nullable IslandWorthService worthService() {
        return features.worthService();
    }

    public void setBankruptcyService(@Nullable IslandBankruptcyService bankruptcyService) {
        this.bankruptcyService = bankruptcyService;
    }

    public @Nullable IslandBankruptcyService bankruptcyService() {
        return bankruptcyService;
    }

    public @Nullable IslandDimensionListener dimensionListener() {
        return features.dimensionListener();
    }

    public @Nullable IslandLimitService limitService() {
        return features.limitService();
    }

    public @Nullable IslandAntiAbuseService antiAbuseService() {
        return features.antiAbuseService();
    }

    public void setActivityFeedService(@Nullable ActivityFeedService activityFeedService) {
        this.activityFeedService = activityFeedService;
    }

    /** Where a command named after a window opens it. */
    public CommandWindows windows() {
        return windows;
    }

    /** Tells the tree what a trust grant carries, so the trust branch can make one. */
    public void useTemporaryAccess(
            TemporaryAccessConfiguration configuration,
            CurrentNodeProcessIdentity nodeProcessIdentity,
            com.uxplima.uxmskyblock.core.application.profile.ProfileTypes profileTypes) {
        this.temporaryAccessConfiguration = configuration;
        this.nodeProcessIdentity = nodeProcessIdentity;
        this.profileTypes = profileTypes;
    }

    /** Hands the island's feed to the command groups that change something worth writing down. */
    public void useActivityFeed(com.uxplima.uxmskyblock.core.application.activity.ActivityFeedService service) {
        this.activityFeedService = service;
    }

    /** Hands the inbox to the command groups that change a player's standing on an island. */
    public void useNotifications(com.uxplima.uxmskyblock.core.application.notification.NotificationService service) {
        this.notificationService = service;
    }

    private com.uxplima.uxmskyblock.bukkit.oneblock.@Nullable OneBlockPanel oneBlockPanel;
    private com.uxplima.uxmskyblock.bukkit.chunkblock.@Nullable ChunkBlockPanel chunkBlockPanel;
    com.uxplima.uxmskyblock.api.leaderboard.@Nullable LeaderboardMetrics leaderboards;

    com.uxplima.uxmskyblock.bukkit.lifecycle.@Nullable PlayerLifecycle playerLifecycle;

    java.util.function.Function<ProfileId, com.uxplima.uxmskyblock.core.domain.gamemode.GameModeType> gameModes =
            profile -> com.uxplima.uxmskyblock.core.domain.gamemode.GameModeType.SKYBLOCK;

    /** The mode a profile's island plays, for the commands whose rules depend on it. */
    public void useGameModes(
            java.util.function.Function<ProfileId, com.uxplima.uxmskyblock.core.domain.gamemode.GameModeType>
                    gameModes) {
        this.gameModes = java.util.Objects.requireNonNull(gameModes, "gameModes must not be null");
    }

    /** What carries out the operator's lifecycle rules on a leave, a kick and a reset. */
    public void useLifecycle(com.uxplima.uxmskyblock.bukkit.lifecycle.@Nullable PlayerLifecycle lifecycle) {
        this.playerLifecycle = lifecycle;
    }

    /** The boards {@code /is top} can show beyond level, worth and bank. */
    public void useLeaderboards(com.uxplima.uxmskyblock.api.leaderboard.@Nullable LeaderboardMetrics leaderboards) {
        this.leaderboards = leaderboards;
    }

    /** What the chunks command opens and unlocks, while the operator lets islands be ChunkBlock islands. */
    public void useChunkBlock(com.uxplima.uxmskyblock.bukkit.chunkblock.@Nullable ChunkBlockPanel panel) {
        this.chunkBlockPanel = panel;
    }

    private com.uxplima.uxmskyblock.bukkit.poseidon.@Nullable PoseidonLore poseidonLore;
    private com.uxplima.uxmskyblock.bukkit.parkour.@Nullable ParkourBoard parkourBoard;

    /** What {@code /is course} shows, while the operator lets islands be Parkour courses. */
    public void useParkourBoard(com.uxplima.uxmskyblock.bukkit.parkour.@Nullable ParkourBoard board) {
        this.parkourBoard = board;
    }

    /** What {@code /is lore} opens, while the operator lets islands be Poseidon islands. */
    public void usePoseidonLore(com.uxplima.uxmskyblock.bukkit.poseidon.@Nullable PoseidonLore lore) {
        this.poseidonLore = lore;
    }

    private com.uxplima.uxmskyblock.bukkit.trade.@Nullable Trades trades;

    private com.uxplima.uxmskyblock.bukkit.tradewinds.@Nullable CargoHolds cargoHolds;
    private com.uxplima.uxmskyblock.bukkit.tradewinds.@Nullable Harbour harbour;

    /** What {@code /is sail} and {@code /is market} open, while the operator lets islands be vessels. */
    public void useHarbour(com.uxplima.uxmskyblock.bukkit.tradewinds.@Nullable Harbour harbour) {
        this.harbour = harbour;
    }

    /** What {@code /is cargo} opens, while the operator lets islands be TradeWinds vessels. */
    public void useCargoHolds(com.uxplima.uxmskyblock.bukkit.tradewinds.@Nullable CargoHolds holds) {
        this.cargoHolds = holds;
    }

    /** What {@code /is trade} asks and answers, while the operator lets players trade. */
    public void useTrades(com.uxplima.uxmskyblock.bukkit.trade.@Nullable Trades trades) {
        this.trades = trades;
    }

    /** What {@code /is oneblock} opens, while the operator lets islands be OneBlock islands. */
    public void useOneBlock(com.uxplima.uxmskyblock.bukkit.oneblock.@Nullable OneBlockPanel panel) {
        this.oneBlockPanel = panel;
    }

    public void setVaultWindow(@Nullable IslandVaultWindow vaultWindow) {
        this.vaultWindow = vaultWindow;
    }

    public void setHomeService(@Nullable HomeService homeService) {
        this.homeService = homeService;
    }

    public void setNameService(@Nullable IslandNameService nameService) {
        this.nameService = nameService;
    }

    public @Nullable IslandNameService nameService() {
        return nameService;
    }

    public void setNetworkRouter(@Nullable IslandNetworkRouter networkRouter) {
        this.networkRouter = networkRouter;
    }

    public @Nullable IslandNetworkRouter networkRouter() {
        return networkRouter;
    }

    public void setRestoreService(@Nullable IslandRestoreService restoreService) {
        this.restoreService = restoreService;
    }

    public @Nullable IslandRestoreService restoreService() {
        return restoreService;
    }

    /** Hands the warp subsystem to the command that reaches it. */
    public void setWarpService(@Nullable IslandWarpService warpService) {
        this.warpService = warpService;
    }

    /** Hands the operator's effect lists to the commands whose milestones fire them. */
    public void setInteractionEffects(com.uxplima.uxmskyblock.bukkit.effect.@Nullable InteractionEffects effects) {
        this.interactionEffects = effects;
    }

    /** Hands the operator's anti abuse rules to the commands that enforce them. */
    public void setAntiAbuseConfiguration(
            com.uxplima.uxmskyblock.bukkit.config.@Nullable AntiAbuseConfiguration configuration) {
        this.antiAbuseConfiguration = configuration;
    }

    /** Hands the operator's biome rules to the command that applies them. */
    public void setBiomeConfiguration(
            com.uxplima.uxmskyblock.bukkit.config.@Nullable BiomeConfiguration configuration) {
        this.biomeConfiguration = configuration;
    }

    /** Hands the operator's wait between two rescans of one island to the command that runs them. */
    public void setRecalculationCooldown(java.time.Duration cooldown) {
        this.recalculationCooldown = java.util.Objects.requireNonNull(cooldown, "cooldown must not be null");
    }

    /** What makes an owner's own roles, and how many the operator lets one make. */
    public void useRoleShaper(
            com.uxplima.uxmskyblock.core.application.membership.@Nullable IslandRoleShaper shaper,
            com.uxplima.uxmskyblock.bukkit.config.RoleConfiguration configuration) {
        this.roleShaper = shaper;
        this.roleConfiguration = java.util.Objects.requireNonNull(configuration, "configuration must not be null");
    }

    /** The windows the feed, the guestbook, the bookmarks and the alliances are shown in. */
    volatile com.uxplima.uxmskyblock.bukkit.menu.@Nullable BorderWindow borderWindow;

    /** Hands {@code /is border} the window it opens. */
    public void useBorderWindow(com.uxplima.uxmskyblock.bukkit.menu.@Nullable BorderWindow window) {
        this.borderWindow = window;
    }

    public void useSocialWindows(com.uxplima.uxmskyblock.bukkit.menu.@Nullable SocialWindows windows) {
        this.socialWindows = windows;
    }

    /** Whether a kind of island can be made on this server: its world is loaded. */
    volatile java.util.function.Predicate<com.uxplima.uxmskyblock.core.domain.preset.StarterPreset> playableKinds =
            preset -> true;

    /** Hands the commands which kinds of island this server can make. */
    public void usePlayableKinds(
            java.util.function.Predicate<com.uxplima.uxmskyblock.core.domain.preset.StarterPreset> playable) {
        this.playableKinds = java.util.Objects.requireNonNull(playable, "playable must not be null");
    }

    /** How a player picks a kind of island, and the window the kinds are shown in. */
    public void usePresets(
            com.uxplima.uxmskyblock.bukkit.config.PresetChoices choices,
            com.uxplima.uxmskyblock.bukkit.menu.@Nullable PresetList list) {
        this.presetChoices = java.util.Objects.requireNonNull(choices, "choices must not be null");
        this.presetList = list;
    }

    /** What runs once a bank move lands: the bank's windows drawn again with what it left. */
    volatile java.util.function.BiConsumer<org.bukkit.entity.Player, String> bankMoved = (player, currency) -> {};

    /** Hands the bank what to do once a move lands, with the player and the currency it moved. */
    public void useBankMoved(java.util.function.BiConsumer<org.bukkit.entity.Player, String> moved) {
        this.bankMoved = java.util.Objects.requireNonNull(moved, "moved must not be null");
    }

    /** The window the homes are listed in, rather than lines in chat. */
    public void useHomeList(com.uxplima.uxmskyblock.bukkit.menu.@Nullable HomeList homeList) {
        this.homeList = homeList;
    }

    /** The window a leaderboard is shown in, rather than lines in chat. */
    public void useTopList(com.uxplima.uxmskyblock.bukkit.menu.@Nullable TopList topList) {
        this.topList = topList;
    }

    /** Hands the directory window to the browse command, so a warp's icon is seen. */
    public void setWarpBrowseMenu(com.uxplima.uxmskyblock.bukkit.menu.@Nullable IslandWarpBrowseMenu warpBrowseMenu) {
        this.warpBrowseMenu = warpBrowseMenu;
    }

    /** Hands the social subsystem to the commands that reach it. */
    public void setSocialService(@Nullable IslandSocialService socialService) {
        this.socialService = socialService;
    }

    /** Hands the alliance subsystem to the command that reaches it. */
    public void setAllianceService(@Nullable IslandAllianceService allianceService) {
        this.allianceService = allianceService;
    }

    /** Hands the web map synchroniser to the create command, so a new island lands on the map. */
    public void useMarkerSynchroniser(@Nullable IslandMarkerSynchroniser markerSynchroniser) {
        this.markerSynchroniser = markerSynchroniser;
    }

    /** Hands the mission service to the level command, so finished missions count toward a level. */
    public void setMissionService(@Nullable IslandMissionService missionService) {
        this.missionService = missionService;
    }

    /** Hands the reward inbox to the command that opens it. */
    public void setRewardInboxService(@Nullable RewardInboxService rewardInboxService) {
        this.rewardInboxService = rewardInboxService;
    }

    public void setBackupService(@Nullable BackupService backupService) {
        this.backupService = backupService;
    }

    /**
     * Names the bucket a restore reads from.
     *
     * <p>The restore command used to write the name into its own source, so an operator who renamed
     * their bucket wrote backups to one place and restored from another that does not exist.
     */
    public void setBackupBucket(@Nullable StorageBucket backupBucket) {
        this.backupBucket = backupBucket;
    }

    /** The thing that makes the backups {@code /is admin restore} puts back. */
    public void setIslandBackupService(@Nullable IslandBackupService islandBackupService) {
        this.islandBackupService = islandBackupService;
    }

    /** Hands the whole database backup to the command that takes one. */
    public void setDatabaseBackupService(
            com.uxplima.uxmskyblock.core.application.backup.@Nullable DatabaseDisasterBackupService service) {
        this.databaseBackupService = service;
    }

    /** Who belongs to an island, and what they may do on it. */
    public void setMembershipService(@Nullable IslandMembershipService membershipService) {
        this.membershipService = membershipService;
    }

    /** Which season is running, how the last one went, and what it owes a player. */
    public void setSeasonService(@Nullable IslandSeasonService seasonService) {
        this.seasonService = seasonService;
    }

    /** The server's structure files, which {@code /is schematic} saves and pastes. */
    volatile java.util.function.Supplier<com.uxplima.uxmskyblock.bukkit.schematic.@Nullable IslandSchematics>
            schematics = () -> null;

    /** Hands {@code /is schematic} the server's structure files. */
    public void useSchematics(
            java.util.function.Supplier<com.uxplima.uxmskyblock.bukkit.schematic.@Nullable IslandSchematics>
                    schematics) {
        this.schematics = java.util.Objects.requireNonNull(schematics, "schematics must not be null");
    }

    /** Hands the doctor the checks it runs. */
    public void setHealthChecks(
            java.util.function.Supplier<java.util.List<com.uxplima.uxmlib.health.HealthCheck>> healthChecks) {
        this.healthChecks = java.util.Objects.requireNonNull(healthChecks, "healthChecks must not be null");
    }

    /** The files an operator may change while the server is running: catalogues and menus. */
    public void setReloader(@Nullable SkyblockReloader reloader) {
        this.reloader = reloader;
    }

    public @Nullable BackupService backupService() {
        return backupService;
    }

    /**
     * The names the operator gave the command and its branches, from {@code commands.conf}. Absent,
     * every word is the one the code ships.
     */
    private com.uxplima.uxmlib.command.annotation.@Nullable ConfiguredCommands commandNames;

    /** Reads the command's names from {@code commands.conf}, as {@link #register} does. */
    public void useCommandNames(com.uxplima.uxmlib.command.annotation.@Nullable ConfiguredCommands names) {
        this.commandNames = names;
    }

    public void register(JavaPlugin plugin) {
        java.nio.file.Path file = plugin.getDataFolder().toPath().resolve("commands.conf");
        if (!java.nio.file.Files.exists(file)) {
            plugin.saveResource("commands.conf", false);
        }
        this.commandNames = com.uxplima.uxmlib.command.annotation.ConfiguredCommands.load(file);
        ConfiguredCommandTree<CommandSourceStack> names = new ConfiguredCommandTree<>(this.commandNames);
        com.uxplima.uxmlib.command.annotation.ConfiguredCommands.Entry root = names.root();
        if (!root.enabled()) {
            // The operator turned the whole command off, which is how a server keeps only the menu.
            return;
        }
        CommandRegistrar.register(plugin, buildRoot().build(), "Main Skyblock command tree", names.rootAliases());
        java.util.Map<String, java.util.List<String>> localized = names.rootLocalized();
        if (!localized.isEmpty()) {
            // A root word for one language is an alias anyone can type, kept out of other readers' command lists.
            plugin.getServer()
                    .getPluginManager()
                    .registerEvents(
                            new com.uxplima.uxmlib.command.annotation.LocalizedAliasVisibility(
                                    localized, plugin.getName(), messages.locales(), plugin),
                            plugin);
        }
    }

    /** Whether {@code source} reads the language {@code tag} names. The console reads every one. */
    private boolean reads(CommandSourceStack source, String tag) {
        return !(source.getSender() instanceof org.bukkit.entity.Player player)
                || ConfiguredCommandTree.readerOf(tag, messages.locales().localeOf(player));
    }

    /**
     * The whole command tree, assembled but not registered.
     *
     * <p>Exposed so a test can hand it to Brigadier and ask whether a line parses. A guard that reads
     * the source instead can only guess at the shape, and one that guesses is believed until the day
     * a player types the line it guessed about.
     */
    public LiteralArgumentBuilder<CommandSourceStack> buildRoot() {
        ConfiguredCommandTree<CommandSourceStack> names = new ConfiguredCommandTree<>(commandNames, this::reads);
        LiteralArgumentBuilder<CommandSourceStack> root =
                names.apply(assembleRoot(new CommandGroupBuilder(this).build()));
        com.uxplima.uxmlib.command.annotation.ConfiguredCommands.Entry rootEntry = names.root();
        // The word a player types: the first alias when there is one, which is how /is is written.
        this.rootWord = rootEntry.aliases().isEmpty()
                ? rootEntry.name()
                : rootEntry.aliases().get(0);
        this.helpBranches = names.branches();
        java.util.Map<String, String> typed = new java.util.HashMap<>();
        for (ConfiguredCommandTree.Branch<CommandSourceStack> branch : helpBranches) {
            typed.put(branch.key(), branch.name());
        }
        String rootTyped = this.rootWord;
        this.commandLines = line -> {
            int space = line.indexOf(' ');
            String first = space < 0 ? line : line.substring(0, space);
            String rest = space < 0 ? "" : line.substring(space);
            return rootTyped + " " + typed.getOrDefault(first, first) + rest;
        };
        // A catalogue line names a command as <cmd:sethome>, and reads it under the operator's words.
        messages.provider().useCommandLines(line -> "/" + typed(line));
        return root;
    }

    /** How a command line written with the code's own keys is typed under the operator's words. */
    private java.util.function.UnaryOperator<String> commandLines = line -> ConfiguredCommandTree.ROOT + " " + line;

    /**
     * {@code keyedLine}, a branch key and its arguments such as {@code warp create Market}, as a player
     * types it on this server, without the slash: the root and the branch under whatever names
     * {@code commands.conf} gives them.
     *
     * <p>Buttons ran {@code is shop} and {@code is warp create}, and an operator who renamed the root or
     * a branch, as every command may be, found every one of them answered as an unknown command.
     */
    public String typed(String keyedLine) {
        return commandLines.apply(
                java.util.Objects.requireNonNull(keyedLine, "keyedLine").strip());
    }

    /** The word the root answers to, as {@code /is help} names it. */
    private String rootWord = ConfiguredCommandTree.ROOT;

    /** Every branch as the operator named it, which is what {@code /is help} lists. */
    private java.util.List<ConfiguredCommandTree.Branch<CommandSourceStack>> helpBranches = java.util.List.of();

    private static final int HELP_PAGE = 10;

    /** The vault window under whichever word the caller typed. */
    private LiteralArgumentBuilder<CommandSourceStack> vaultBranch(String verb) {
        return Cmd.literal(verb)
                .executes(ctx -> executeVault(ctx, 1))
                .then(Cmd.literal("log")
                        .executes(ctx -> executeVaultLog(ctx, DEFAULT_VAULT_LOG_LINES))
                        .then(Cmd.argument("lines", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1))
                                .executes(ctx -> executeVaultLog(
                                        ctx,
                                        com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "lines")))))
                .then(Cmd.argument("page", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1))
                        .executes(ctx -> executeVault(
                                ctx, com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "page"))));
    }

    /**
     * Puts a verb behind the permission the catalogue publishes for it.
     *
     * <p>The catalogue registered eighteen nodes with the server and twelve of them were read
     * nowhere. An operator could take {@code uxmskyblock.island.create} off a group, see it taken
     * off in their permission plugin, and watch that group go on making islands.
     *
     * <p>Every one of these ships as true, so a server that grants nothing by hand sees no change
     * at all. What changes is that taking one away now does something.
     */
    private static LiteralArgumentBuilder<CommandSourceStack> gated(
            LiteralArgumentBuilder<CommandSourceStack> verb, CatalogPermissions permission) {
        return verb.requires(src -> src.getSender().hasPermission(permission.node()));
    }

    private LiteralArgumentBuilder<CommandSourceStack> assembleRoot(CommandGroupBuilder.CommandGroups groups) {
        LiteralArgumentBuilder<CommandSourceStack> root = Cmd.literal("island")
                .executes(this::executeRoot)
                .then(Cmd.literal("help")
                        .executes(ctx -> executeHelp(ctx, 1))
                        .then(Cmd.argument("page", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1))
                                .executes(ctx -> executeHelp(
                                        ctx,
                                        com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "page")))))
                .then(Cmd.literal("menu").executes(this::executeMenu))
                .then(groups.mechanicsCommands().buildMissions())
                .then(groups.mechanicsCommands().buildChallenges())
                .then(Cmd.literal("oneblock").executes(this::executeOneBlock))
                .then(Cmd.literal("lore").executes(this::executeLore))
                .then(Cmd.literal("trade")
                        .then(Cmd.argument("player", StringArgumentType.word()).executes(this::executeTrade)))
                .then(Cmd.literal("cargo").executes(this::executeCargo))
                .then(Cmd.literal("sail").executes(ctx -> executeHarbour(ctx, true)))
                .then(Cmd.literal("market").executes(ctx -> executeHarbour(ctx, false)))
                .then(Cmd.literal("course").executes(this::executeCourse))
                .then(Cmd.literal("chunks")
                        .executes(ctx -> executeChunks(ctx, false))
                        .then(Cmd.literal("unlock").executes(ctx -> executeChunks(ctx, true))))
                .then(groups.mechanicsCommands().buildBorder())
                .then(groups.mechanicsCommands().buildBounds())
                .then(groups.progressionCommands().buildLevel())
                .then(groups.progressionCommands().buildRecalc())
                .then(groups.progressionCommands().buildWorth())
                .then(groups.progressionCommands().buildValue())
                .then(groups.lifecycleCommands().buildReset())
                .then(gated(groups.lifecycleCommands().buildDelete(), CatalogPermissions.ISLAND_DELETE))
                .then(gated(groups.lifecycleCommands().buildCreate(), CatalogPermissions.ISLAND_CREATE))
                .then(gated(groups.navigationCommands().buildHome(), CatalogPermissions.ISLAND_HOME))
                .then(groups.navigationCommands().buildGo())
                .then(groups.navigationCommands().buildVisit())
                .then(groups.navigationCommands().buildNether())
                .then(groups.navigationCommands().buildEnd())
                .then(groups.mechanicsCommands().buildLimits())
                .then(groups.mechanicsCommands().buildQuarantine())
                .then(groups.mechanicsCommands().buildBooster())
                .then(groups.navigationCommands().buildSetSpawn())
                .then(gated(groups.homeCommands().buildSetHome(), CatalogPermissions.ISLAND_SET_HOME))
                .then(gated(groups.homeCommands().buildTravelHome(), CatalogPermissions.ISLAND_HOME))
                .then(groups.homeCommands().buildNamedHome())
                .then(groups.homeCommands().buildDeleteHome())
                .then(groups.activityCommands().build())
                .then(groups.warpCommands().build())
                .then(groups.warpCommands().buildExplore("explore"))
                .then(groups.warpCommands().buildExplore("warps"))
                .then(groups.socialCommands().buildGuestbook())
                .then(groups.socialCommands().buildRate())
                .then(groups.socialCommands().buildBookmarks())
                .then(groups.allianceCommands().build())
                // The names the documents publish for branches that already exist under another
                // word: /is ally, /is disband, /is settings, and /is explore beside /is warps.
                .then(groups.allianceCommands().buildAlias("ally"))
                .then(gated(groups.lifecycleCommands().buildDisband(), CatalogPermissions.ISLAND_DELETE))
                // /is settings opened the island menu, not its settings.
                .then(Cmd.literal("settings")
                        .executes(ctx -> windows.openOr(ctx, "island-settings", this::executeMenu)))
                .then(groups.rewardCommands().build())
                .then(groups.flagCommands().build())
                .then(gated(groups.visitorCommands().buildBan(), CatalogPermissions.ISLAND_BAN))
                .then(gated(groups.visitorCommands().buildUnban(), CatalogPermissions.ISLAND_UNBAN))
                .then(groups.visitorCommands().buildBans())
                .then(groups.visitorCommands().buildLock())
                .then(groups.visitorCommands().buildUnlock())
                .then(gated(groups.membershipCommands().buildInvite(), CatalogPermissions.ISLAND_INVITE))
                .then(groups.membershipCommands().buildAccept())
                .then(groups.membershipCommands().buildDeny())
                .then(gated(groups.membershipCommands().buildKick(), CatalogPermissions.ISLAND_KICK))
                .then(groups.membershipCommands().buildLeave())
                .then(groups.membershipCommands().buildMembers())
                .then(groups.membershipCommands().buildRole())
                .then(groups.membershipCommands().buildPermissions())
                .then(groups.infoCommands().buildInfo())
                .then(groups.chatCommands().buildAllianceChat())
                .then(groups.chatCommands().buildAllianceChatAlias())
                .then(groups.seasonCommands().buildSeason())
                .then(groups.reloadCommands().buildReload())
                .then(groups.doctorCommands().buildDoctor())
                .then(groups.schematicCommands().build())
                .then(gated(groups.upgradeCommands().build(), CatalogPermissions.ISLAND_UPGRADE))
                .then(groups.shopCommands().build())
                // The menu file and the documents both say upgrades; a player typing the singular
                // should not be told there is no such command.
                .then(gated(groups.upgradeCommands().buildUnder("upgrade"), CatalogPermissions.ISLAND_UPGRADE))
                .then(groups.trustCommands().buildTrust())
                .then(groups.trustCommands().buildUntrust())
                .then(groups.trustCommands().buildTrusted())
                .then(vaultBranch("vault"))
                // The design document publishes /is chest for the same window. A command an
                // operator reads about and types is either there or the document is wrong, and the
                // command is cheaper than the correction.
                .then(vaultBranch("chest"))
                .then(groups.warpCommands().buildWarpLock())
                .then(groups.warpCommands().buildWarpUnlock())
                .then(groups.lifecycleCommands().buildRename())
                .then(groups.adminCommands().buildRestore())
                .then(gated(groups.bankCommands().build(), CatalogPermissions.ISLAND_BANK))
                .then(gated(groups.progressionCommands().buildBiome(), CatalogPermissions.ISLAND_BIOME))
                .then(gated(groups.progressionCommands().buildTop(), CatalogPermissions.ISLAND_TOP))
                .then(Cmd.literal("profile")
                        .then(Cmd.literal("switch")
                                .then(Cmd.argument("profileId", StringArgumentType.word())
                                        .executes(this::executeProfileSwitch))))
                .then(groups.chatCommands().buildChat())
                .then(groups.chatCommands().buildChatAlias())
                .then(groups.chatCommands().buildSpy())
                .then(groups.adminCommands().buildAdmin());
        return root;
    }

    private void sendFeedback(Audience audience, Component component) {
        if (audience instanceof Player player) {
            schedulerPort.onEntity(new PlayerUuid(player.getUniqueId()), () -> {
                if (player.isOnline()) {
                    player.sendMessage(component);
                }
            });
        } else {
            audience.sendMessage(component);
        }
    }

    private void send(Audience audience, String key) {
        sendFeedback(audience, messages.render(audience, key));
    }

    private void send(
            Audience audience, String key, net.kyori.adventure.text.minimessage.tag.resolver.TagResolver... resolvers) {
        sendFeedback(audience, messages.render(audience, key, resolvers));
    }

    private int executeRoot(CommandContext<CommandSourceStack> ctx) {
        if (ctx.getSource().getSender() instanceof Player player && features.controlMenu() != null) {
            features.controlMenu().open(player);
            return Cmd.OK;
        }
        return executeHelp(ctx, 1);
    }

    private int executeOneBlock(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            send(ctx.getSource().getSender(), "error.players_only");
            return Cmd.OK;
        }
        com.uxplima.uxmskyblock.bukkit.oneblock.OneBlockPanel panel = this.oneBlockPanel;
        if (panel == null) {
            send(player, "oneblock.not_oneblock");
            return Cmd.OK;
        }
        panel.open(player);
        return Cmd.OK;
    }

    private int executeCourse(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            send(ctx.getSource().getSender(), "error.players_only");
            return Cmd.OK;
        }
        com.uxplima.uxmskyblock.bukkit.parkour.ParkourBoard board = this.parkourBoard;
        if (board == null) {
            send(player, "parkour.not_course");
            return Cmd.OK;
        }
        board.show(player);
        return Cmd.OK;
    }

    private int executeLore(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            send(ctx.getSource().getSender(), "error.players_only");
            return Cmd.OK;
        }
        com.uxplima.uxmskyblock.bukkit.poseidon.PoseidonLore lore = this.poseidonLore;
        if (lore == null) {
            send(player, "poseidon.not_poseidon");
            return Cmd.OK;
        }
        lore.open(player);
        return Cmd.OK;
    }

    private int executeHarbour(CommandContext<CommandSourceStack> ctx, boolean sailing) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            send(ctx.getSource().getSender(), "error.players_only");
            return Cmd.OK;
        }
        com.uxplima.uxmskyblock.bukkit.tradewinds.Harbour open = this.harbour;
        if (open == null) {
            send(player, "tradewinds.hold.not_vessel");
            return Cmd.OK;
        }
        if (sailing) {
            open.sail(player);
        } else {
            open.market(player);
        }
        return Cmd.OK;
    }

    private int executeCargo(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            send(ctx.getSource().getSender(), "error.players_only");
            return Cmd.OK;
        }
        com.uxplima.uxmskyblock.bukkit.tradewinds.CargoHolds holds = this.cargoHolds;
        if (holds == null) {
            send(player, "tradewinds.hold.not_vessel");
            return Cmd.OK;
        }
        holds.open(player);
        return Cmd.OK;
    }

    private int executeTrade(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            send(ctx.getSource().getSender(), "error.players_only");
            return Cmd.OK;
        }
        com.uxplima.uxmskyblock.bukkit.trade.Trades running = this.trades;
        if (running == null) {
            send(player, "trade.disabled");
            return Cmd.OK;
        }
        String name = StringArgumentType.getString(ctx, "player");
        running.ask(player, player.getServer().getPlayerExact(name));
        return Cmd.OK;
    }

    private int executeChunks(CommandContext<CommandSourceStack> ctx, boolean unlock) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            send(ctx.getSource().getSender(), "error.players_only");
            return Cmd.OK;
        }
        com.uxplima.uxmskyblock.bukkit.chunkblock.ChunkBlockPanel panel = this.chunkBlockPanel;
        if (panel == null) {
            send(player, "chunkblock.not_chunkblock");
        } else if (unlock) {
            panel.unlockFaced(player);
        } else {
            panel.open(player);
        }
        return Cmd.OK;
    }

    private int executeMenu(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            send(ctx.getSource().getSender(), "error.players_only");
            return Cmd.OK;
        }
        if (features.controlMenu() != null) {
            features.controlMenu().open(player);
        } else {
            send(player, "menu.not_enabled");
        }
        return Cmd.OK;
    }

    /**
     * Lists the commands the sender can use, under the words the operator gave them, a page at a time.
     *
     * <p>It was a list of twenty nine lines in the catalogue, and the command had seventy eight
     * branches: a player never learned of invite, members, warp or shop from it, and a renamed word
     * still printed as the shipped one. Each line is now drawn from the command the server registered,
     * with its words from the reader's catalogue.
     */
    private int executeHelp(CommandContext<CommandSourceStack> ctx, int page) {
        CommandSender sender = ctx.getSource().getSender();
        java.util.List<ConfiguredCommandTree.Branch<CommandSourceStack>> usable = new java.util.ArrayList<>();
        String helpWord = "help";
        for (ConfiguredCommandTree.Branch<CommandSourceStack> branch : helpBranches) {
            if (branch.key().equals("help")) {
                helpWord = branch.name();
            }
            // A branch with no help words is not listed. The catalogue gives none to a second word
            // for another branch, such as chest for the vault, so a command is one line.
            if (messages.has("help.commands." + branch.key() + ".description")
                    && branch.requirement().test(ctx.getSource())) {
                usable.add(branch);
            }
        }
        int pages = Math.max(1, (usable.size() + HELP_PAGE - 1) / HELP_PAGE);
        int shown = Math.min(Math.max(1, page), pages);
        send(
                sender,
                "help.header",
                Placeholder.unparsed("page", Integer.toString(shown)),
                Placeholder.unparsed("pages", Integer.toString(pages)));
        for (ConfiguredCommandTree.Branch<CommandSourceStack> branch :
                usable.subList((shown - 1) * HELP_PAGE, Math.min(usable.size(), shown * HELP_PAGE))) {
            String usage = "help.commands." + branch.key() + ".usage";
            Component usageLine = messages.has(usage)
                            && !messages.provider().getRaw(usage, "en").isBlank()
                    ? Component.space().append(messages.renderPlain(sender, usage))
                    : Component.empty();
            send(
                    sender,
                    "help.entry",
                    Placeholder.unparsed("root", rootWord),
                    Placeholder.unparsed("name", branch.name()),
                    Placeholder.component("usage", usageLine),
                    Placeholder.component(
                            "description",
                            messages.renderPlain(sender, "help.commands." + branch.key() + ".description")));
        }
        if (shown < pages) {
            send(
                    sender,
                    "help.next_page",
                    Placeholder.unparsed("root", rootWord),
                    Placeholder.unparsed("help", helpWord),
                    Placeholder.unparsed("page", Integer.toString(shown + 1)));
        }
        return Cmd.OK;
    }

    private int executeVault(CommandContext<CommandSourceStack> ctx, int page) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            send(ctx.getSource().getSender(), "error.players_only");
            return Cmd.OK;
        }
        IslandVaultWindow window = this.vaultWindow;
        if (window == null) {
            send(player, "vault.disabled");
            return Cmd.OK;
        }
        window.open(player, page);
        return Cmd.OK;
    }

    private int executeVaultLog(CommandContext<CommandSourceStack> ctx, int lines) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            send(ctx.getSource().getSender(), "error.players_only");
            return Cmd.OK;
        }
        IslandVaultWindow window = this.vaultWindow;
        if (window == null) {
            send(player, "vault.disabled");
            return Cmd.OK;
        }
        window.showLog(player, lines);
        return Cmd.OK;
    }

    private int executeProfileSwitch(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            send(ctx.getSource().getSender(), "error.players_only");
            return Cmd.OK;
        }
        String rawProf = StringArgumentType.getString(ctx, "profileId");
        try {
            UUID profUuid = UUID.fromString(rawProf);
            sessionCoordinator.switchProfile(player, new ProfileId(profUuid));
        } catch (IllegalArgumentException e) {
            send(player, "profile.invalid_uuid");
        }
        return Cmd.OK;
    }
}
