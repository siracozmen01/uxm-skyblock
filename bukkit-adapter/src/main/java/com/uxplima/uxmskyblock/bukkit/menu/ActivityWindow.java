package com.uxplima.uxmskyblock.bukkit.menu;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.entity.Player;

import com.uxplima.uxmlib.common.Durations;
import com.uxplima.uxmskyblock.bukkit.i18n.DurationText;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.core.domain.activity.ActivityEventType;
import com.uxplima.uxmskyblock.core.domain.activity.ActivityQuery;
import org.jspecify.annotations.Nullable;

/**
 * An island's feed as a window: its lines a page at a time, narrowed to one member, to the kinds of line a
 * button names and to a stretch of time, and beside it a window of who put in and who took out how much.
 *
 * <p>The windows are {@code menus/island-activity.conf} and {@code menus/island-activity-totals.conf}. Every
 * filter button is written there, with what it narrows to: {@code skyblock:activity-kind:bank=BANK_DEPOSIT,
 * BANK_WITHDRAW} shows the bank lines and names the filter by {@code menu.activity.kinds.bank}, and
 * {@code skyblock:activity-since:7d} shows the last seven days. So an operator adds, removes and renames the
 * filters without a line of Java. Each viewer keeps their own filters until they leave.
 */
public final class ActivityWindow implements org.bukkit.event.Listener {

    private static final Logger LOGGER = Logger.getLogger(ActivityWindow.class.getName());

    /** The menu file of the feed. */
    public static final String FILE = "island-activity";

    /** The menu file of the bank totals. */
    public static final String TOTALS_FILE = "island-activity-totals";

    /** The lines of the feed, a list of the feed window. */
    static final String LINES = "skyblock:activity";

    /** What each member moved, a list of the totals window. */
    static final String TOTALS = "skyblock:activity-totals";

    /** Narrows the feed to the kinds its argument names, or to every kind with {@code all}. */
    static final String KIND = "skyblock:activity-kind";

    /** Narrows the feed to the time its argument names, such as {@code 7d}, or to all of it with {@code all}. */
    static final String SINCE = "skyblock:activity-since";

    /** Moves the member filter on to the next member with {@code next}, or back to everyone with {@code all}. */
    static final String MEMBER = "skyblock:activity-member";

    /** Opens the totals window under the same filters. */
    static final String OPEN_TOTALS = "skyblock:activity-open-totals";

    /** Opens the feed window under the same filters. */
    static final String OPEN_LINES = "skyblock:activity-open-lines";

    /** Which window a viewer is shown. */
    public enum View {
        LINES,
        TOTALS
    }

    /** One line of the feed as the reader reads it, and how long ago it happened. */
    public record Line(String text, String ago) {
        public Line {
            Objects.requireNonNull(text, "text must not be null");
            Objects.requireNonNull(ago, "ago must not be null");
        }
    }

    /**
     * What one member moved one way in one currency, written for the reader.
     *
     * @param in whether it went into the bank
     */
    public record Total(String player, boolean in, String amount, int lines) {
        public Total {
            Objects.requireNonNull(player, "player must not be null");
            Objects.requireNonNull(amount, "amount must not be null");
        }
    }

    /**
     * The filters one viewer has set.
     *
     * @param kind the name the kind button gave its kinds, {@code all} for every kind
     * @param period the stretch of time as the button wrote it, {@code all} for all of it
     */
    public record Filter(
            @Nullable UUID player,
            Set<ActivityEventType> types,
            String kind,
            @Nullable Duration since,
            String period) {

        static final Filter EVERYTHING = new Filter(null, Set.of(), ALL, null, ALL);

        /** No filter at all: every member, every kind, all of the time the feed keeps. */
        public static Filter everything() {
            return EVERYTHING;
        }

        public Filter {
            Objects.requireNonNull(types, "types must not be null");
            Objects.requireNonNull(kind, "kind must not be null");
            Objects.requireNonNull(period, "period must not be null");
            types = Set.copyOf(types);
        }

        /** The query these filters ask of the island's feed at {@code now}. */
        public ActivityQuery query(String instanceId, Instant now) {
            return new ActivityQuery(instanceId, player, types, since != null ? now.minus(since) : null);
        }
    }

    private static final String ALL = "all";

    private final Messages messages;
    private final Map<UUID, Filter> filters = new ConcurrentHashMap<>();
    private final Map<UUID, List<UUID>> members = new ConcurrentHashMap<>();
    private volatile @Nullable SkyblockMenuEngine engine;
    private volatile BiConsumer<Player, View> reopen = (player, view) -> {};

    public ActivityWindow(Messages messages) {
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
    }

    /** What is done when a filter changes or the other window is asked for: the feed is read again and shown. */
    public void onChange(BiConsumer<Player, View> reopen) {
        this.reopen = Objects.requireNonNull(reopen, "reopen must not be null");
    }

    /** Hands this window the engine that reads its files, and teaches the engine what its buttons do. */
    public void useMenuEngine(@Nullable SkyblockMenuEngine engine) {
        this.engine = engine;
        if (engine == null) {
            return;
        }
        engine.handedList(LINES);
        engine.handedList(TOTALS);
        engine.action(
                KIND, ctx -> changed(ctx.player(), kind(filterOf(ctx.player().getUniqueId()), ctx.arg()), View.LINES));
        engine.action(
                SINCE,
                ctx -> changed(ctx.player(), since(filterOf(ctx.player().getUniqueId()), ctx.arg()), View.LINES));
        engine.action(MEMBER, ctx -> changed(ctx.player(), member(ctx.player().getUniqueId(), ctx.arg()), View.LINES));
        engine.action(OPEN_TOTALS, ctx -> reopen.accept(ctx.player(), View.TOTALS));
        engine.action(OPEN_LINES, ctx -> reopen.accept(ctx.player(), View.LINES));
    }

    private void changed(Player player, Filter filter, View view) {
        filters.put(player.getUniqueId(), filter);
        reopen.accept(player, view);
    }

    /** The filters {@code viewer} has set, everything when they set none. */
    public Filter filterOf(UUID viewer) {
        return filters.getOrDefault(viewer, Filter.EVERYTHING);
    }

    /** Forgets a viewer who left. */
    public void forget(UUID viewer) {
        filters.remove(viewer);
        members.remove(viewer);
    }

    /** A player who leaves the server leaves their filters behind. */
    @org.bukkit.event.EventHandler
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        forget(event.getPlayer().getUniqueId());
    }

    /**
     * The filters with the kinds {@code argument} names: {@code all}, or a name, an equals sign and the kinds,
     * as {@code bank=BANK_DEPOSIT,BANK_WITHDRAW}. A kind the plugin does not know is left out with a warning.
     */
    static Filter kind(Filter filter, String argument) {
        String written = argument.strip();
        int equals = written.indexOf('=');
        if (written.isEmpty() || written.equalsIgnoreCase(ALL) || equals < 0) {
            return new Filter(filter.player(), Set.of(), ALL, filter.since(), filter.period());
        }
        Set<ActivityEventType> types = EnumSet.noneOf(ActivityEventType.class);
        for (String one : written.substring(equals + 1).split(",", -1)) {
            String name = one.strip().toUpperCase(Locale.ROOT);
            if (name.isEmpty()) {
                continue;
            }
            try {
                types.add(ActivityEventType.valueOf(name));
            } catch (IllegalArgumentException e) {
                LOGGER.log(Level.WARNING, () -> "The feed has no kind of line called " + name + "; it is left out.");
            }
        }
        String name = written.substring(0, equals).strip();
        return new Filter(filter.player(), types, name.isEmpty() ? ALL : name, filter.since(), filter.period());
    }

    /** The filters with the time {@code argument} names: {@code all}, or a stretch such as {@code 24h} or {@code 7d}. */
    static Filter since(Filter filter, String argument) {
        String written = argument.strip();
        if (written.isEmpty() || written.equalsIgnoreCase(ALL)) {
            return new Filter(filter.player(), filter.types(), filter.kind(), null, ALL);
        }
        try {
            Duration stretch = Durations.parse(written);
            if (stretch.isNegative() || stretch.isZero()) {
                throw new IllegalArgumentException("not a stretch of time");
            }
            return new Filter(filter.player(), filter.types(), filter.kind(), stretch, written);
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, () -> "The feed cannot read " + written + " as a stretch of time.");
            return filter;
        }
    }

    /** The filters of {@code viewer} with the member moved on: {@code next} or back to everyone with {@code all}. */
    Filter member(UUID viewer, String argument) {
        Filter filter = filterOf(viewer);
        List<UUID> known = members.getOrDefault(viewer, List.of());
        if (!argument.strip().equalsIgnoreCase("next") || known.isEmpty()) {
            return new Filter(null, filter.types(), filter.kind(), filter.since(), filter.period());
        }
        int at = filter.player() == null ? -1 : known.indexOf(filter.player());
        // Past the last member the filter comes back round to everyone.
        UUID next = at + 1 < known.size() ? known.get(at + 1) : null;
        return new Filter(next, filter.types(), filter.kind(), filter.since(), filter.period());
    }

    /** The words the window shows for each filter the viewer has set. */
    Map<String, String> filterWords(Player viewer, Function<UUID, String> nameOf) {
        Filter filter = filterOf(viewer.getUniqueId());
        UUID player = filter.player();
        String who = player == null ? messages.words(viewer, "@menu.activity.everyone") : nameOf.apply(player);
        String kind = messages.named(viewer, "menu.activity.kinds", filter.kind(), filter.kind());
        Duration since = filter.since();
        String period = since == null
                ? messages.words(viewer, "@menu.activity.all_time")
                : DurationText.coarse(messages, viewer, since);
        return Map.of("filter_member", who, "filter_kind", kind, "filter_period", period);
    }

    /** One row per line of the feed, the newest first. */
    static List<MenuRow> lineRows(List<Line> lines) {
        List<MenuRow> rows = new ArrayList<>(lines.size());
        for (Line line : lines) {
            rows.add(new MenuRow(Map.of("text", line.text(), "ago", line.ago()), line));
        }
        return List.copyOf(rows);
    }

    /** One row per total, which way it went written in the reader's language. */
    List<MenuRow> totalRows(Player reader, List<Total> totals) {
        List<MenuRow> rows = new ArrayList<>(totals.size());
        for (Total total : totals) {
            rows.add(new MenuRow(
                    Map.of(
                            "player", total.player(),
                            "way",
                                    messages.words(
                                            reader, total.in() ? "@menu.activity.way_in" : "@menu.activity.way_out"),
                            "amount", total.amount(),
                            "lines", Integer.toString(total.lines()),
                            "material", total.in() ? "LIME_DYE" : "RED_DYE"),
                    total));
        }
        return List.copyOf(rows);
    }

    /**
     * Shows the feed, and answers false when the operator removed its file, so the command says it in chat.
     *
     * @param known the members whose lines the feed holds, in the order the member filter moves through them
     * @param nameOf the name a member goes by
     */
    public boolean showLines(Player player, List<Line> lines, List<UUID> known, Function<UUID, String> nameOf) {
        members.put(player.getUniqueId(), List.copyOf(known));
        Map<String, String> values = new java.util.HashMap<>(filterWords(player, nameOf));
        values.put("count", Integer.toString(lines.size()));
        return open(player, FILE, values, Map.of(LINES, lineRows(lines)));
    }

    /** Shows what each member moved through the bank under the viewer's filters. */
    public boolean showTotals(Player player, List<Total> totals, Function<UUID, String> nameOf) {
        Map<String, String> values = new java.util.HashMap<>(filterWords(player, nameOf));
        values.put("count", Integer.toString(totals.size()));
        return open(player, TOTALS_FILE, values, Map.of(TOTALS, totalRows(player, totals)));
    }

    private boolean open(Player player, String file, Map<String, String> values, Map<String, List<?>> lists) {
        Objects.requireNonNull(player, "player must not be null");
        SkyblockMenuEngine current = this.engine;
        return current != null && current.open(player, file, values, lists);
    }
}
