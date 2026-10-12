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
import com.uxplima.uxmskyblock.core.domain.leaderboard.LeaderboardCategory;
import com.uxplima.uxmskyblock.core.domain.leaderboard.LeaderboardEntry;
import com.uxplima.uxmskyblock.core.domain.worth.IslandScoreBreakdown;
import org.jspecify.annotations.Nullable;

/**
 * Handles island progression and valuation commands:
 * /is level, /is worth, /is value, /is top, /is biome
 */
public final class IslandProgressionCommands {

    /** Where a command named after a window opens it. */
    private CommandWindows windows = CommandWindows.none();

    /** The window a board is shown in, looked up when one is asked for. */
    private Supplier<com.uxplima.uxmskyblock.bukkit.menu.@Nullable TopList> topList = () -> null;

    void useTopList(Supplier<com.uxplima.uxmskyblock.bukkit.menu.@Nullable TopList> topList) {
        this.topList = Objects.requireNonNull(topList, "topList must not be null");
    }

    void useWindows(CommandWindows windows) {
        this.windows = java.util.Objects.requireNonNull(windows, "windows must not be null");
    }

    private final IslandLocationService islandLocationService;
    private final IslandBankService islandBankService;
    private final IslandLeaderboardService islandLeaderboardService;
    private final BiomeModificationPort biomeModificationPort;
    private final PlayerSessionCoordinator sessionCoordinator;
    private final SchedulerPort schedulerPort;
    private final Supplier<@Nullable IslandWorthService> worthServiceProvider;
    private final Supplier<@Nullable IslandMissionService> missionServiceProvider;
    private final Messages messages;
    private final IslandLabels labels;

    /**
     * Which biomes this server offers and what an island has to reach first.
     *
     * <p>The numbers used to live in the biome definitions themselves, so an operator who wanted
     * the end sooner had nowhere to say so. The shipped file is the default until the bootstrap
     * hands over the operator's.
     */
    private Supplier<com.uxplima.uxmskyblock.api.leaderboard.@Nullable LeaderboardMetrics> leaderboards = () -> null;

    private volatile BiomeConfiguration biomeRules = BiomeConfiguration.defaultConfiguration();

    private volatile RecalculationGate recalculationGate =
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
                .executes(this::executeLevel)
                .then(Cmd.literal("recalculate").executes(this::executeLevelRecalculate));
    }

    /**
     * {@code /is recalc}: the short name the extreme scale document publishes for a recalculation.
     *
     * <p>The document has named it since it was written and nothing answered it. A command an
     * operator reads about and types is either there or the document is wrong, and the command is
     * cheaper than the correction.
     */
    public LiteralArgumentBuilder<CommandSourceStack> buildRecalc() {
        return Cmd.literal("recalc").executes(this::executeLevelRecalculate);
    }

    public LiteralArgumentBuilder<CommandSourceStack> buildWorth() {
        return Cmd.literal("worth").executes(this::executeLevel);
    }

    public LiteralArgumentBuilder<CommandSourceStack> buildValue() {
        return Cmd.literal("value").executes(this::executeLevel);
    }

    public LiteralArgumentBuilder<CommandSourceStack> buildTop() {
        return Cmd.literal("top")
                .executes(ctx -> windows.openOr(ctx, "island-top", board -> executeTop(board, "level")))
                // A registered metric is named namespace:key, and a word stops at the colon.
                .then(Cmd.argument("category", StringArgumentType.greedyString())
                        .executes(ctx -> executeTop(ctx, StringArgumentType.getString(ctx, "category"))));
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

    private Optional<ProfileId> activeProfile(Player player) {
        if (sessionCoordinator == null) {
            return Optional.empty();
        }
        return sessionCoordinator.activeProfile(player.getUniqueId());
    }

    private void send(Audience audience, String key, TagResolver... resolvers) {
        send(audience, messages.render(audience, key, resolvers));
    }

    private static TagResolver number(String name, long value) {
        return Placeholder.unparsed(name, String.format(Locale.ROOT, "%,d", value));
    }

    /** Minor units are stored as an integer; a player reads them as money, as the server writes it. */
    private static String money(long minorUnits) {
        return com.uxplima.uxmskyblock.bukkit.i18n.MoneyText.of(minorUnits);
    }

    private void send(Audience audience, Component component) {
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

    private int executeLevel(CommandContext<CommandSourceStack> ctx) {
        Audience sender = ctx.getSource().getSender();
        if (!(sender instanceof Player player)) {
            send(sender, "error.players_only");
            return Cmd.OK;
        }
        IslandWorthService worthService = worthServiceProvider.get();
        if (worthService == null) {
            send(player, "level.engine_disabled");
            return Cmd.OK;
        }

        Optional<ProfileId> optProfile = activeProfile(player);
        if (optProfile.isEmpty()) {
            send(player, "error.session_not_active");
            return Cmd.OK;
        }

        ProfileId profileId = optProfile.get();
        schedulerPort.async(() -> {
            // Which island the caller belongs to is a row in a table. Reading it here, before the
            // scheduler, put a query on the thread Brigadier runs a command on, which is the thread
            // running the game for everybody in the region.
            Optional<IslandId> optIsland = islandLocationService.findIslandId(profileId);
            if (optIsland.isEmpty()) {
                send(player, "error.no_island");
                return;
            }
            IslandId islandId = optIsland.get();
            long bankBalance = islandBankService.getBalanceMinorUnits(profileId).orElse(0L);
            // The finished missions really are counted. This used to pass a hardcoded zero, so
            // levels.quest-weight was a number an operator could set and never see applied: a player
            // who finished every mission scored the same as one who finished none.
            IslandScoreBreakdown score =
                    worthService.calculateScore(islandId, completedMissions(islandId, profileId), bankBalance);
            schedulerPort.onEntity(new PlayerUuid(player.getUniqueId()), () -> {
                send(player, "level.header");
                send(player, "level.calculated", number("island_level", score.calculatedLevel()));
                send(player, "level.total_score", number("score", score.totalScore()));
                send(player, "level.block_score", number("score", score.blockScore()));
                send(player, "level.spawner_score", number("score", score.spawnerScore()));
                send(player, "level.bank_score", number("score", score.bankScore()));
                send(
                        player,
                        "level.worth",
                        Placeholder.unparsed("worth", money(score.dampedEconomicWorthMinorUnits())));
                send(player, "level.recalculate_hint");
            });
        });
        return Cmd.OK;
    }

    private int executeLevelRecalculate(CommandContext<CommandSourceStack> ctx) {
        Audience sender = ctx.getSource().getSender();
        if (!(sender instanceof Player player)) {
            send(sender, "error.players_only");
            return Cmd.OK;
        }
        IslandWorthService worthService = worthServiceProvider.get();
        if (worthService == null) {
            send(player, "level.engine_disabled");
            return Cmd.OK;
        }

        Optional<ProfileId> optProfile = activeProfile(player);
        if (optProfile.isEmpty()) {
            send(player, "error.session_not_active");
            return Cmd.OK;
        }

        ProfileId profileId = optProfile.get();
        RecalculationGate gate = this.recalculationGate;

        schedulerPort.async(() -> {
            Optional<IslandId> optIsland = islandLocationService.findIslandId(profileId);
            if (optIsland.isEmpty()) {
                send(player, "error.no_island");
                return;
            }
            IslandId islandId = optIsland.get();
            Optional<com.uxplima.uxmskyblock.core.domain.island.IslandLocation> optLoc =
                    islandLocationService.findLocation(islandId);
            if (optLoc.isEmpty()) {
                schedulerPort.onEntity(
                        new PlayerUuid(player.getUniqueId()), () -> send(player, "level.details_missing"));
                return;
            }
            com.uxplima.uxmskyblock.core.domain.island.IslandLocation loc = optLoc.get();
            long bankBalance = islandBankService.getBalanceMinorUnits(profileId).orElse(0L);

            // The same hardcoded zero that once sat in executeLevel sat here too, and this is the
            // worse half: the recalculation is what rescans the island and publishes the new level,
            // so levels.quest-weight was dropped from the number a player is finally given.
            int completed = completedMissions(islandId, profileId);

            switch (gate.tryEnter(islandId)) {
                case RecalculationGate.Admission.AlreadyRunning running -> {
                    send(player, "level.recalculation_running");
                    return;
                }
                case RecalculationGate.Admission.CoolingDown wait -> {
                    send(
                            player,
                            "level.recalculation_cooldown",
                            Placeholder.unparsed(
                                    "remaining",
                                    Long.toString(Math.max(1L, wait.remaining().toSeconds()))));
                    return;
                }
                case RecalculationGate.Admission.Admitted admitted -> send(player, "level.recalculating");
            }

            try {
                worthService.triggerAsyncRecalculation(
                        islandId, loc.worldName(), loc.bounds(), completed, bankBalance, score -> {
                            gate.leave(islandId);
                            schedulerPort.onEntity(new PlayerUuid(player.getUniqueId()), () -> {
                                send(player, "level.recalculated");
                                send(
                                        player,
                                        "level.new_level",
                                        number("island_level", score.calculatedLevel()),
                                        number("score", score.totalScore()));
                                send(
                                        player,
                                        "level.worth",
                                        Placeholder.unparsed("worth", money(score.dampedEconomicWorthMinorUnits())));
                            });
                        });
            } catch (RuntimeException failed) {
                // A rescan that never started must not hold the island's place for ever.
                gate.leave(islandId);
                throw failed;
            }
        });
        return Cmd.OK;
    }

    private int executeTop(CommandContext<CommandSourceStack> ctx, String category) {
        CommandSourceStack src = ctx.getSource();
        String asked = category.trim().toLowerCase(Locale.ROOT);
        com.uxplima.uxmskyblock.api.leaderboard.LeaderboardMetrics registered = leaderboards.get();
        com.uxplima.uxmskyblock.api.NamespacedId metricId = com.uxplima.uxmskyblock.api.NamespacedId.of(asked);
        if (registered != null
                && com.uxplima.uxmskyblock.core.application.leaderboard.IslandMetricProviders.categoryOf(metricId)
                        .isEmpty()) {
            var provider = registered.provider(metricId);
            if (provider.isPresent()) {
                return executeMetricTop(src, registered, provider.get());
            }
        }
        LeaderboardCategory cat = com.uxplima.uxmskyblock.core.application.leaderboard.IslandMetricProviders.categoryOf(
                        metricId)
                .orElseGet(() -> switch (asked) {
                    case "worth" -> LeaderboardCategory.WORTH;
                    case "bank" -> LeaderboardCategory.BANK;
                    default -> LeaderboardCategory.LEVEL;
                });

        schedulerPort.async(() -> {
            var entries = islandLeaderboardService.getTop(cat, 10);
            // Where the caller's own island stands. The board prints ten and a server has hundreds,
            // so a player outside the ten used to learn nothing at all from a board about them. The
            // rank is read off the same cached board, not the database; only finding out which
            // island is theirs is a read, and only a player has one.
            Rank ownRank = rankOf(src.getSender(), cat);
            // An island nobody named is called whose it is, which is a read, so it is done here.
            java.util.Map<IslandId, Component> unnamed = new java.util.HashMap<>();
            for (LeaderboardEntry entry : entries) {
                if (!entry.named()) {
                    unnamed.put(entry.islandId(), labels.of(src.getSender(), entry.islandId()));
                }
            }
            // A window of the board, each island under its owner's head, when the operator kept its file.
            com.uxplima.uxmskyblock.bukkit.menu.TopList window = topList.get();
            if (window != null && src.getSender() instanceof Player player) {
                java.util.Map<IslandId, java.util.UUID> owners = new java.util.HashMap<>();
                for (LeaderboardEntry entry : entries) {
                    islandLocationService
                            .findIsland(entry.islandId())
                            .ifPresent(island -> owners.put(
                                    entry.islandId(), island.ownerPlayerUuid().value()));
                }
                schedulerPort.onEntity(new PlayerUuid(player.getUniqueId()), () -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    // A name by id is the server's cache of who has played, read on the player's thread.
                    java.util.Map<java.util.UUID, String> ownerNames = new java.util.HashMap<>();
                    owners.values().forEach(owner -> {
                        String name = org.bukkit.Bukkit.getOfflinePlayer(owner).getName();
                        if (name != null) {
                            ownerNames.put(owner, name);
                        }
                    });
                    java.util.List<com.uxplima.uxmskyblock.bukkit.menu.TopList.Entry> shown = entries.stream()
                            .map(entry -> {
                                java.util.UUID owner = owners.get(entry.islandId());
                                return new com.uxplima.uxmskyblock.bukkit.menu.TopList.Entry(
                                        entry.rank(),
                                        plain(nameOf(entry, unnamed)),
                                        plain(scoreOf(player, cat, entry)),
                                        owner,
                                        owner == null ? null : ownerNames.get(owner));
                            })
                            .toList();
                    String board = plain(messages.renderPlain(
                            player, "leaderboard.category_" + cat.name().toLowerCase(Locale.ROOT)));
                    if (!window.show(player, board, plain(rankWords(player, ownRank)), shown)) {
                        topInChat(src, cat, entries, unnamed, ownRank);
                    }
                });
                return;
            }
            schedulerPort.onGlobal(() -> topInChat(src, cat, entries, unnamed, ownRank));
        });

        return Cmd.OK;
    }

    /** A board in chat: a header, a line for each island, and where the reader's own island stands. */
    private void topInChat(
            CommandSourceStack src,
            LeaderboardCategory cat,
            java.util.List<LeaderboardEntry> entries,
            java.util.Map<IslandId, Component> unnamed,
            Rank ownRank) {
        Audience audience = src.getSender();
        Component categoryName = messages.renderPlain(
                audience, "leaderboard.category_" + cat.name().toLowerCase(Locale.ROOT));
        send(audience, "leaderboard.header", Placeholder.component("category", categoryName));
        if (entries.isEmpty()) {
            send(audience, "leaderboard.empty");
        } else {
            for (LeaderboardEntry entry : entries) {
                send(
                        audience,
                        "leaderboard.entry",
                        Placeholder.unparsed("place", Integer.toString(entry.rank())),
                        Placeholder.component("name", nameOf(entry, unnamed)),
                        Placeholder.component("score", scoreOf(audience, cat, entry)));
            }
        }
        if (ownRank == Rank.NOT_PLACED) {
            send(audience, "leaderboard.your_rank_unplaced");
        } else if (ownRank.place() > 0) {
            send(audience, "leaderboard.your_rank", Placeholder.unparsed("place", Integer.toString(ownRank.place())));
        }
    }

    /** The island's name, or whose island it is when nobody named it. */
    private static Component nameOf(LeaderboardEntry entry, java.util.Map<IslandId, Component> unnamed) {
        return entry.named()
                ? Component.text(entry.islandName())
                : unnamed.getOrDefault(entry.islandId(), Component.empty());
    }

    /**
     * An island's score in the reader's words. A level is a word, and money is written through the catalogue,
     * where the operator names the currency; the stored entry spells both in English for the API.
     */
    private Component scoreOf(Audience reader, LeaderboardCategory cat, LeaderboardEntry entry) {
        return cat == LeaderboardCategory.LEVEL
                ? messages.renderPlain(
                        reader,
                        "leaderboard.score_level",
                        Placeholder.unparsed("island_level", Long.toString(entry.score())))
                : messages.renderPlain(
                        reader,
                        "leaderboard.score_money",
                        Placeholder.unparsed(
                                "amount", com.uxplima.uxmskyblock.bukkit.i18n.MoneyText.of(entry.score())));
    }

    /** Where the reader's own island stands, as the leaderboard window says it. */
    private Component rankWords(Audience reader, Rank rank) {
        if (rank == Rank.NOT_PLACED) {
            return messages.renderPlain(reader, "menu.leaderboard.rank_unplaced");
        }
        if (rank.place() > 0) {
            return messages.renderPlain(
                    reader,
                    "menu.leaderboard.rank_placed",
                    Placeholder.unparsed("place", Integer.toString(rank.place())));
        }
        return messages.renderPlain(reader, "menu.leaderboard.rank_none");
    }

    private static String plain(Component line) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(line);
    }

    /**
     * The board of a metric another plugin registered.
     *
     * <p>The owner is read off the main thread, as each shipped board is. The board's name comes from
     * the reader's language file under {@code leaderboard.metrics}, keyed by the id with its colon
     * written as an underscore, and otherwise from the owner.
     */
    private int executeMetricTop(
            CommandSourceStack src,
            com.uxplima.uxmskyblock.api.leaderboard.LeaderboardMetrics registered,
            com.uxplima.uxmskyblock.api.leaderboard.LeaderboardMetricProvider provider) {
        schedulerPort.async(() -> {
            var board = registered.ranked(provider.metricId(), 10);
            schedulerPort.onGlobal(() -> {
                Audience audience = src.getSender();
                String key = provider.metricId().asString().replace(':', '_');
                String title = messages.named(audience, "leaderboard.metrics", key, provider.displayName());
                send(audience, "leaderboard.header", Placeholder.unparsed("category", title));
                if (board.isEmpty()) {
                    send(audience, "leaderboard.empty");
                    return;
                }
                for (var ranked : board) {
                    send(
                            audience,
                            "leaderboard.entry",
                            Placeholder.unparsed("place", Integer.toString(ranked.rank())),
                            Placeholder.unparsed("name", ranked.reading().displayName()),
                            Placeholder.component(
                                    "score",
                                    messages.renderPlain(
                                            audience,
                                            "leaderboard.score_value",
                                            Placeholder.unparsed("figure", ranked.formatted()))));
                }
            });
        });
        return Cmd.OK;
    }

    /**
     * Where one sender's island stands on a board, or nothing to say.
     *
     * <p>A console has no island and a player without one has none either, and neither wants a line
     * about a rank. An island the board does not hold is a different answer from no island at all:
     * the board holds as many as the operator's cache capacity, and being past the end of it is
     * worth saying.
     */
    private Rank rankOf(Audience sender, LeaderboardCategory category) {
        if (!(sender instanceof Player player)) {
            return Rank.NOTHING_TO_SAY;
        }
        return activeProfile(player)
                .flatMap(islandLocationService::findIslandId)
                .map(islandId -> {
                    java.util.OptionalInt place = islandLeaderboardService.getRank(category, islandId);
                    return place.isPresent() ? new Rank(place.getAsInt()) : Rank.NOT_PLACED;
                })
                .orElse(Rank.NOTHING_TO_SAY);
    }

    /** A place on the board, or one of the two ways there is no place to name. */
    private record Rank(int place) {
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
            int reached = levelOf(optIslandId.get(), profileId);
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

    /**
     * The level this island has reached, or every level at once when there is nothing to measure.
     *
     * <p>A node with the worth engine switched off has no levels, so it has no levels to require:
     * every biome the operator offers is open there. Returning the highest possible number says
     * that without a second branch at the caller.
     */
    private int levelOf(IslandId islandId, ProfileId profileId) {
        IslandWorthService worthService = worthServiceProvider.get();
        if (worthService == null) {
            return Integer.MAX_VALUE;
        }
        long bankBalance = islandBankService.getBalanceMinorUnits(profileId).orElse(0L);
        return (int) Math.min(
                Integer.MAX_VALUE,
                worthService
                        .calculateScore(islandId, completedMissions(islandId, profileId), bankBalance)
                        .calculatedLevel());
    }

    /** How many missions the caller has finished on this island, or none when missions are off. */
    private int completedMissions(IslandId islandId, ProfileId profileId) {
        IslandMissionService missions = missionServiceProvider.get();
        return missions == null ? 0 : missions.countCompleted(islandId, profileId);
    }
}
