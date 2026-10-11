package com.uxplima.uxmskyblock.bukkit.chunkblock;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.bukkit.Material;
import org.bukkit.entity.Player;

import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.i18n.MoneyText;
import com.uxplima.uxmskyblock.bukkit.integration.economy.BankWallets;
import com.uxplima.uxmskyblock.bukkit.menu.MenuRow;
import com.uxplima.uxmskyblock.bukkit.menu.SkyblockMenuEngine;
import com.uxplima.uxmskyblock.core.application.chunkblock.ChunkBlockService;
import com.uxplima.uxmskyblock.core.application.chunkblock.ChunkUnlocks;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkPos;
import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkRequirement;
import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkTerritory;
import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkTiers;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import org.jspecify.annotations.Nullable;

/**
 * One closed chunk of a ChunkBlock island as a window: its grade, every line of its price with what is paid and what
 * is left, and buttons that pay a line, pay everything, or take its bypass. The window is
 * {@code menus/island-chunk.conf}, and a Bedrock player gets it as a form, as every menu file.
 *
 * <p>The chunks window lists the closed chunks an island can open next, one tile each, and a click on one opens this
 * window for it. So does a click on the sign over a closed chunk.
 */
public final class ChunkWindow {

    /** The menu file of one chunk. */
    public static final String FILE = "island-chunk";

    /** The lines of the chunk's price, a list of the chunk window. */
    static final String LINES = "skyblock:chunk-lines";

    /** The closed chunks an island can open next, a list of the chunks window. */
    public static final String FRONTIER = "skyblock:chunk-frontier";

    /** The verb a tile of the frontier runs: the window of its chunk. */
    static final String OPEN = "skyblock:chunk-open";

    /** The verb a line's tile runs: pays that line. */
    static final String PAY = "skyblock:chunk-pay";

    /** The verb that pays every line the player can. */
    static final String PAY_ALL = "skyblock:chunk-pay-all";

    /** The verb that takes the chunk's bypass. */
    static final String BYPASS = "skyblock:chunk-bypass";

    private final ChunkPurchase purchase;
    private final ChunkBlockService service;
    private final IslandStoragePort islands;
    private final SchedulerPort scheduler;
    private final Messages messages;
    private final Function<String, Optional<BankWallets.Kept>> currencies;
    private final Function<UUID, Optional<ProfileId>> activeProfile;
    private final BiFunction<IslandId, ProfileId, Long> levelOf;
    private final Map<UUID, ChunkPos> viewing = new ConcurrentHashMap<>();
    private volatile @Nullable SkyblockMenuEngine engine;

    /**
     * @param currencies the currency of {@code modules/bank.conf} kept under an id, for its name and icon
     * @param levelOf the island's level as the level command works it out, read off the main thread
     */
    public ChunkWindow(
            ChunkPurchase purchase,
            ChunkBlockService service,
            IslandStoragePort islands,
            SchedulerPort scheduler,
            Messages messages,
            Function<String, Optional<BankWallets.Kept>> currencies,
            Function<UUID, Optional<ProfileId>> activeProfile,
            BiFunction<IslandId, ProfileId, Long> levelOf) {
        this.purchase = Objects.requireNonNull(purchase, "purchase must not be null");
        this.service = Objects.requireNonNull(service, "service must not be null");
        this.islands = Objects.requireNonNull(islands, "islands must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
        this.currencies = Objects.requireNonNull(currencies, "currencies must not be null");
        this.activeProfile = Objects.requireNonNull(activeProfile, "activeProfile must not be null");
        this.levelOf = Objects.requireNonNull(levelOf, "levelOf must not be null");
    }

    /** Hands this window the engine that reads its file, and teaches the engine what its tiles do. */
    public void useMenuEngine(@Nullable SkyblockMenuEngine engine) {
        this.engine = engine;
        if (engine == null) {
            return;
        }
        engine.handedList(LINES);
        engine.handedList(FRONTIER);
        engine.action(
                OPEN,
                ctx -> MenuRow.handle(ctx.context(), ChunkPos.class).ifPresent(chunk -> show(ctx.player(), chunk)));
        engine.action(
                PAY,
                ctx -> MenuRow.handle(ctx.context(), ChunkRequirement.class)
                        .ifPresent(line -> clicked(ctx.player(), line)));
        engine.action(
                PAY_ALL,
                ctx -> chunkOf(ctx.player())
                        .ifPresent(chunk -> purchase.payAll(ctx.player(), chunk, () -> reopen(ctx.player()))));
        engine.action(
                BYPASS,
                ctx -> chunkOf(ctx.player())
                        .ifPresent(chunk -> purchase.bypass(ctx.player(), chunk, () -> reopen(ctx.player()))));
    }

    /** A click on a line: pays it, or reads the chunk again for a level, which nothing pays. */
    void clicked(Player player, ChunkRequirement line) {
        chunkOf(player).ifPresent(chunk -> {
            if (line.paid()) {
                purchase.payLine(player, chunk, line, () -> reopen(player));
            } else {
                show(player, chunk);
            }
        });
    }

    /** The chunk whose window the player has up, or had up last. */
    Optional<ChunkPos> chunkOf(Player player) {
        return Optional.ofNullable(viewing.get(player.getUniqueId()));
    }

    /** Opens the window of {@code chunk} of the player's island, once its price is read off the main thread. */
    public void show(Player player, ChunkPos chunk) {
        Objects.requireNonNull(chunk, "chunk must not be null");
        Optional<ProfileId> profile = activeProfile.apply(player.getUniqueId());
        if (profile.isEmpty()) {
            tell(player, "error.session_not_active");
            return;
        }
        scheduler.async(() -> {
            Optional<Island> island =
                    islands.findIslandIdByProfileId(profile.get()).flatMap(islands::findIslandById);
            if (island.isEmpty()) {
                tell(player, "error.no_island");
                return;
            }
            Optional<ChunkUnlocks.Offer> offer = purchase.unlocks()
                    .offer(island.get().id(), chunk, island.get().bounds());
            if (offer.isEmpty()) {
                tell(player, "chunkblock.not_chunkblock");
                return;
            }
            if (offer.get().place() == ChunkTerritory.Unlock.ALREADY_OPEN) {
                tell(player, "chunkblock.already_open");
                return;
            }
            long level = levelOf.apply(island.get().id(), profile.get());
            ChunkPos origin = service.territory(island.get().id())
                    .map(ChunkTerritory::origin)
                    .orElse(chunk);
            scheduler.onEntity(new PlayerUuid(player.getUniqueId()), () -> open(player, offer.get(), origin, level));
        });
    }

    private void open(Player player, ChunkUnlocks.Offer offer, ChunkPos origin, long level) {
        viewing.put(player.getUniqueId(), offer.chunk());
        SkyblockMenuEngine menus = this.engine;
        if (menus == null) {
            return;
        }
        menus.open(player, FILE, values(player, offer, origin, level), Map.of(LINES, lines(player, offer, level)));
    }

    /** Draws the window again, with what the last payment changed, for a player who still has it up. */
    private void reopen(Player player) {
        SkyblockMenuEngine menus = this.engine;
        Optional<ChunkPos> chunk = chunkOf(player);
        if (menus != null && chunk.isPresent() && menus.showing(player, FILE)) {
            show(player, chunk.get());
        }
    }

    /**
     * Everything the window may name of its chunk, spelled {@code <argument_<name>>}: {@code tier}, where it lies as
     * {@code x} and {@code z} at its middle, its {@code ring}, the island's {@code level} and the {@code needed}
     * level, the island's chunk {@code points}, the points opening it {@code gives}, how many of its {@code lines}
     * are {@code paid}, and what its {@code bypass} takes.
     */
    Map<String, String> values(Player player, ChunkUnlocks.Offer offer, ChunkPos origin, long level) {
        Map<String, String> values = new HashMap<>();
        ChunkPos chunk = offer.chunk();
        List<ChunkRequirement> paid =
                offer.tier().cost().stream().filter(ChunkRequirement::paid).toList();
        values.put("tier", messages.words(player, offer.tier().name()));
        values.put("x", Integer.toString(chunk.x() * 16 + 8));
        values.put("z", Integer.toString(chunk.z() * 16 + 8));
        values.put("ring", Integer.toString(ChunkTiers.ring(origin, chunk)));
        values.put("level", Long.toString(level));
        values.put("needed", Long.toString(needed(offer)));
        values.put("points", Long.toString(offer.points()));
        values.put("gives", Long.toString(offer.tier().points()));
        values.put("lines", Integer.toString(paid.size()));
        values.put(
                "paid",
                Long.toString(
                        paid.stream().filter(line -> offer.remaining(line) == 0).count()));
        values.put(
                "bypass",
                offer.tier().bypass().isEmpty()
                        ? messages.words(player, "@menu.chunk.no_bypass")
                        : offer.tier().bypass().stream()
                                .map(line -> amount(line, line.amount()) + " " + what(player, line))
                                .collect(Collectors.joining(", ")));
        return Map.copyOf(values);
    }

    /**
     * One row per line of the price, spelled {@code <entry_<name>>}: {@code what} it asks for, how much is
     * {@code paid} of how much is {@code needed} and how much is {@code left}, who it comes {@code from}, {@code how} a
     * click pays it, the {@code state} it is in, and the {@code material} its tile wears.
     */
    List<MenuRow> lines(Player player, ChunkUnlocks.Offer offer, long level) {
        List<MenuRow> rows = new ArrayList<>();
        for (ChunkRequirement line : offer.tier().cost()) {
            long needed = line instanceof ChunkRequirement.Level ? needed(offer) : line.amount();
            long left = line instanceof ChunkRequirement.Level ? Math.max(0, needed - level) : offer.remaining(line);
            long paid = line instanceof ChunkRequirement.Level ? Math.min(level, needed) : needed - left;
            Map<String, String> words = new HashMap<>();
            words.put("what", what(player, line));
            words.put("paid", amount(line, paid));
            words.put("needed", amount(line, needed));
            words.put("left", amount(line, left));
            words.put("from", messages.words(player, "@menu.chunk.from." + source(line)));
            words.put("how", messages.words(player, "@menu.chunk.how." + source(line)));
            words.put("state", messages.words(player, "@menu.chunk." + state(line, left)));
            words.put("material", material(line));
            rows.add(new MenuRow(words, line));
        }
        return List.copyOf(rows);
    }

    /**
     * The closed chunks an island can open next: each one beside its open ground, inside its edge, nearest the
     * first chunk first.
     */
    public static List<ChunkPos> frontier(ChunkTerritory territory, IslandBounds bounds) {
        Set<ChunkPos> next = new LinkedHashSet<>();
        for (ChunkPos open : territory.open()) {
            for (ChunkPos beside : List.of(
                    new ChunkPos(open.x() + 1, open.z()),
                    new ChunkPos(open.x() - 1, open.z()),
                    new ChunkPos(open.x(), open.z() + 1),
                    new ChunkPos(open.x(), open.z() - 1))) {
                if (territory.placeFor(beside, bounds) == ChunkTerritory.Unlock.UNLOCKED) {
                    next.add(beside);
                }
            }
        }
        ChunkPos origin = territory.origin();
        return next.stream()
                .sorted(Comparator.comparingInt((ChunkPos chunk) -> ChunkTiers.ring(origin, chunk))
                        .thenComparingInt(ChunkPos::x)
                        .thenComparingInt(ChunkPos::z))
                .toList();
    }

    /**
     * The tiles of the chunks window's frontier, one per closed chunk the island can open next, spelled
     * {@code <entry_<name>>}: its {@code tier}, where it lies as {@code x} and {@code z}, its {@code ring}, and how
     * many of its {@code lines} are {@code paid}.
     */
    public List<MenuRow> frontierRows(Player player, IslandId islandId, IslandBounds bounds) {
        Optional<ChunkTerritory> territory = service.territory(islandId);
        if (territory.isEmpty()) {
            return List.of();
        }
        List<MenuRow> rows = new ArrayList<>();
        for (ChunkPos chunk : frontier(territory.get(), bounds)) {
            purchase.unlocks().offer(islandId, chunk, bounds).ifPresent(offer -> {
                Map<String, String> values =
                        values(player, offer, territory.get().origin(), 0);
                Map<String, String> words = new HashMap<>();
                for (String name : List.of("tier", "x", "z", "ring", "lines", "paid")) {
                    words.put(name, values.get(name));
                }
                rows.add(new MenuRow(words, chunk));
            });
        }
        return List.copyOf(rows);
    }

    /** What {@code line} asks for, in the reader's language; an item by its own name in their client. */
    String what(Player player, ChunkRequirement line) {
        return switch (line) {
            case ChunkRequirement.Items items ->
                PlayerChunkPurse.material(items)
                        .map(type -> "<lang:" + type.translationKey() + ">")
                        .orElse(items.material());
            case ChunkRequirement.Currency currency ->
                currencies
                        .apply(currency.id())
                        .map(kept -> messages.words(player, kept.spec().displayName()))
                        .orElse(currency.id());
            case ChunkRequirement.Money money -> messages.words(player, "@menu.chunk.money");
            case ChunkRequirement.Points points -> messages.words(player, "@menu.chunk.points");
            case ChunkRequirement.Level held -> messages.words(player, "@menu.chunk.level");
        };
    }

    /** The item a line's tile wears. */
    String material(ChunkRequirement line) {
        return switch (line) {
            case ChunkRequirement.Items items ->
                PlayerChunkPurse.material(items).map(Material::name).orElse("BARRIER");
            case ChunkRequirement.Currency currency ->
                currencies.apply(currency.id()).map(kept -> kept.spec().icon()).orElse("SUNFLOWER");
            case ChunkRequirement.Money money -> "GOLD_INGOT";
            case ChunkRequirement.Points points -> "NETHER_STAR";
            case ChunkRequirement.Level held -> "EXPERIENCE_BOTTLE";
        };
    }

    /** Whether {@code line} is met: a level is reached or not, anything else paid or still to pay. */
    static String state(ChunkRequirement line, long left) {
        if (line instanceof ChunkRequirement.Level) {
            return left == 0 ? "reached" : "not_reached";
        }
        return left == 0 ? "done" : "owing";
    }

    /** Who pays {@code line}: the player, the island's bank, its points, or its level. */
    static String source(ChunkRequirement line) {
        return switch (line) {
            case ChunkRequirement.Points points -> "points";
            case ChunkRequirement.Level held -> "level";
            default -> ChunkPurse.handedIn(line) ? "player" : "bank";
        };
    }

    private static String amount(ChunkRequirement line, long amount) {
        return line instanceof ChunkRequirement.Money ? MoneyText.of(amount) : Long.toString(amount);
    }

    private static long needed(ChunkUnlocks.Offer offer) {
        return offer.tier().cost().stream()
                .filter(ChunkRequirement.Level.class::isInstance)
                .mapToLong(line -> ((ChunkRequirement.Level) line).of(offer.ladder()))
                .max()
                .orElse(0);
    }

    private void tell(Player player, String key) {
        scheduler.onEntity(new PlayerUuid(player.getUniqueId()), () -> messages.send(player, key));
    }
}
