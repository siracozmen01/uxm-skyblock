package com.uxplima.uxmskyblock.bukkit.top;

import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.i18n.MoneyText;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.leaderboard.LeaderboardCategory;
import com.uxplima.uxmskyblock.core.domain.leaderboard.LeaderboardEntry;
import org.jspecify.annotations.Nullable;

/**
 * Keeps every display up to date: once a minute it reads each board the displays show, off the game's threads, and
 * draws each display on the thread that owns its block.
 */
public final class TopDisplayBeat implements AutoCloseable {

    private static final Duration EVERY = Duration.ofMinutes(1);

    private final TopDisplays displays;
    private final BiFunction<LeaderboardCategory, Integer, List<LeaderboardEntry>> boards;
    private final Function<IslandId, Optional<UUID>> ownerOf;
    private final Function<UUID, Optional<String>> nameOf;
    private final Messages messages;
    private final SchedulerPort scheduler;
    private final TopPainter painter;
    private volatile @Nullable AutoCloseable task;

    /**
     * @param boards the first places of a board, as many as asked for
     * @param ownerOf the player who owns an island
     * @param nameOf a player's name
     */
    public TopDisplayBeat(
            TopDisplays displays,
            BiFunction<LeaderboardCategory, Integer, List<LeaderboardEntry>> boards,
            Function<IslandId, Optional<UUID>> ownerOf,
            Function<UUID, Optional<String>> nameOf,
            Messages messages,
            SchedulerPort scheduler,
            TopPainter painter) {
        this.displays = Objects.requireNonNull(displays, "displays must not be null");
        this.boards = Objects.requireNonNull(boards, "boards must not be null");
        this.ownerOf = Objects.requireNonNull(ownerOf, "ownerOf must not be null");
        this.nameOf = Objects.requireNonNull(nameOf, "nameOf must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.painter = Objects.requireNonNull(painter, "painter must not be null");
    }

    public TopDisplays displays() {
        return displays;
    }

    public TopPainter painter() {
        return painter;
    }

    /** Starts drawing every display once a minute. */
    public void start() {
        if (task == null) {
            task = scheduler.repeatAsync(this::refresh, Duration.ofSeconds(5), EVERY);
        }
    }

    /** Draws every display now, from boards read here; call it off the game's threads. */
    public void refresh() {
        List<TopDisplay> all = displays.all();
        Map<LeaderboardCategory, Integer> deepest = new EnumMap<>(LeaderboardCategory.class);
        for (TopDisplay display : all) {
            deepest.merge(display.board(), display.rank(), Math::max);
        }
        Map<LeaderboardCategory, List<LeaderboardEntry>> read = new EnumMap<>(LeaderboardCategory.class);
        deepest.forEach((board, rank) -> read.put(board, boards.apply(board, rank)));
        for (TopDisplay display : all) {
            draw(display, read.getOrDefault(display.board(), List.of()));
        }
    }

    /** Draws {@code display} now, from its board read here; call it off the game's threads. */
    public void refresh(TopDisplay display) {
        draw(display, boards.apply(display.board(), display.rank()));
    }

    private void draw(TopDisplay display, List<LeaderboardEntry> board) {
        Optional<TopPainter.Shown> shown = board.stream()
                .filter(entry -> entry.rank() == display.rank())
                .findFirst()
                .map(entry -> shown(display.board(), entry));
        scheduler.onRegion(display.world(), display.x() >> 4, display.z() >> 4, () -> painter.paint(display, shown));
    }

    /** The words a display shows for {@code entry}, chosen here where the owner's name may be read. */
    TopPainter.Shown shown(LeaderboardCategory board, LeaderboardEntry entry) {
        Optional<UUID> owner = ownerOf.apply(entry.islandId());
        String ownerName = owner.flatMap(nameOf).orElse("");
        String island;
        if (entry.named()) {
            island = entry.islandName();
        } else if (!ownerName.isEmpty()) {
            island = plain("leaderboard.owned", "owner", ownerName);
        } else {
            island = plain(
                    "leaderboard.unnamed",
                    "id",
                    entry.islandId().value().toString().substring(0, 8));
        }
        String score = board == LeaderboardCategory.LEVEL
                ? plain("leaderboard.score_level", "island_level", Long.toString(entry.score()))
                : plain("leaderboard.score_money", "amount", MoneyText.of(entry.score()));
        String boardName = PlainTextComponentSerializer.plainText()
                .serialize(messages.renderPlain(
                        Audience.empty(), "leaderboard.category_" + board.name().toLowerCase(Locale.ROOT)));
        return new TopPainter.Shown(boardName, island, ownerName, owner.orElse(null), score);
    }

    private String plain(String key, String name, String value) {
        return PlainTextComponentSerializer.plainText()
                .serialize(messages.renderPlain(Audience.empty(), key, Placeholder.unparsed(name, value)));
    }

    @Override
    public void close() {
        AutoCloseable running = task;
        task = null;
        if (running != null) {
            try {
                running.close();
            } catch (Exception ignored) {
                // Stopping is best effort, and a stopped beat draws nothing more.
            }
        }
    }
}
