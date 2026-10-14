package com.uxplima.uxmskyblock.bukkit.command;

import com.uxplima.uxmskyblock.bukkit.bootstrap.SkyblockReloader;
import com.uxplima.uxmskyblock.bukkit.config.TemporaryAccessConfiguration;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.bukkit.vault.IslandVaultWindow;
import com.uxplima.uxmskyblock.bukkit.webmap.IslandMarkerSynchroniser;
import com.uxplima.uxmskyblock.core.application.activity.ActivityFeedService;
import com.uxplima.uxmskyblock.core.application.alliance.IslandAllianceService;
import com.uxplima.uxmskyblock.core.application.backup.BackupService;
import com.uxplima.uxmskyblock.core.application.backup.IslandBackupService;
import com.uxplima.uxmskyblock.core.application.bank.IslandBankruptcyService;
import com.uxplima.uxmskyblock.core.application.home.HomeService;
import com.uxplima.uxmskyblock.core.application.membership.IslandMembershipService;
import com.uxplima.uxmskyblock.core.application.mission.IslandMissionService;
import com.uxplima.uxmskyblock.core.application.name.IslandNameService;
import com.uxplima.uxmskyblock.core.application.network.IslandNetworkRouter;
import com.uxplima.uxmskyblock.core.application.reward.RewardInboxService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.season.IslandSeasonService;
import com.uxplima.uxmskyblock.core.application.snapshot.IslandRestoreService;
import com.uxplima.uxmskyblock.core.application.social.IslandSocialService;
import com.uxplima.uxmskyblock.core.application.warp.IslandWarpService;
import com.uxplima.uxmskyblock.core.domain.access.CurrentNodeProcessIdentity;
import com.uxplima.uxmskyblock.core.domain.storage.StorageBucket;
import org.jspecify.annotations.Nullable;

/**
 * What the island command reaches that the server hands it after it is built: the services and windows a module brings
 * when it is on, each with the setter the wiring calls. The verbs live in {@link IslandCommandTree}, which extends this,
 * and in the groups {@link CommandGroupBuilder} builds from these fields. Split out of the tree so neither file is over
 * the size the standards draw a line at; the fields keep no modifier so the groups in this package read them directly.
 */
abstract class IslandCommandSettings {

    /** The verbs each game mode adds to the island command, and what they reach. */
    final ModeVerbs modes;

    IslandCommandSettings(SchedulerPort schedulerPort, PlayerSessionCoordinator sessionCoordinator, Messages messages) {
        this.modes = new ModeVerbs(schedulerPort, sessionCoordinator, messages);
    }

    volatile @Nullable HomeService homeService;

    volatile @Nullable ActivityFeedService activityFeedService;

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

    /** The windows the feed, the guestbook, the bookmarks and the alliances are shown in. */
    volatile com.uxplima.uxmskyblock.bukkit.menu.@Nullable BorderWindow borderWindow;

    volatile com.uxplima.uxmskyblock.bukkit.menu.@Nullable ActivityWindow activityWindow;

    /** Whether a kind of island can be made on this server: its world is loaded. */
    volatile java.util.function.Predicate<com.uxplima.uxmskyblock.core.domain.preset.StarterPreset> playableKinds =
            preset -> true;

    /** What runs once a bank move lands: the bank's windows drawn again with what it left. */
    volatile java.util.function.BiConsumer<org.bukkit.entity.Player, String> bankMoved = (player, currency) -> {};

    /** The server's structure files, which {@code /is schematic} saves and pastes. */
    volatile java.util.function.Supplier<com.uxplima.uxmskyblock.bukkit.schematic.@Nullable IslandSchematics>
            schematics = () -> null;

    volatile @Nullable IslandVaultWindow vaultWindow;

    public void setBankruptcyService(@Nullable IslandBankruptcyService bankruptcyService) {
        this.bankruptcyService = bankruptcyService;
    }

    public @Nullable IslandBankruptcyService bankruptcyService() {
        return bankruptcyService;
    }

    public void setActivityFeedService(@Nullable ActivityFeedService activityFeedService) {
        this.activityFeedService = activityFeedService;
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
    /** Hands {@code /is border} the window it opens. */
    public void useBorderWindow(com.uxplima.uxmskyblock.bukkit.menu.@Nullable BorderWindow window) {
        this.borderWindow = window;
    }
    /** Hands {@code /is activity} the window it opens. */
    public void useActivityWindow(com.uxplima.uxmskyblock.bukkit.menu.@Nullable ActivityWindow window) {
        this.activityWindow = window;
    }
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

    /** What the chunks command opens and unlocks, while the operator lets islands be ChunkBlock islands. */
    public void useChunkBlock(com.uxplima.uxmskyblock.bukkit.chunkblock.@Nullable ChunkBlockPanel panel) {
        modes.useChunkBlock(panel);
    }
    /** {@code /is plots}, while Brix is enabled. */
    public void useBrixGallery(com.uxplima.uxmskyblock.bukkit.brix.@Nullable BrixGallery gallery) {
        modes.useBrixGallery(gallery);
    }
    /** {@code /is values}: what each block is worth to an island. */
    public void useBlockValues(com.uxplima.uxmskyblock.bukkit.worth.@Nullable BlockValues values) {
        modes.useBlockValues(values);
    }
    /** {@code /is visitfee}, and the fee {@code /is visit} asks for. */
    public void useVisitFees(com.uxplima.uxmskyblock.bukkit.visitor.@Nullable VisitFees fees) {
        modes.useVisitFees(fees);
    }
    /** {@code /is fly}. */
    public void useIslandFly(com.uxplima.uxmskyblock.bukkit.fly.@Nullable IslandFly fly) {
        modes.useIslandFly(fly);
    }

    public void useBrixContests(com.uxplima.uxmskyblock.bukkit.brix.@Nullable BrixContestPlay contests) {
        modes.useBrixContests(contests);
    }
    /** What {@code /is plot reset} clears, while islands can be Brix plots. */
    public void usePlotReset(com.uxplima.uxmskyblock.bukkit.brix.@Nullable PlotReset reset) {
        modes.usePlotReset(reset);
    }
    /** What {@code /is plot time} and {@code /is plot weather} set, while islands can be Brix plots. */
    public void usePlotSky(com.uxplima.uxmskyblock.bukkit.brix.@Nullable PlotSkyCommands sky) {
        modes.usePlotSky(sky);
    }
    /** What {@code /is courses} opens, while islands can be Parkour courses. */
    public void useParkourCourses(com.uxplima.uxmskyblock.bukkit.parkour.@Nullable ParkourCourses courses) {
        modes.useParkourCourses(courses);
    }
    /** What {@code /is coursetag} and {@code /is courserate} do, while islands can be Parkour courses. */
    public void useParkourTagging(com.uxplima.uxmskyblock.bukkit.parkour.@Nullable ParkourTagging tagging) {
        modes.useParkourTagging(tagging);
    }
    /** What {@code /is course} shows, while the operator lets islands be Parkour courses. */
    public void useParkourBoard(com.uxplima.uxmskyblock.bukkit.parkour.@Nullable ParkourBoard board) {
        modes.useParkourBoard(board);
    }
    /** What {@code /is lore} opens, while the operator lets islands be Poseidon islands. */
    public void usePoseidonLore(com.uxplima.uxmskyblock.bukkit.poseidon.@Nullable PoseidonLore lore) {
        modes.usePoseidonLore(lore);
    }
    /** What {@code /is sail} and {@code /is market} open, while the operator lets islands be vessels. */
    public void useHarbour(com.uxplima.uxmskyblock.bukkit.tradewinds.@Nullable Harbour harbour) {
        modes.useHarbour(harbour);
    }
    /** What {@code /is boxbuy} and {@code /is boxside} do, while the operator lets islands be Boxed. */
    public void useBoxedShop(com.uxplima.uxmskyblock.bukkit.boxed.@Nullable BoxedShop shop) {
        modes.useBoxedShop(shop);
    }
    /** What {@code /is plunder} does, while the operator lets islands be vessels. */
    public void useBoarding(com.uxplima.uxmskyblock.bukkit.tradewinds.@Nullable Boarding boarding) {
        modes.useBoarding(boarding);
    }
    /** What {@code /is contracts} opens, while the operator lets islands be vessels. */
    public void useContracts(com.uxplima.uxmskyblock.bukkit.tradewinds.@Nullable ContractDesk contracts) {
        modes.useContracts(contracts);
    }
    /** What {@code /is cargo} opens, while the operator lets islands be TradeWinds vessels. */
    public void useCargoHolds(com.uxplima.uxmskyblock.bukkit.tradewinds.@Nullable CargoHolds holds) {
        modes.useCargoHolds(holds);
    }
    /** What {@code /is trade} asks and answers, while the operator lets players trade. */
    public void useTrades(com.uxplima.uxmskyblock.bukkit.trade.@Nullable Trades trades) {
        modes.useTrades(trades);
    }
    /** What {@code /is oneblock} opens, while the operator lets islands be OneBlock islands. */
    public void useOneBlock(com.uxplima.uxmskyblock.bukkit.oneblock.@Nullable OneBlockPanel panel) {
        modes.useOneBlock(panel);
    }
    /** What {@code /is visit} asks of the visit fee, or nothing while the server charges none. */
    com.uxplima.uxmskyblock.bukkit.visitor.@Nullable VisitFees visitFees() {
        return modes.visitFees();
    }
}
