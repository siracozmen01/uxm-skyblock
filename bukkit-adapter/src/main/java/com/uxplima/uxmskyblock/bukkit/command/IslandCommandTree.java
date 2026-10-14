package com.uxplima.uxmskyblock.bukkit.command;

import java.util.Objects;
import java.util.UUID;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.uxplima.uxmlib.command.Cmd;
import com.uxplima.uxmlib.command.CommandRegistrar;
import com.uxplima.uxmskyblock.bukkit.config.HomeConfiguration;
import com.uxplima.uxmskyblock.bukkit.dimension.IslandDimensionListener;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.integration.economy.SkyblockEconomyBridge;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.permission.CatalogPermissions;
import com.uxplima.uxmskyblock.bukkit.schematic.StarterSchematicEngine;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.core.application.antiabuse.IslandAntiAbuseService;
import com.uxplima.uxmskyblock.core.application.bank.IslandBankService;
import com.uxplima.uxmskyblock.core.application.biome.BiomeModificationPort;
import com.uxplima.uxmskyblock.core.application.flag.IslandFlagService;
import com.uxplima.uxmskyblock.core.application.island.CreateIslandUseCase;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.leaderboard.IslandLeaderboardService;
import com.uxplima.uxmskyblock.core.application.limit.IslandLimitService;
import com.uxplima.uxmskyblock.core.application.preset.StarterPresetCatalog;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.upgrade.IslandUpgradeStoragePort;
import com.uxplima.uxmskyblock.core.application.worth.IslandWorthService;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import org.jspecify.annotations.Nullable;

/**
 * Paper Brigadier command tree for {@code /island} and {@code /is}.
 *
 * <p>Coordinates subcommand groups: {@link IslandBankCommands}, {@link IslandChatCommands},
 * {@link IslandAdminCommands}, {@link IslandLifecycleCommands}, {@link IslandNavigationCommands},
 * {@link IslandProgressionCommands}, and {@link IslandMechanicsCommands}.
 */
public final class IslandCommandTree extends IslandCommandSettings {

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

    final ServerNodeId serverNodeId;
    final String worldName;
    final SkyblockEconomyBridge economyBridge;
    final IslandFeatures features;
    final CommandWindows windows;

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
        super(schedulerPort, sessionCoordinator, messages);
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

    public @Nullable IslandDimensionListener dimensionListener() {
        return features.dimensionListener();
    }

    public @Nullable IslandLimitService limitService() {
        return features.limitService();
    }

    public @Nullable IslandAntiAbuseService antiAbuseService() {
        return features.antiAbuseService();
    }

    /** Where a command named after a window opens it. */
    public CommandWindows windows() {
        return windows;
    }

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

    public void useSocialWindows(com.uxplima.uxmskyblock.bukkit.menu.@Nullable SocialWindows windows) {
        this.socialWindows = windows;
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

    private @Nullable ModeAdminCommands modeAdmin;

    /** What an operator does to the islands of each mode, registered with the island command as {@code /isadmin}. */
    public void useModeAdmin(ModeAdminCommands admin) {
        this.modeAdmin = java.util.Objects.requireNonNull(admin, "admin must not be null");
    }

    public void register(JavaPlugin plugin) {
        java.nio.file.Path file = plugin.getDataFolder().toPath().resolve("commands.conf");
        if (!java.nio.file.Files.exists(file)) {
            plugin.saveResource("commands.conf", false);
        }
        this.commandNames = com.uxplima.uxmlib.command.annotation.ConfiguredCommands.load(file);
        ModeAdminCommands admin = this.modeAdmin;
        if (admin != null) {
            // Its own command, there whether or not the island command is.
            admin.register(plugin, this.commandNames, this::reads);
        }
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
                .then(modes.vault("vault", () -> vaultWindow))
                // The design document publishes /is chest for the same window. A command an
                // operator reads about and types is either there or the document is wrong, and the
                // command is cheaper than the correction.
                .then(modes.vault("chest", () -> vaultWindow))
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
        modes.attach(root);
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

    private int executeHelp(CommandContext<CommandSourceStack> ctx, int page) {
        return new HelpPages(messages, this::send).show(ctx.getSource(), page, rootWord, helpBranches);
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
