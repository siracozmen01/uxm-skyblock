package com.uxplima.uxmskyblock.bukkit.command;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import org.bukkit.entity.Player;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.uxplima.uxmlib.command.Cmd;
import com.uxplima.uxmskyblock.bukkit.config.BiomeConfiguration;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.core.application.bank.IslandBankService;
import com.uxplima.uxmskyblock.core.application.biome.BiomeModificationPort;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.leaderboard.IslandLeaderboardService;
import com.uxplima.uxmskyblock.core.application.mission.IslandMissionService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.worth.IslandWorthService;
import com.uxplima.uxmskyblock.core.application.worth.RecalculationGate;
import com.uxplima.uxmskyblock.core.domain.biome.IslandBiome;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandPermission;
import com.uxplima.uxmskyblock.core.domain.leaderboard.LeaderboardEntry;
import org.jspecify.annotations.Nullable;

/**
 * Handles island progression and valuation commands:
 * /is level, /is worth, /is value, /is top, /is biome
 */
public final class IslandProgressionCommands {

    /** {@code /is top}: the leaderboard of a category or a metric, in its window or in chat, with the reader's own place. */
    private final TopVerbs topVerbs = new TopVerbs(this);

    /** {@code /is level} and its recalculation: the island's level said, and counted again block by block when asked. */
    private final LevelVerbs levelVerbs = new LevelVerbs(this);

    /** Where a command named after a window opens it. */
    private CommandWindows windows = CommandWindows.none();

    /** The window a board is shown in, looked up when one is asked for. */
    Supplier<com.uxplima.uxmskyblock.bukkit.menu.@Nullable TopList> topList = () -> null;

    void useTopList(Supplier<com.uxplima.uxmskyblock.bukkit.menu.@Nullable TopList> topList) {
        this.topList = Objects.requireNonNull(topList, "topList must not be null");
    }

    void useWindows(CommandWindows windows) {
        this.windows = java.util.Objects.requireNonNull(windows, "windows must not be null");
    }

    final IslandLocationService islandLocationService;
    final IslandBankService islandBankService;
    final IslandLeaderboardService islandLeaderboardService;
    private final BiomeModificationPort biomeModificationPort;
    private final PlayerSessionCoordinator sessionCoordinator;
    final SchedulerPort schedulerPort;
    final Supplier<@Nullable IslandWorthService> worthServiceProvider;
    private final Supplier<@Nullable IslandMissionService> missionServiceProvider;
    final Messages messages;
    final IslandLabels labels;

    /**
     * Which biomes this server offers and what an island has to reach first.
     *
     * <p>The numbers used to live in the biome definitions themselves, so an operator who wanted
     * the end sooner had nowhere to say so. The shipped file is the default until the bootstrap
     * hands over the operator's.
     */
    Supplier<com.uxplima.uxmskyblock.api.leaderboard.@Nullable LeaderboardMetrics> leaderboards = () -> null;

    private volatile BiomeConfiguration biomeRules = BiomeConfiguration.defaultConfiguration();

    volatile RecalculationGate recalculationGate =
            new RecalculationGate(RecalculationGate.DEFAULT_COOLDOWN, java.time.Clock.systemUTC());

    public IslandProgressionCommands(
            IslandLocationService islandLocationService,
            IslandBankService islandBankService,
            IslandLeaderboardService islandLeaderboardService,
            BiomeModificationPort biomeModificationPort,
            PlayerSessionCoordinator sessionCoordinator,
            SchedulerPort schedulerPort,
            Supplier<@Nullable IslandWorthService> worthServiceProvider,
            Supplier<@Nullable IslandMissionService> missionServiceProvider,
            Messages messages) {
        this.islandLocationService =
                Objects.requireNonNull(islandLocationService, "islandLocationService must not be null");
        this.islandBankService = Objects.requireNonNull(islandBankService, "islandBankService must not be null");
        this.islandLeaderboardService =
                Objects.requireNonNull(islandLeaderboardService, "islandLeaderboardService must not be null");
        this.biomeModificationPort =
                Objects.requireNonNull(biomeModificationPort, "biomeModificationPort must not be null");
        this.sessionCoordinator = Objects.requireNonNull(sessionCoordinator, "sessionCoordinator must not be null");
        this.schedulerPort = Objects.requireNonNull(schedulerPort, "schedulerPort must not be null");
        this.worthServiceProvider =
                Objects.requireNonNull(worthServiceProvider, "worthServiceProvider must not be null");
        this.missionServiceProvider =
                Objects.requireNonNull(missionServiceProvider, "missionServiceProvider must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
        this.labels = new IslandLabels(islandLocationService, messages);
    }

    /** Where the names islands were given are read, so a list can call an island by its name. */
    public void useIslandNames(
            java.util.function.Supplier<
                            com.uxplima.uxmskyblock.core.application.name.@org.jspecify.annotations.Nullable IslandNameService>
                    names) {
        labels.useNames(names);
    }

    /** The boards a plugin registered, which {@code /is top} shows by their metric id. */
    public void useLeaderboards(
            Supplier<com.uxplima.uxmskyblock.api.leaderboard.@Nullable LeaderboardMetrics> leaderboards) {
        this.leaderboards = java.util.Objects.requireNonNull(leaderboards, "leaderboards must not be null");
    }

    /** Tells the rescan command how long an island waits between two rescans. */
    public void useRecalculationCooldown(java.time.Duration cooldown) {
        useRecalculationGate(new RecalculationGate(cooldown, java.time.Clock.systemUTC()));
    }

    /** Package private so a test can hold the clock. */
    void useRecalculationGate(RecalculationGate gate) {
        this.recalculationGate = java.util.Objects.requireNonNull(gate, "gate must not be null");
    }

    /** Tells this command group which biomes the operator offers, and at what level. */
    public void useBiomeRules(@Nullable BiomeConfiguration biomeRules) {
        if (biomeRules != null) {
            this.biomeRules = biomeRules;
        }
    }

    public LiteralArgumentBuilder<CommandSourceStack> buildLevel() {
        return Cmd.literal("level")
                .executes(levelVerbs::executeLevel)
                .then(Cmd.literal("recalculate").executes(levelVerbs::executeLevelRecalculate));
    }

    /**
     * {@code /is recalc}: the short name the extreme scale document publishes for a recalculation.
     *
     * <p>The document has named it since it was written and nothing answered it. A command an
     * operator reads about and types is either there or the document is wrong, and the command is
     * cheaper than the correction.
     */
    public LiteralArgumentBuilder<CommandSourceStack> buildRecalc() {
        return Cmd.literal("recalc").executes(levelVerbs::executeLevelRecalculate);
    }

    public LiteralArgumentBuilder<CommandSourceStack> buildWorth() {
        return Cmd.literal("worth").executes(levelVerbs::executeLevel);
    }

    public LiteralArgumentBuilder<CommandSourceStack> buildValue() {
        return Cmd.literal("value").executes(levelVerbs::executeLevel);
    }

    public LiteralArgumentBuilder<CommandSourceStack> buildTop() {
        return Cmd.literal("top")
                .executes(ctx -> windows.openOr(ctx, "island-top", board -> topVerbs.executeTop(board, "level")))
                // A registered metric is named namespace:key, and a word stops at the colon.
                .then(Cmd.argument("category", StringArgumentType.greedyString())
                        .executes(ctx -> topVerbs.executeTop(ctx, StringArgumentType.getString(ctx, "category"))));
    }

    public LiteralArgumentBuilder<CommandSourceStack> buildBiome() {
        return Cmd.literal("biome")
                .executes(ctx -> windows.openOr(ctx, "island-biome", this::executeBiomeOffer))
                .then(Cmd.literal("list").executes(this::executeBiomeOffer))
                .then(Cmd.argument("type", StringArgumentType.word()).executes(this::executeBiomeChange));
    }

    /**
     * The biomes this server offers, and how to ask for one. The bare word answered with the game's
     * own error for an incomplete command, which names nothing a player could type next.
     */
    private int executeBiomeOffer(CommandContext<CommandSourceStack> ctx) {
        org.bukkit.command.CommandSender sender = ctx.getSource().getSender();
        BiomeConfiguration rules = this.biomeRules;
        if (!rules.enabled()) {
            send(sender, "biome.disabled");
            return Cmd.OK;
        }
        send(sender, "biome.offered", Placeholder.unparsed("offered", rules.offeredNames()));
        return Cmd.OK;
    }

    Optional<ProfileId> activeProfile(Player player) {
        if (sessionCoordinator == null) {
            return Optional.empty();
        }
        return sessionCoordinator.activeProfile(player.getUniqueId());
    }

    void send(Audience audience, String key, TagResolver... resolvers) {
        send(audience, messages.render(audience, key, resolvers));
    }

    static TagResolver number(String name, long value) {
        return Placeholder.unparsed(name, String.format(Locale.ROOT, "%,d", value));
    }

    /** Minor units are stored as an integer; a player reads them as money, as the server writes it. */
    static String money(long minorUnits) {
        return com.uxplima.uxmskyblock.bukkit.i18n.MoneyText.of(minorUnits);
    }

    void send(Audience audience, Component component) {
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

    /** The island's name, or whose island it is when nobody named it. */
    static Component nameOf(LeaderboardEntry entry, java.util.Map<IslandId, Component> unnamed) {
        return entry.named()
                ? Component.text(entry.islandName())
                : unnamed.getOrDefault(entry.islandId(), Component.empty());
    }

    static String plain(Component line) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(line);
    }

    /** A place on the board, or one of the two ways there is no place to name. */
    record Rank(int place) {
        static final Rank NOTHING_TO_SAY = new Rank(0);
        static final Rank NOT_PLACED = new Rank(-1);
    }

    private int executeBiomeChange(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            return Cmd.OK;
        }
        String biomeName = StringArgumentType.getString(ctx, "type");
        Optional<IslandBiome> optBiome = IslandBiome.fromId(biomeName);
        if (optBiome.isEmpty()) {
            send(player, "biome.unknown", Placeholder.unparsed("name", biomeName));
            return Cmd.OK;
        }

        PlayerUuid playerUuid = new PlayerUuid(player.getUniqueId());
        Optional<ProfileId> optProfile = activeProfile(player);
        if (optProfile.isEmpty()) {
            send(player, "error.session_not_active");
            return Cmd.OK;
        }
        ProfileId profileId = optProfile.get();
        IslandBiome targetBiome = optBiome.get();

        BiomeConfiguration rules = this.biomeRules;
        if (!rules.enabled() || !rules.offers(targetBiome)) {
            send(
                    player,
                    "biome.not_offered",
                    Placeholder.unparsed("name", targetBiome.id()),
                    Placeholder.unparsed("offered", rules.offeredNames()));
            return Cmd.OK;
        }

        schedulerPort.async(() -> {
            Optional<IslandId> optIslandId = islandLocationService.findIslandId(profileId);
            if (optIslandId.isEmpty()) {
                schedulerPort.onEntity(playerUuid, () -> send(player, "error.no_island"));
                return;
            }

            // The role editor has published a biome permission since the permission work and this
            // command read it nowhere, so any member could repaint the island the owner built.
            Optional<Island> optIsland = islandLocationService.findIsland(optIslandId.get());
            if (optIsland.isPresent()
                    && !optIsland.get().isOwner(profileId)
                    && !optIsland.get().hasPermission(profileId, IslandPermission.BIOME_CHANGE)) {
                schedulerPort.onEntity(playerUuid, () -> send(player, "biome.no_permission"));
                return;
            }

            int required = rules.requiredLevelOf(targetBiome);
            int reached = levelVerbs.levelOf(optIslandId.get(), profileId);
            if (reached < required) {
                schedulerPort.onEntity(
                        playerUuid,
                        () -> send(
                                player,
                                "biome.level_required",
                                Placeholder.unparsed("biome", targetBiome.displayName()),
                                Placeholder.unparsed("required", Integer.toString(required)),
                                Placeholder.unparsed("island_level", Integer.toString(reached))));
                return;
            }

            var unused = biomeModificationPort
                    .applyBiome(optIslandId.get(), targetBiome)
                    .thenAccept(success -> {
                        schedulerPort.onEntity(playerUuid, () -> {
                            if (success) {
                                send(player, "biome.changed", Placeholder.unparsed("biome", targetBiome.displayName()));
                            } else {
                                send(player, "biome.failed");
                            }
                        });
                    });
        });

        return Cmd.OK;
    }

    /** How many missions the caller has finished on this island, or none when missions are off. */
    int completedMissions(IslandId islandId, ProfileId profileId) {
        IslandMissionService missions = missionServiceProvider.get();
        return missions == null ? 0 : missions.countCompleted(islandId, profileId);
    }
}
