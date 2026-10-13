package com.uxplima.uxmskyblock.bukkit.parkour;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.entity.Player;

import com.uxplima.uxmskyblock.bukkit.config.ParkourConfiguration;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.menu.MenuRow;
import com.uxplima.uxmskyblock.bukkit.menu.SkyblockMenuEngine;
import com.uxplima.uxmskyblock.bukkit.menu.SocialWindows;
import com.uxplima.uxmskyblock.core.application.parkour.ParkourDirectoryPort;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import org.jspecify.annotations.Nullable;

/**
 * Every Parkour course in one window, the most run first, drawn by {@code menus/parkour-courses.conf}: each course's
 * name, whose island it is on, its tags, how it is rated, its record and how often it was run. A click goes there. A
 * Bedrock player gets the same file as a form.
 *
 * <p>The courses are read on the scheduler and drawn on the player's thread.
 */
public final class ParkourCourses {

    private static final Logger LOGGER = Logger.getLogger(ParkourCourses.class.getName());

    /** The menu file that lists the courses, and the list it draws them from. */
    static final String FILE = "parkour-courses";

    static final String COURSES = "parkour:courses";

    /** The most courses the window lists; more than this turn into pages there are never enough of. */
    private static final int MOST = 270;

    private final ParkourDirectoryPort port;
    private final ParkourTagging tagging;
    private final SchedulerPort scheduler;
    private final Messages messages;
    private final ParkourConfiguration.Ratings ratings;
    private final Function<UUID, @Nullable String> nameOf;
    private volatile @Nullable SkyblockMenuEngine menuEngine;

    /** @param nameOf a player's name, or null when the server never knew it */
    public ParkourCourses(
            ParkourDirectoryPort port,
            ParkourTagging tagging,
            SchedulerPort scheduler,
            Messages messages,
            ParkourConfiguration.Ratings ratings,
            Function<UUID, @Nullable String> nameOf) {
        this.port = Objects.requireNonNull(port, "port must not be null");
        this.tagging = Objects.requireNonNull(tagging, "tagging must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
        this.ratings = Objects.requireNonNull(ratings, "ratings must not be null");
        this.nameOf = Objects.requireNonNull(nameOf, "nameOf must not be null");
    }

    /** Hands the window the engine that reads its file. A click on a course is the island visit every list has. */
    public void useMenuEngine(@Nullable SkyblockMenuEngine engine) {
        this.menuEngine = engine;
        if (engine != null) {
            engine.handedList(COURSES);
        }
    }

    /** Opens the directory of courses. */
    public void open(Player player) {
        PlayerUuid who = PlayerUuid.of(player.getUniqueId());
        scheduler.async(() -> {
            List<ParkourDirectoryPort.Listing> listed;
            Map<UUID, String> owners;
            try {
                listed = port.directory(MOST);
                owners = owners(listed);
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "The Parkour courses could not be read.", e);
                scheduler.onEntity(who, () -> messages.send(player, "parkour.courses.failed"));
                return;
            }
            scheduler.onEntity(who, () -> {
                if (listed.isEmpty()) {
                    messages.send(player, "parkour.courses.none");
                    return;
                }
                SkyblockMenuEngine engine = menuEngine;
                if (engine == null
                        || !engine.open(player, FILE, Map.of(), Map.of(COURSES, rows(player, listed, owners)))) {
                    messages.send(player, "parkour.courses.no_window");
                }
            });
        });
    }

    /** Each owner's name, once each; one the server never knew is the start of their id. Off the main thread. */
    Map<UUID, String> owners(List<ParkourDirectoryPort.Listing> listed) {
        Map<UUID, String> owners = new HashMap<>();
        for (ParkourDirectoryPort.Listing listing : listed) {
            owners.computeIfAbsent(listing.owner(), owner -> {
                String name = nameOf.apply(owner);
                return name == null ? owner.toString().substring(0, 8) : name;
            });
        }
        return owners;
    }

    /** One row per course, in the order read, each a visit to its owner's island. */
    List<MenuRow> rows(Player player, List<ParkourDirectoryPort.Listing> listed, Map<UUID, String> owners) {
        List<MenuRow> rows = new ArrayList<>(listed.size());
        String none = messages.words(player, "@parkour.tag.none");
        for (ParkourDirectoryPort.Listing listing : listed) {
            String owner = owners.getOrDefault(listing.owner(), "?");
            String name = listing.name() == null || listing.name().isBlank()
                    ? messages.words(player, "@parkour.courses.unnamed").replace("<owner>", owner)
                    : listing.name();
            Map<String, String> words = new HashMap<>();
            words.put("name", name);
            words.put("owner", owner);
            words.put(
                    "difficulty",
                    listing.tags().difficulty() == null
                            ? none
                            : tagging.word(
                                    player,
                                    ParkourTagging.Kind.DIFFICULTY,
                                    listing.tags().difficulty()));
            words.put(
                    "category",
                    listing.tags().category() == null
                            ? none
                            : tagging.word(
                                    player,
                                    ParkourTagging.Kind.CATEGORY,
                                    listing.tags().category()));
            words.put(
                    "rating",
                    listing.ratings() == 0
                            ? messages.words(player, "@parkour.courses.unrated")
                            : String.format(Locale.ROOT, "%.1f/%d", listing.stars(), ratings.stars()));
            words.put("ratings", Integer.toString(listing.ratings()));
            Long record = listing.record();
            words.put(
                    "record",
                    record == null
                            ? messages.words(player, "@parkour.courses.unfinished")
                            : ParkourRuns.format(Duration.ofMillis(record)));
            words.put("runs", Long.toString(listing.runs()));
            words.put(
                    "facts",
                    ratings.enabled() ? "difficulty category rating record runs" : "difficulty category record runs");
            rows.add(new MenuRow(words, new SocialWindows.Place(name, owner)));
        }
        return List.copyOf(rows);
    }
}
