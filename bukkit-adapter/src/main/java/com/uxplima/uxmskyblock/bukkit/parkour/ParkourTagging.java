package com.uxplima.uxmskyblock.bukkit.parkour;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import com.uxplima.uxmskyblock.bukkit.config.ParkourConfiguration;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.core.application.parkour.ParkourService;
import com.uxplima.uxmskyblock.core.application.parkour.ParkourTagsPort;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandPermission;
import org.jspecify.annotations.Nullable;

/**
 * A course's tags and rating: its team gives it a difficulty and a category from the operator's lists, and a runner
 * who finished it, and is not of its team, rates it. The course board says both.
 *
 * <p>The player's island is read on their own thread; the tags and stars are read and written on the scheduler.
 */
public final class ParkourTagging {

    private static final Logger LOGGER = Logger.getLogger(ParkourTagging.class.getName());

    /** Which of a course's tags. */
    public enum Kind {
        DIFFICULTY("difficulties"),
        CATEGORY("categories");

        private final String group;

        Kind(String group) {
            this.group = group;
        }
    }

    /** What the board says of a course: its tags and how it is rated. */
    record About(ParkourTagsPort.Tags tags, ParkourTagsPort.Rating rating) {}

    private final ParkourService service;
    private final ParkourTagsPort port;
    private final IslandProtectionListener islands;
    private final SchedulerPort scheduler;
    private final Messages messages;
    private final ParkourConfiguration.Tagging tagging;
    private final ParkourConfiguration.Ratings ratings;

    public ParkourTagging(
            ParkourService service,
            ParkourTagsPort port,
            IslandProtectionListener islands,
            SchedulerPort scheduler,
            Messages messages,
            ParkourConfiguration.Tagging tagging,
            ParkourConfiguration.Ratings ratings) {
        this.service = Objects.requireNonNull(service, "service must not be null");
        this.port = Objects.requireNonNull(port, "port must not be null");
        this.islands = Objects.requireNonNull(islands, "islands must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
        this.tagging = Objects.requireNonNull(tagging, "tagging must not be null");
        this.ratings = Objects.requireNonNull(ratings, "ratings must not be null");
    }

    /** The names the operator lets a course be tagged with, of {@code kind}. */
    public List<String> names(Kind kind) {
        return kind == Kind.DIFFICULTY ? tagging.difficulties() : tagging.categories();
    }

    /**
     * Gives the course the player stands on the tag {@code name} of {@code kind}, or takes it off when there is no
     * name. Only its team, with the right to change the island's settings, tags a course.
     */
    public void tag(Player player, Kind kind, @Nullable String name) {
        Optional<Island> island = courseUnder(player);
        if (island.isEmpty()) {
            messages.send(player, "parkour.not_course");
            return;
        }
        PlayerUuid who = PlayerUuid.of(player.getUniqueId());
        boolean allowed = island.get().members().values().stream()
                .anyMatch(member -> member.playerUuid().equals(who)
                        && member.role().hasPermission(IslandPermission.SETTINGS_MODIFY));
        if (!allowed) {
            messages.send(player, "parkour.tag.not_team");
            return;
        }
        String chosen = name == null ? null : name.trim().toLowerCase(Locale.ROOT);
        if (chosen != null && !names(kind).contains(chosen)) {
            messages.send(
                    player,
                    "parkour.tag.unknown",
                    Placeholder.unparsed("name", chosen),
                    Placeholder.unparsed(
                            "choices",
                            names(kind).stream()
                                    .map(each -> word(player, kind, each))
                                    .collect(Collectors.joining(", "))));
            return;
        }
        IslandId course = island.get().id();
        scheduler.async(() -> {
            try {
                ParkourTagsPort.Tags was = port.tags(course);
                port.tag(
                        course,
                        kind == Kind.DIFFICULTY
                                ? new ParkourTagsPort.Tags(chosen, was.category())
                                : new ParkourTagsPort.Tags(was.difficulty(), chosen));
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, e, () -> "Course " + course + " could not be tagged.");
                scheduler.onEntity(who, () -> messages.send(player, "parkour.tag.failed"));
                return;
            }
            scheduler.onEntity(who, () -> {
                if (chosen == null) {
                    messages.send(player, "parkour.tag.cleared." + kind.name().toLowerCase(Locale.ROOT));
                } else {
                    messages.send(
                            player,
                            "parkour.tag.set." + kind.name().toLowerCase(Locale.ROOT),
                            Placeholder.unparsed("tag", word(player, kind, chosen)));
                }
            });
        });
    }

    /** The player rates the course they stand on {@code stars}: a runner who finished it, not of its team. */
    public void rate(Player player, int stars) {
        if (!ratings.enabled()) {
            messages.send(player, "parkour.rate.off");
            return;
        }
        Optional<Island> island = courseUnder(player);
        if (island.isEmpty()) {
            messages.send(player, "parkour.not_course");
            return;
        }
        PlayerUuid who = PlayerUuid.of(player.getUniqueId());
        if (island.get().members().values().stream()
                .anyMatch(member -> member.playerUuid().equals(who))) {
            messages.send(player, "parkour.rate.own");
            return;
        }
        if (stars < 1 || stars > ratings.stars()) {
            messages.send(
                    player, "parkour.rate.range", Placeholder.unparsed("most", Integer.toString(ratings.stars())));
            return;
        }
        IslandId course = island.get().id();
        scheduler.async(() -> {
            boolean finished;
            try {
                finished = service.best(course, who).isPresent();
                if (finished) {
                    port.rate(course, who, stars);
                }
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, e, () -> "Course " + course + " could not be rated.");
                scheduler.onEntity(who, () -> messages.send(player, "parkour.rate.failed"));
                return;
            }
            scheduler.onEntity(who, () -> {
                if (!finished) {
                    messages.send(player, "parkour.rate.not_finished");
                    return;
                }
                messages.send(
                        player,
                        "parkour.rate.done",
                        Placeholder.unparsed("stars", Integer.toString(stars)),
                        Placeholder.unparsed("most", Integer.toString(ratings.stars())));
            });
        });
    }

    /** The course's tags and rating. Off the main thread. */
    About about(IslandId course) {
        return new About(port.tags(course), ratings.enabled() ? port.rating(course) : ParkourTagsPort.Rating.NONE);
    }

    /** Says the course's tags and rating to the player, as the board heads its times. */
    void tell(Player player, About about) {
        ParkourTagsPort.Tags tags = about.tags();
        String none = messages.words(player, "@parkour.tag.none");
        messages.sendPlain(
                player,
                "parkour.about_tags",
                Placeholder.unparsed(
                        "difficulty",
                        tags.difficulty() == null ? none : word(player, Kind.DIFFICULTY, tags.difficulty())),
                Placeholder.unparsed(
                        "category", tags.category() == null ? none : word(player, Kind.CATEGORY, tags.category())));
        if (!ratings.enabled()) {
            return;
        }
        ParkourTagsPort.Rating rating = about.rating();
        if (rating.count() == 0) {
            messages.sendPlain(player, "parkour.about_unrated");
            return;
        }
        messages.sendPlain(
                player,
                "parkour.about_rating",
                Placeholder.unparsed("rating", String.format(Locale.ROOT, "%.1f", rating.average())),
                Placeholder.unparsed("most", Integer.toString(ratings.stars())),
                Placeholder.unparsed("count", Integer.toString(rating.count())));
    }

    /** A tag as the player reads it: the language files' word for it, or the name as written. */
    String word(Player player, Kind kind, String name) {
        String key = "parkour." + kind.group + "." + name;
        return messages.raw(player, key) == null ? name : messages.words(player, "@" + key);
    }

    private Optional<Island> courseUnder(Player player) {
        Location at = player.getLocation();
        Optional<Island> island = at == null ? Optional.empty() : islands.findIslandAt(at);
        return island.filter(found -> service.isCourse(found.id()));
    }
}
