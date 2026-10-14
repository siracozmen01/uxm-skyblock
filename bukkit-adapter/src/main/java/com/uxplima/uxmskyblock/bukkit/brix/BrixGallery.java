package com.uxplima.uxmskyblock.bukkit.brix;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.entity.Player;

import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.menu.MenuRow;
import com.uxplima.uxmskyblock.bukkit.menu.SkyblockMenuEngine;
import com.uxplima.uxmskyblock.bukkit.menu.SocialWindows;
import com.uxplima.uxmskyblock.core.application.brix.BrixContests;
import com.uxplima.uxmskyblock.core.application.brix.BrixService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.brix.Contest;
import com.uxplima.uxmskyblock.core.domain.brix.ContestStanding;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import org.jspecify.annotations.Nullable;

/**
 * Every Brix plot in one window, drawn by {@code menus/brix-gallery.conf}: whose plot it is, how large its team is,
 * and how it stands in the contest under way. The plots of the contest come first, best first; the rest follow, the
 * newest first. A click goes there. A Bedrock player gets the same file as a form.
 *
 * <p>The plots are read on the scheduler and drawn on the player's thread.
 */
public final class BrixGallery {

    private static final Logger LOGGER = Logger.getLogger(BrixGallery.class.getName());

    /** The menu file that lists the plots, and the list it draws them from. */
    static final String FILE = "brix-gallery";

    static final String PLOTS = "brix:plots";

    /** The most plots the window lists; more than this turn into pages there are never enough of. */
    static final int MOST = 270;

    private final BrixService plots;
    private final BrixContests contests;
    private final Function<IslandId, Optional<Island>> stored;
    private final SchedulerPort scheduler;
    private final Messages messages;
    private final Function<UUID, @Nullable String> nameOf;
    private volatile @Nullable SkyblockMenuEngine menuEngine;

    /**
     * @param stored the island of an id, read from the database; off the main thread
     * @param nameOf a player's name, or null when the server never knew it
     */
    public BrixGallery(
            BrixService plots,
            BrixContests contests,
            Function<IslandId, Optional<Island>> stored,
            SchedulerPort scheduler,
            Messages messages,
            Function<UUID, @Nullable String> nameOf) {
        this.plots = Objects.requireNonNull(plots, "plots must not be null");
        this.contests = Objects.requireNonNull(contests, "contests must not be null");
        this.stored = Objects.requireNonNull(stored, "stored must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
        this.nameOf = Objects.requireNonNull(nameOf, "nameOf must not be null");
    }

    /** Hands the window the engine that reads its file. A click on a plot is the island visit every list has. */
    public void useMenuEngine(@Nullable SkyblockMenuEngine engine) {
        this.menuEngine = engine;
        if (engine != null) {
            engine.handedList(PLOTS);
        }
    }

    /** Opens the gallery of plots. */
    public void open(Player player) {
        PlayerUuid who = PlayerUuid.of(player.getUniqueId());
        scheduler.async(() -> {
            List<Listed> listed;
            try {
                listed = listed();
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "The Brix plots could not be read.", e);
                scheduler.onEntity(who, () -> messages.send(player, "brix.gallery.failed"));
                return;
            }
            scheduler.onEntity(who, () -> {
                if (listed.isEmpty()) {
                    messages.send(player, "brix.gallery.none");
                    return;
                }
                SkyblockMenuEngine engine = menuEngine;
                if (engine == null || !engine.open(player, FILE, Map.of(), Map.of(PLOTS, rows(player, listed)))) {
                    messages.send(player, "brix.gallery.no_window");
                }
            });
        });
    }

    /** One plot as the window shows it: its island, its owner's name, and its standing in the contest if entered. */
    record Listed(Island island, String owner, @Nullable ContestStanding standing, boolean contest) {}

    /**
     * The plots in the order the window lists them: those entered in the contest under way best first, then the
     * rest the newest first, at most {@link #MOST}. Off the main thread.
     */
    List<Listed> listed() {
        Optional<Contest> under = contests.current();
        Map<IslandId, ContestStanding> standings = new HashMap<>();
        List<IslandId> placed = new ArrayList<>();
        under.ifPresent(contest -> {
            for (ContestStanding standing : contests.standings(contest.number())) {
                standings.put(standing.island(), standing);
                placed.add(standing.island());
            }
        });
        List<Island> rest = new ArrayList<>();
        List<Island> entered = new ArrayList<>();
        for (IslandId plot : plots.all()) {
            Optional<Island> island = stored.apply(plot);
            if (island.isEmpty()) {
                continue;
            }
            if (standings.containsKey(plot)) {
                entered.add(island.get());
            } else {
                rest.add(island.get());
            }
        }
        entered.sort(Comparator.comparingInt(island -> placed.indexOf(island.id())));
        rest.sort(Comparator.comparing(Island::createdAt).reversed());
        Map<UUID, String> names = new HashMap<>();
        List<Listed> listed = new ArrayList<>();
        for (Island island : concat(entered, rest)) {
            if (listed.size() == MOST) {
                break;
            }
            UUID owner = island.ownerPlayerUuid().value();
            String name = names.computeIfAbsent(owner, id -> {
                String known = nameOf.apply(id);
                return known == null ? id.toString().substring(0, 8) : known;
            });
            listed.add(new Listed(island, name, standings.get(island.id()), under.isPresent()));
        }
        return List.copyOf(listed);
    }

    /** One row per plot, in the order listed, each a visit to its owner's island. */
    List<MenuRow> rows(Player player, List<Listed> listed) {
        List<MenuRow> rows = new ArrayList<>(listed.size());
        for (Listed plot : listed) {
            String name = messages.words(player, "@brix.gallery.named").replace("<owner>", plot.owner());
            Map<String, String> words = new HashMap<>();
            words.put("name", name);
            words.put("owner", plot.owner());
            words.put("team", Integer.toString(plot.island().members().size()));
            ContestStanding standing = plot.standing();
            String contest;
            if (!plot.contest()) {
                contest = messages.words(player, "@brix.gallery.no_contest");
            } else if (standing == null) {
                contest = messages.words(player, "@brix.gallery.not_entered");
            } else {
                contest = messages.words(player, "@brix.gallery.standing")
                        .replace("<average>", String.format(Locale.ROOT, "%.1f", standing.average()))
                        .replace("<votes>", Integer.toString(standing.votes()));
            }
            words.put("contest", contest);
            rows.add(new MenuRow(words, new SocialWindows.Place(name, plot.owner())));
        }
        return List.copyOf(rows);
    }

    private static List<Island> concat(List<Island> first, List<Island> then) {
        List<Island> all = new ArrayList<>(first);
        all.addAll(then);
        return all;
    }
}
