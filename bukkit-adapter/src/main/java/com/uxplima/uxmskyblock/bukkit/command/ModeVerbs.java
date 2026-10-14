package com.uxplima.uxmskyblock.bukkit.command;

import java.util.Objects;
import java.util.OptionalInt;
import java.util.function.BiConsumer;

import org.bukkit.entity.Player;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.uxplima.uxmlib.command.Cmd;
import com.uxplima.uxmskyblock.bukkit.boxed.BoxedShop;
import com.uxplima.uxmskyblock.bukkit.brix.BrixContestPlay;
import com.uxplima.uxmskyblock.bukkit.brix.BrixGallery;
import com.uxplima.uxmskyblock.bukkit.brix.PlotReset;
import com.uxplima.uxmskyblock.bukkit.brix.PlotSkyCommands;
import com.uxplima.uxmskyblock.bukkit.chunkblock.ChunkBlockPanel;
import com.uxplima.uxmskyblock.bukkit.fly.IslandFly;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.oneblock.OneBlockPanel;
import com.uxplima.uxmskyblock.bukkit.parkour.ParkourBoard;
import com.uxplima.uxmskyblock.bukkit.parkour.ParkourCourses;
import com.uxplima.uxmskyblock.bukkit.parkour.ParkourTagging;
import com.uxplima.uxmskyblock.bukkit.poseidon.PoseidonLore;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.bukkit.trade.Trades;
import com.uxplima.uxmskyblock.bukkit.tradewinds.Boarding;
import com.uxplima.uxmskyblock.bukkit.tradewinds.CargoHolds;
import com.uxplima.uxmskyblock.bukkit.tradewinds.ContractDesk;
import com.uxplima.uxmskyblock.bukkit.tradewinds.Harbour;
import com.uxplima.uxmskyblock.bukkit.vault.IslandVaultWindow;
import com.uxplima.uxmskyblock.bukkit.visitor.VisitFees;
import com.uxplima.uxmskyblock.bukkit.worth.BlockValues;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import org.jspecify.annotations.Nullable;

/**
 * The verbs that reach a part the server may not run, the island vault among them, and above all the verbs the game
 * modes add to the island command: OneBlock, ChunkBlock, Poseidon, Parkour, Brix, TradeWinds,
 * Boxed and the island extras, each reaching a part that is there only while its mode or module is on. Split out of
 * {@link IslandCommandTree}, which hangs them under its root, so that class stays the size the standards allow.
 */
final class ModeVerbs {

    /** How many log lines a caller who names no number gets. */
    private static final int DEFAULT_VAULT_LOG_LINES = 10;

    private static final String NOT_VESSEL = "tradewinds.hold.not_vessel";
    private static final String NOT_COURSE = "parkour.not_course";
    private static final String NOT_PLOT = "brix.not_plot";
    private static final String NO_CONTEST = "brix.contest.none";
    private static final String NO_BOX = "boxed.buy.no_box";
    private static final String NOT_CHUNKBLOCK = "chunkblock.not_chunkblock";

    private final SchedulerPort schedulerPort;
    private final PlayerSessionCoordinator sessionCoordinator;
    private final Messages messages;

    private @Nullable OneBlockPanel oneBlockPanel;

    private @Nullable ChunkBlockPanel chunkBlockPanel;

    private @Nullable PoseidonLore poseidonLore;

    private @Nullable ParkourBoard parkourBoard;

    private @Nullable ParkourTagging parkourTagging;

    private @Nullable ParkourCourses parkourCourses;

    private @Nullable PlotSkyCommands plotSky;

    private @Nullable PlotReset plotReset;

    private @Nullable BrixContestPlay brixContests;

    private @Nullable BrixGallery brixGallery;

    /** {@code /is contest}, while Brix is enabled. */
    private @Nullable IslandFly islandFly;

    /** {@code /is values}, while the worth module is on. */
    private @Nullable BlockValues blockValues;

    /** {@code /is visitfee}, and the fee {@code /is visit} asks for, while the server charges one. */
    private @Nullable VisitFees visitFees;

    private @Nullable Trades trades;

    private @Nullable CargoHolds cargoHolds;

    private @Nullable Harbour harbour;

    private @Nullable ContractDesk contracts;

    private @Nullable Boarding boarding;

    private @Nullable BoxedShop boxedShop;

    ModeVerbs(SchedulerPort schedulerPort, PlayerSessionCoordinator sessionCoordinator, Messages messages) {
        this.schedulerPort = Objects.requireNonNull(schedulerPort, "schedulerPort must not be null");
        this.sessionCoordinator = Objects.requireNonNull(sessionCoordinator, "sessionCoordinator must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
    }

    /** What the chunks command opens and unlocks, while the operator lets islands be ChunkBlock islands. */
    public void useChunkBlock(@Nullable ChunkBlockPanel panel) {
        this.chunkBlockPanel = panel;
    }
    /** {@code /is plots}, while Brix is enabled. */
    public void useBrixGallery(@Nullable BrixGallery gallery) {
        this.brixGallery = gallery;
    }
    /** {@code /is values}: what each block is worth to an island. */
    public void useBlockValues(@Nullable BlockValues values) {
        this.blockValues = values;
    }
    /** {@code /is visitfee}, and the fee {@code /is visit} asks for. */
    public void useVisitFees(@Nullable VisitFees fees) {
        this.visitFees = fees;
    }
    /** {@code /is fly}. */
    public void useIslandFly(@Nullable IslandFly fly) {
        this.islandFly = fly;
    }

    public void useBrixContests(@Nullable BrixContestPlay contests) {
        this.brixContests = contests;
    }
    /** What {@code /is plot reset} clears, while islands can be Brix plots. */
    public void usePlotReset(@Nullable PlotReset reset) {
        this.plotReset = reset;
    }
    /** What {@code /is plot time} and {@code /is plot weather} set, while islands can be Brix plots. */
    public void usePlotSky(@Nullable PlotSkyCommands sky) {
        this.plotSky = sky;
    }
    /** What {@code /is courses} opens, while islands can be Parkour courses. */
    public void useParkourCourses(@Nullable ParkourCourses courses) {
        this.parkourCourses = courses;
    }
    /** What {@code /is coursetag} and {@code /is courserate} do, while islands can be Parkour courses. */
    public void useParkourTagging(@Nullable ParkourTagging tagging) {
        this.parkourTagging = tagging;
    }
    /** What {@code /is course} shows, while the operator lets islands be Parkour courses. */
    public void useParkourBoard(@Nullable ParkourBoard board) {
        this.parkourBoard = board;
    }
    /** What {@code /is lore} opens, while the operator lets islands be Poseidon islands. */
    public void usePoseidonLore(@Nullable PoseidonLore lore) {
        this.poseidonLore = lore;
    }
    /** What {@code /is sail} and {@code /is market} open, while the operator lets islands be vessels. */
    public void useHarbour(@Nullable Harbour harbour) {
        this.harbour = harbour;
    }
    /** What {@code /is boxbuy} and {@code /is boxside} do, while the operator lets islands be Boxed. */
    public void useBoxedShop(@Nullable BoxedShop shop) {
        this.boxedShop = shop;
    }
    /** What {@code /is plunder} does, while the operator lets islands be vessels. */
    public void useBoarding(@Nullable Boarding boarding) {
        this.boarding = boarding;
    }
    /** What {@code /is contracts} opens, while the operator lets islands be vessels. */
    public void useContracts(@Nullable ContractDesk contracts) {
        this.contracts = contracts;
    }
    /** What {@code /is cargo} opens, while the operator lets islands be TradeWinds vessels. */
    public void useCargoHolds(@Nullable CargoHolds holds) {
        this.cargoHolds = holds;
    }
    /** What {@code /is trade} asks and answers, while the operator lets players trade. */
    public void useTrades(@Nullable Trades trades) {
        this.trades = trades;
    }
    /** What {@code /is oneblock} opens, while the operator lets islands be OneBlock islands. */
    public void useOneBlock(@Nullable OneBlockPanel panel) {
        this.oneBlockPanel = panel;
    }

    /** What {@code /is visit} asks of the visit fee, or nothing while the server charges none. */
    @Nullable VisitFees visitFees() {
        return visitFees;
    }

    /** Hangs every game mode's verb under {@code root}. Each reads its part when it runs, not when it is hung. */
    void attach(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(Cmd.literal("oneblock")
                .executes(ctx -> reach(ctx, oneBlockPanel, "oneblock.not_oneblock", OneBlockPanel::open)));
        root.then(Cmd.literal("lore")
                .executes(ctx -> reach(ctx, poseidonLore, "poseidon.not_poseidon", PoseidonLore::open)));
        root.then(Cmd.literal("trade")
                .then(Cmd.argument("player", StringArgumentType.word())
                        .executes(ctx -> reach(
                                ctx,
                                trades,
                                "trade.disabled",
                                (running, player) -> running.ask(
                                        player,
                                        player.getServer()
                                                .getPlayerExact(StringArgumentType.getString(ctx, "player")))))));
        root.then(Cmd.literal("cargo").executes(ctx -> reach(ctx, cargoHolds, NOT_VESSEL, CargoHolds::open)));
        root.then(Cmd.literal("sail").executes(ctx -> reach(ctx, harbour, NOT_VESSEL, Harbour::sail)));
        root.then(Cmd.literal("market").executes(ctx -> reach(ctx, harbour, NOT_VESSEL, Harbour::market)));
        root.then(Cmd.literal("contracts").executes(ctx -> reach(ctx, contracts, NOT_VESSEL, ContractDesk::open)));
        root.then(Cmd.literal("plunder").executes(ctx -> reach(ctx, boarding, NOT_VESSEL, Boarding::plunder)));
        root.then(Cmd.literal("boxbuy").executes(ctx -> reach(ctx, boxedShop, NO_BOX, BoxedShop::buy)));
        root.then(Cmd.literal("boxside")
                .executes(ctx -> reach(ctx, boxedShop, NO_BOX, (shop, player) -> shop.side(player, null)))
                .then(Cmd.argument("side", StringArgumentType.word())
                        .executes(ctx -> reach(
                                ctx,
                                boxedShop,
                                NO_BOX,
                                (shop, player) -> shop.side(player, StringArgumentType.getString(ctx, "side"))))));
        root.then(Cmd.literal("course")
                .executes(ctx -> reach(ctx, parkourBoard, NOT_COURSE, ParkourBoard::show))
                .then(Cmd.literal("season")
                        .executes(ctx -> reach(
                                ctx,
                                parkourBoard,
                                NOT_COURSE,
                                (board, player) -> board.showSeason(player, OptionalInt.empty())))
                        .then(Cmd.argument("number", IntegerArgumentType.integer(1))
                                .executes(ctx -> reach(
                                        ctx,
                                        parkourBoard,
                                        NOT_COURSE,
                                        (board, player) -> board.showSeason(
                                                player,
                                                OptionalInt.of(IntegerArgumentType.getInteger(ctx, "number"))))))));
        root.then(Cmd.literal("courses")
                .executes(ctx -> reach(ctx, parkourCourses, "parkour.courses.none", ParkourCourses::open)));
        root.then(
                Cmd.literal("plots").executes(ctx -> reach(ctx, brixGallery, "brix.gallery.none", BrixGallery::open)));
        // The flight and the island read for it are the player's, so the toggle runs on their own thread.
        root.then(Cmd.literal("fly")
                .executes(
                        ctx -> reach(ctx, islandFly, "fly.disabled", (fly, player) -> onThread(player, fly::toggle))));
        // The values are the configuration's, in memory; the window opens on the player's own thread.
        root.then(Cmd.literal("values")
                .executes(ctx -> reach(
                        ctx,
                        blockValues,
                        "block_values.disabled",
                        (values, player) -> onThread(player, values::open))));
        root.then(Cmd.literal("visitfee")
                .executes(ctx -> reach(
                        ctx, visitFees, "visit_fee.disabled", (fees, player) -> fees.show(player, profileOf(player))))
                .then(Cmd.argument("amount", DoubleArgumentType.doubleArg(0))
                        .executes(ctx -> reach(
                                ctx,
                                visitFees,
                                "visit_fee.disabled",
                                (fees, player) -> fees.set(
                                        player, profileOf(player), DoubleArgumentType.getDouble(ctx, "amount"))))));
        root.then(Cmd.literal("contest")
                .executes(ctx -> reach(ctx, brixContests, NO_CONTEST, BrixContestPlay::show))
                .then(Cmd.literal("join").executes(ctx -> reach(ctx, brixContests, NO_CONTEST, BrixContestPlay::join)))
                .then(Cmd.literal("vote")
                        .then(Cmd.argument("stars", IntegerArgumentType.integer())
                                .executes(ctx -> reach(
                                        ctx,
                                        brixContests,
                                        NO_CONTEST,
                                        (contests, player) -> contests.vote(
                                                player, IntegerArgumentType.getInteger(ctx, "stars")))))));
        root.then(Cmd.literal("plot")
                .then(Cmd.literal("reset")
                        .executes(ctx -> reach(ctx, plotReset, NOT_PLOT, PlotReset::ask))
                        .then(Cmd.literal("confirm")
                                .executes(ctx -> reach(ctx, plotReset, NOT_PLOT, PlotReset::confirm))))
                .then(Cmd.literal("time")
                        .then(Cmd.argument("value", StringArgumentType.word())
                                .executes(ctx -> reach(
                                        ctx,
                                        plotSky,
                                        NOT_PLOT,
                                        (sky, player) ->
                                                sky.time(player, StringArgumentType.getString(ctx, "value"))))))
                .then(Cmd.literal("weather")
                        .then(Cmd.argument("value", StringArgumentType.word())
                                .executes(ctx -> reach(
                                        ctx,
                                        plotSky,
                                        NOT_PLOT,
                                        (sky, player) ->
                                                sky.weather(player, StringArgumentType.getString(ctx, "value")))))));
        root.then(Cmd.literal("coursetag")
                .then(courseTag("difficulty", ParkourTagging.Kind.DIFFICULTY))
                .then(courseTag("category", ParkourTagging.Kind.CATEGORY)));
        root.then(Cmd.literal("courserate")
                .then(Cmd.argument("stars", IntegerArgumentType.integer())
                        .executes(ctx -> reach(
                                ctx,
                                parkourTagging,
                                NOT_COURSE,
                                (tagging, player) ->
                                        tagging.rate(player, IntegerArgumentType.getInteger(ctx, "stars"))))));
        root.then(Cmd.literal("chunks")
                .executes(ctx -> reach(ctx, chunkBlockPanel, NOT_CHUNKBLOCK, ChunkBlockPanel::open))
                .then(Cmd.literal("unlock")
                        .executes(ctx -> reach(ctx, chunkBlockPanel, NOT_CHUNKBLOCK, ChunkBlockPanel::unlockFaced))));
    }

    /** The vault window under whichever word the caller typed: a page of it, or the last lines of its log. */
    LiteralArgumentBuilder<CommandSourceStack> vault(
            String verb, java.util.function.Supplier<@Nullable IslandVaultWindow> window) {
        return Cmd.literal(verb)
                .executes(ctx -> reach(ctx, window.get(), "vault.disabled", (open, player) -> open.open(player, 1)))
                .then(Cmd.literal("log")
                        .executes(ctx -> reach(
                                ctx,
                                window.get(),
                                "vault.disabled",
                                (open, player) -> open.showLog(player, DEFAULT_VAULT_LOG_LINES)))
                        .then(Cmd.argument("lines", IntegerArgumentType.integer(1))
                                .executes(ctx -> reach(
                                        ctx,
                                        window.get(),
                                        "vault.disabled",
                                        (open, player) ->
                                                open.showLog(player, IntegerArgumentType.getInteger(ctx, "lines"))))))
                .then(Cmd.argument("page", IntegerArgumentType.integer(1))
                        .executes(ctx -> reach(
                                ctx,
                                window.get(),
                                "vault.disabled",
                                (open, player) -> open.open(player, IntegerArgumentType.getInteger(ctx, "page")))));
    }

    /** {@code coursetag <kind>} takes the tag off; {@code coursetag <kind> <name>} gives it. */
    private LiteralArgumentBuilder<CommandSourceStack> courseTag(String word, ParkourTagging.Kind kind) {
        return Cmd.literal(word)
                .executes(ctx ->
                        reach(ctx, parkourTagging, NOT_COURSE, (tagging, player) -> tagging.tag(player, kind, null)))
                .then(Cmd.argument("name", StringArgumentType.word())
                        .suggests((ctx, builder) -> {
                            ParkourTagging tagging = this.parkourTagging;
                            if (tagging != null) {
                                tagging.names(kind).forEach(builder::suggest);
                            }
                            return builder.buildFuture();
                        })
                        .executes(ctx -> reach(
                                ctx,
                                parkourTagging,
                                NOT_COURSE,
                                (tagging, player) ->
                                        tagging.tag(player, kind, StringArgumentType.getString(ctx, "name")))));
    }

    /**
     * Runs {@code act} with {@code part} for the player who typed the verb. The console is told the verb is for players,
     * and a player on a server that runs no such part is told {@code absent}.
     */
    private <T> int reach(
            CommandContext<CommandSourceStack> ctx, @Nullable T part, String absent, BiConsumer<T, Player> act) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            send(ctx.getSource().getSender(), "error.players_only");
        } else if (part == null) {
            send(player, absent);
        } else {
            act.accept(part, player);
        }
        return Cmd.OK;
    }

    /** Runs {@code work} for {@code player} on the thread that owns them. */
    private void onThread(Player player, java.util.function.Consumer<Player> work) {
        schedulerPort.onEntity(new PlayerUuid(player.getUniqueId()), () -> work.accept(player));
    }

    private @Nullable ProfileId profileOf(Player player) {
        return sessionCoordinator.activeProfile(player.getUniqueId()).orElse(null);
    }

    private void send(Audience audience, String key) {
        Component said = messages.render(audience, key);
        if (audience instanceof Player player) {
            schedulerPort.onEntity(new PlayerUuid(player.getUniqueId()), () -> {
                if (player.isOnline()) {
                    player.sendMessage(said);
                }
            });
        } else {
            audience.sendMessage(said);
        }
    }
}
