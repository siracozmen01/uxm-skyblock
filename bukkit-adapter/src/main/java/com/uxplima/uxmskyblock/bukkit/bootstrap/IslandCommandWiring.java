package com.uxplima.uxmskyblock.bukkit.bootstrap;

import org.bukkit.plugin.java.JavaPlugin;

import com.uxplima.uxmskyblock.bukkit.command.IslandCommandTree;
import com.uxplima.uxmskyblock.bukkit.command.IslandFeatures;
import com.uxplima.uxmskyblock.bukkit.health.SkyblockHealth;
import com.uxplima.uxmskyblock.bukkit.world.IslandWorldCheck;
import com.uxplima.uxmskyblock.core.application.flag.IslandFlagService;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import com.uxplima.uxmskyblock.persistence.bootstrap.PersistenceBootstrap;

/**
 * The {@code /is} command tree and every service its branches reach.
 *
 * <p>Built once the integrations it hands to its branches exist: the menus, the placeholders, the
 * economy bridge and the router.
 */
final class IslandCommandWiring {

    private IslandCommandWiring() {}

    /** The island's level as the level command works it out: blocks, finished missions and the bank. */
    private static long levelOf(
            GameplayWiring gameplay,
            com.uxplima.uxmskyblock.core.domain.identity.IslandId islandId,
            com.uxplima.uxmskyblock.core.domain.identity.ProfileId profile) {
        var worth = gameplay.worthService();
        if (worth == null) {
            return 0;
        }
        var missions = gameplay.missionService();
        int finished = missions == null ? 0 : missions.countCompleted(islandId, profile);
        long bank = gameplay.bankService().getBalanceMinorUnits(profile).orElse(0L);
        return worth.calculateScore(islandId, finished, bank).calculatedLevel();
    }

    static IslandCommandTree build(
            IntegrationWiring integration,
            JavaPlugin plugin,
            ConfigurationWiring config,
            PersistenceBootstrap persistence,
            AuthorityWiring authority,
            GameplayWiring gameplay,
            Housekeeping housekeeping) {
        ServerNodeId serverNodeId = config.nodeConfig().nodeId();
        String worldName = config.nodeConfig().worldName();
        IslandCommandTree tree = new IslandCommandTree(
                gameplay.createIslandUseCase(),
                gameplay.locationService(),
                new IslandFlagService(persistence.islandStoragePort(), persistence.islandMutationLock()),
                gameplay.bankService(),
                persistence.islandUpgradeStoragePort(),
                gameplay.leaderboardService(),
                gameplay.biomeAdapter(),
                gameplay.presetCatalog(),
                gameplay.schematicEngine(),
                gameplay.protectionListener(),
                authority.sessionCoordinator(),
                gameplay.scheduler(),
                integration.messages(),
                config.homeConfig(),
                serverNodeId,
                worldName,
                integration.economyBridge(),
                IslandFeatures.builder()
                        .controlMenu(integration.controlMenu())
                        .chatService(gameplay.chatService())
                        .inactivityService(gameplay.inactivityService())
                        .freezeService(gameplay.freezeService())
                        .missionsMenu(gameplay.missionsMenu())
                        .boundaryService(gameplay.boundaryService())
                        .recycleService(gameplay.recycleService())
                        .resetMenu(gameplay.resetConfirmationMenu())
                        .worthService(gameplay.worthService())
                        .dimensionListener(gameplay.dimensionListener())
                        .limitService(gameplay.limitService())
                        .antiAbuseService(gameplay.antiAbuseService())
                        .boosterService(gameplay.boosterService())
                        .boosterMenu(gameplay.boosterMenu())
                        .upgradeService(gameplay.upgradeService())
                        .shopService(gameplay.shopService())
                        .shopMenu(gameplay.shopMenu())
                        // The grant subsystem was complete underneath and nothing could make a
                        // grant: the whole write side had no caller, so the check on every click
                        // asked about grants that could not exist.
                        .temporaryAccessService(
                                config.moduleSettings().isModuleEnabled("temporary-access")
                                        ? gameplay.temporaryAccessService()
                                        : null)
                        .build());
        // A button runs an island command under the operator's names for it, so the verbs need the tree.
        SkyblockMenuVerbs.register(integration.menuEngine(), integration.messages(), tree::typed);
        // The roles, what each may do and the role a member holds are windows, drawn again on a change.
        var roleWindows = new com.uxplima.uxmskyblock.bukkit.menu.RoleWindows(
                integration.menuEngine(),
                integration.messages(),
                authority.sessionCoordinator()::activeProfile,
                gameplay.locationService(),
                gameplay.scheduler(),
                tree::typed);
        roleWindows.register();
        tree.windows().useRoles(roleWindows::openRoles);
        gameplay.membershipService().whenRolesChanged(roleWindows::rolesChanged);
        // An owner makes roles of their own beside the four every island has, as many as config.conf allows.
        tree.useRoleShaper(
                new com.uxplima.uxmskyblock.core.application.membership.IslandRoleShaper(
                        persistence.islandStoragePort(),
                        persistence.islandMutationLock(),
                        gameplay.membershipService()::rolesChangedOn),
                config.roleConfig());
        // Every currency the bank keeps, its money among them, is a tile of the bank window with a window of its own.
        var economy = integration.economyBridge();
        var bankCurrencies = new com.uxplima.uxmskyblock.bukkit.menu.BankCurrencyList(
                integration.messages(),
                () -> config.bankConfig().money(),
                () -> economy.wallets()
                        .map(wallets -> wallets.currencies().stream()
                                .map(com.uxplima.uxmskyblock.bukkit.integration.economy.BankWallets.Kept::spec)
                                .toList())
                        .orElse(java.util.List.of()),
                tree::typed);
        bankCurrencies.useCarried((player, id) -> com.uxplima.uxmskyblock.bukkit.menu.BankCurrencyList.MONEY.equals(id)
                ? java.util.Optional.of(
                        economy.isEconomyAvailable()
                                ? Long.toString(
                                        (long) Math.floor(economy.balance(org.bukkit.Bukkit.getOfflinePlayer(player))))
                                : "0")
                : economy.wallets()
                        .flatMap(wallets -> wallets.carried(player, id))
                        .map(String::valueOf));
        bankCurrencies.useControl(integration.controlMenu());
        bankCurrencies.register(integration.menuEngine());
        integration.controlMenu().useCarried(bankCurrencies::carriedBy);
        tree.useBankMoved(bankCurrencies::moved);
        // The border is a window: shown or hidden, and the colour it is drawn in.
        var borderWindow =
                new com.uxplima.uxmskyblock.bukkit.menu.BorderWindow(integration.messages(), gameplay::boundaryService);
        borderWindow.useMenuEngine(integration.menuEngine());
        // And how the island's edge is drawn for everybody, which a role that may change settings chooses.
        var islandStore = persistence.islandStoragePort();
        borderWindow.useEdges(
                gameplay.islandEdges(),
                player -> authority
                        .sessionCoordinator()
                        .activeProfile(player)
                        .flatMap(profile -> islandStore
                                .findIslandIdByProfileId(profile)
                                .flatMap(islandStore::findIslandById)
                                .map(island -> new com.uxplima.uxmskyblock.bukkit.menu.BorderWindow.Own(
                                        island.id(),
                                        island.isOwner(profile)
                                                || island.hasPermission(
                                                        profile,
                                                        com.uxplima.uxmskyblock.core.domain.island.IslandPermission
                                                                .SETTINGS_MODIFY)))),
                gameplay.scheduler());
        tree.useBorderWindow(borderWindow);
        // The homes and every leaderboard are windows, one tile each, rather than lines in chat.
        var homeList = new com.uxplima.uxmskyblock.bukkit.menu.HomeList(tree::typed);
        homeList.useMenuEngine(integration.menuEngine());
        tree.useHomeList(homeList);
        var topList = new com.uxplima.uxmskyblock.bukkit.menu.TopList(tree::typed);
        topList.useMenuEngine(integration.menuEngine());
        tree.useTopList(topList);
        // The feed, the guestbook, the bookmarks and the alliances are windows, one tile per line.
        var socialWindows = new com.uxplima.uxmskyblock.bukkit.menu.SocialWindows(integration.messages(), tree::typed);
        socialWindows.useMenuEngine(integration.menuEngine());
        tree.useSocialWindows(socialWindows);
        // The feed is a window of its own, narrowed by member, kind and time, with the bank by member beside it.
        var activityWindow = new com.uxplima.uxmskyblock.bukkit.menu.ActivityWindow(integration.messages());
        activityWindow.useMenuEngine(integration.menuEngine());
        authority.sessionCoordinator().whenProfileLeft((player, left) -> activityWindow.forget(player.getUniqueId()));
        tree.useActivityWindow(activityWindow);
        // The kinds of island are a window, and a player with no island is met the way the operator chose.
        com.uxplima.uxmskyblock.bukkit.config.PresetChoices choices = gameplay.presetChoices();
        // A kind of a game mode the operator does not offer is not shown, and neither is one whose world this
        // server has not loaded: on Folia, Boxed and StrangerRealms.
        var worlds = com.uxplima.uxmskyblock.bukkit.world.PresetWorlds.ofServer(worldName);
        java.util.function.Predicate<com.uxplima.uxmskyblock.core.domain.preset.StarterPreset> offered =
                preset -> choices.offers(preset) && worlds.playable(preset);
        var presetList = new com.uxplima.uxmskyblock.bukkit.menu.PresetList(
                integration.messages(),
                () -> gameplay.presetCatalog().allPresets().stream()
                        .filter(offered)
                        .toList(),
                () -> choices,
                tree::typed);
        tree.usePlayableKinds(offered);
        presetList.useMenuEngine(integration.menuEngine());
        tree.usePresets(choices, presetList);
        integration.controlMenu().useNoIsland(player -> {
            switch (choices.whenNoIsland()) {
                case MENU -> {
                    if (!presetList.show(player)) {
                        player.performCommand(tree.typed("create"));
                    }
                }
                case CREATE ->
                    player.performCommand(tree.typed(
                            "create " + gameplay.presetCatalog().defaultPreset().id()));
                case HELP -> player.performCommand(tree.typed("help"));
            }
        });
        // The members window lists the members, one tile each, rather than sending the player to chat.
        new com.uxplima.uxmskyblock.bukkit.menu.MemberList(
                        integration.messages(),
                        authority.sessionCoordinator()::activeProfile,
                        gameplay.locationService(),
                        gameplay.membershipService(),
                        java.time.Clock.systemUTC())
                .register(integration.menuEngine().bindings(), roleWindows::pickRole);
        tree.useTemporaryAccess(
                config.temporaryAccessConfig(), authority.nodeProcessIdentity(), authority.profileTypes());
        // The inbox, its table, its ten categories and the delivery on join were all here and
        // nothing ever wrote a row, so "while you were away" was always empty.
        tree.useNotifications(gameplay.notificationService());
        // Nothing ever wrote an activity event, so every island's feed was empty for as long as the
        // server ran.
        tree.useActivityFeed(gameplay.activityFeedService());
        housekeeping.tellTheFeedWhenAMissionFinishes(gameplay);
        tree.setBankruptcyService(gameplay.bankruptcyService());
        tree.setHomeService(gameplay.homeService());
        tree.setVaultWindow(gameplay.vaultWindow());
        tree.useLifecycle(gameplay.playerLifecycle());
        tree.useGameModes(gameplay.gameModeHierarchyService()::modeOf);
        tree.useLeaderboards(gameplay.leaderboardMetrics());
        // A OneBlock island's standing, as the operator's menu file draws it and as placeholders.
        var oneBlockPanel = gameplay.oneBlockWiring().panel(integration.menuEngine());
        // A ChunkBlock island's chunks, as the operator's menu file draws them, the unlock and placeholders.
        var chunkBlockPanel = gameplay.chunkBlockWiring()
                .panel(
                        integration.menuEngine(),
                        persistence.islandStoragePort(),
                        gameplay.scheduler(),
                        integration.messages(),
                        authority.sessionCoordinator()::activeProfile,
                        (islandId, profile) -> levelOf(gameplay, islandId, profile));
        if (chunkBlockPanel != null) {
            // Chunks are paid for by their grades, out of what players carry and the island's bank.
            var purchase = gameplay.chunkBlockWiring()
                    .purchase(
                            persistence,
                            gameplay.bankService(),
                            integration.economyBridge(),
                            gameplay.accessService(),
                            gameplay.scheduler(),
                            integration.messages(),
                            authority.sessionCoordinator()::activeProfile,
                            (islandId, profile) -> levelOf(gameplay, islandId, profile),
                            config.nodeConfig().nodeId());
            chunkBlockPanel.usePurchase(purchase);
            // Each closed chunk the island can open next, as a window of its price.
            var chunkWindow = new com.uxplima.uxmskyblock.bukkit.chunkblock.ChunkWindow(
                    purchase,
                    gameplay.chunkBlockWiring().service(),
                    persistence.islandStoragePort(),
                    gameplay.scheduler(),
                    integration.messages(),
                    id -> integration.economyBridge().wallets().flatMap(wallets -> wallets.currency(id)),
                    authority.sessionCoordinator()::activeProfile,
                    (islandId, profile) -> levelOf(gameplay, islandId, profile));
            chunkWindow.useMenuEngine(integration.menuEngine());
            chunkBlockPanel.useWindow(chunkWindow);
            gameplay.chunkBlockWiring().useWindow(chunkWindow);
        }
        tree.useChunkBlock(chunkBlockPanel);
        integration.placeholderExpansion().useChunkBlock(chunkBlockPanel);
        tree.useOneBlock(oneBlockPanel);
        // A Parkour course's best times.
        if (gameplay.parkourWiring().enabled()) {
            tree.useParkourBoard(gameplay.parkourWiring().board());
        }
        // A Poseidon island's lore: a book on Java, a form on Bedrock, pages from the language files.
        if (gameplay.poseidonWiring().enabled()) {
            var forms = integration.bedrockFormService();
            tree.usePoseidonLore(new com.uxplima.uxmskyblock.bukkit.poseidon.PoseidonLore(
                    integration.messages(),
                    gameplay.scheduler(),
                    authority.sessionCoordinator()::activeProfile,
                    gameplay.gameModeHierarchyService()::modeOf,
                    forms::isBedrock,
                    forms.screen(),
                    org.bukkit.entity.Player::openBook));
        }
        integration.placeholderExpansion().useOneBlock(oneBlockPanel);
        if (gameplay.tradeWindsWiring().enabled()) {
            tree.useCargoHolds(gameplay.tradeWindsWiring().holds());
            gameplay.tradeWindsWiring().harbour().useForms(integration.bedrockFormService());
            gameplay.tradeWindsWiring().harbour().useMenuEngine(integration.menuEngine());
            gameplay.tradeWindsWiring().holds().useMenuEngine(integration.menuEngine());
            tree.useHarbour(gameplay.tradeWindsWiring().harbour());
        }
        if (gameplay.tradeWiring().enabled()) {
            tree.useTrades(gameplay.tradeWiring().trades());
        }
        tree.setActivityFeedService(gameplay.activityFeedService());
        tree.setNameService(gameplay.islandNameService());
        tree.setNetworkRouter(integration.networkRouter());
        tree.setRestoreService(gameplay.islandRestoreService());
        tree.setBackupService(gameplay.backupService());
        tree.setBackupBucket(gameplay.backupBucket());
        tree.setIslandBackupService(gameplay.islandBackupService());
        tree.setDatabaseBackupService(gameplay.databaseBackupService());
        tree.setMembershipService(gameplay.membershipService());
        tree.setSeasonService(gameplay.seasonService());
        // A reload reads the catalogues and the menus and nothing else: no service is re-bound and
        // no table is touched, because hot swapping a subsystem is how a plugin leaks classloaders
        // and leaves listeners behind.
        tree.setReloader(
                new SkyblockReloader(integration.messages().provider(), config.dataDir(), integration.menuEngine()));
        String islandWorld = config.nodeConfig().worldName();
        java.util.List<com.uxplima.uxmlib.health.HealthCheck> checks = java.util.List.of(
                SkyblockHealth.storage(persistence::databaseAnswers),
                SkyblockHealth.windows(
                        () -> integration.menuEngine().loadedSpecs().size()),
                SkyblockHealth.placeholders(
                        () -> integration.placeholderExpansion().isPublished()),
                SkyblockHealth.islandWorld(
                        () -> IslandWorldCheck.warningFor(
                                islandWorld, plugin.getServer().getWorld(islandWorld), plugin.getName()),
                        () -> plugin.getServer().getWorld(islandWorld) != null),
                SkyblockHealth.economy(() -> integration.economyBridge().isEconomyAvailable()));
        tree.setHealthChecks(() -> checks);
        tree.useSchematics(gameplay::islandSchematics);
        // Four subsystems that were running with no door. Every one of them had a service, a table
        // and a feature module, and no command a player could type.
        tree.setWarpService(gameplay.warpService());
        tree.setWarpBrowseMenu(gameplay.warpBrowseMenu());
        tree.setBiomeConfiguration(config.biomeConfig());
        tree.setRecalculationCooldown(config.levelConfig().recalculationCooldown());
        tree.setAntiAbuseConfiguration(config.antiAbuseConfig());
        tree.setInteractionEffects(config.effectsConfig());
        tree.setSocialService(gameplay.socialService());
        tree.setAllianceService(gameplay.allianceService());
        tree.setRewardInboxService(gameplay.rewardInboxService());
        tree.useMarkerSynchroniser(integration.markerSynchroniser());
        tree.setMissionService(gameplay.missionService());
        return tree;
    }
}
