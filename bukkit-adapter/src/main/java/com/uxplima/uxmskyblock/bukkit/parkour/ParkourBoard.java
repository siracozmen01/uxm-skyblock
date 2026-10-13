package com.uxplima.uxmskyblock.bukkit.parkour;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.core.application.parkour.ParkourPort;
import com.uxplima.uxmskyblock.core.application.parkour.ParkourService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import org.jspecify.annotations.Nullable;

/** The best times on the course a player stands on, fastest first, as the course command shows them. */
public final class ParkourBoard {

    private static final Logger LOGGER = Logger.getLogger(ParkourBoard.class.getName());

    private final ParkourService service;
    private final IslandProtectionListener islands;
    private final SchedulerPort scheduler;
    private final Messages messages;
    private final int size;
    private final Function<UUID, @Nullable String> nameOf;
    private volatile @Nullable ParkourTagging tagging;
    private volatile com.uxplima.uxmskyblock.core.application.parkour.@Nullable ParkourSeasonService seasons;

    /**
     * @param size how many runners the board shows
     * @param nameOf a runner's name, or null when the server never knew it
     */
    public ParkourBoard(
            ParkourService service,
            IslandProtectionListener islands,
            SchedulerPort scheduler,
            Messages messages,
            int size,
            Function<UUID, @Nullable String> nameOf) {
        this.service = Objects.requireNonNull(service, "service must not be null");
        this.islands = Objects.requireNonNull(islands, "islands must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
        this.size = Math.max(1, size);
        this.nameOf = Objects.requireNonNull(nameOf, "nameOf must not be null");
    }

    /** Heads the board with the course's tags and rating. */
    public void about(ParkourTagging telling) {
        this.tagging = Objects.requireNonNull(telling, "telling must not be null");
    }

    /** Shows each season's board beside the board of all time. */
    public void seasons(com.uxplima.uxmskyblock.core.application.parkour.ParkourSeasonService boards) {
        this.seasons = Objects.requireNonNull(boards, "boards must not be null");
    }

    /**
     * Shows the board of one season on the course the player stands on: the season under way, or the one numbered.
     */
    public void showSeason(Player player, java.util.OptionalInt number) {
        Optional<IslandId> standing = course(player);
        if (standing.isEmpty()) {
            messages.send(player, "parkour.not_course");
            return;
        }
        com.uxplima.uxmskyblock.core.application.parkour.ParkourSeasonService boards = seasons;
        if (boards == null) {
            messages.send(player, "parkour.season.none");
            return;
        }
        IslandId course = standing.get();
        PlayerUuid who = PlayerUuid.of(player.getUniqueId());
        scheduler.async(() -> {
            Optional<com.uxplima.uxmskyblock.core.domain.season.SeasonRecord> season;
            List<ParkourPort.Best> top;
            try {
                season = number.isPresent() ? boards.season(number.getAsInt()) : boards.current();
                top = season.isEmpty() ? List.of() : boards.top(course, season.get(), size);
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, e, () -> "The season times on course " + course + " could not be read.");
                return;
            }
            List<String> names = names(top);
            scheduler.onEntity(who, () -> {
                if (season.isEmpty()) {
                    if (number.isPresent()) {
                        messages.send(
                                player,
                                "parkour.season.unknown",
                                Placeholder.unparsed("number", Integer.toString(number.getAsInt())));
                    } else {
                        messages.send(player, "parkour.season.none");
                    }
                    return;
                }
                TagResolver named = Placeholder.unparsed("season", season.get().name());
                if (top.isEmpty()) {
                    messages.send(player, "parkour.season.empty", named);
                    return;
                }
                messages.sendPlain(player, "parkour.season.header", named);
                entries(player, top, names);
            });
        });
    }

    /** Shows the board of the course the player stands on, or says they are on none. */
    public void show(Player player) {
        Optional<IslandId> standing = course(player);
        if (standing.isEmpty()) {
            messages.send(player, "parkour.not_course");
            return;
        }
        IslandId course = standing.get();
        PlayerUuid who = PlayerUuid.of(player.getUniqueId());
        ParkourTagging telling = tagging;
        scheduler.async(() -> {
            List<ParkourPort.Best> top;
            ParkourTagging.About about;
            try {
                top = service.top(course, size);
                about = telling == null ? null : telling.about(course);
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, e, () -> "The best times on course " + course + " could not be read.");
                return;
            }
            List<String> names = names(top);
            scheduler.onEntity(who, () -> {
                if (telling != null && about != null) {
                    telling.tell(player, about);
                }
                tell(player, top, names);
            });
        });
    }

    /** The Parkour course the player stands on. */
    private Optional<IslandId> course(Player player) {
        Location at = player.getLocation();
        Optional<Island> island = at == null ? Optional.empty() : islands.findIslandAt(at);
        return island.map(Island::id).filter(service::isCourse);
    }

    /** Each runner's name; one the server never knew is the start of their id. Off the main thread. */
    private List<String> names(List<ParkourPort.Best> top) {
        return top.stream()
                .map(best -> {
                    String name = nameOf.apply(best.runner().value());
                    return name == null ? best.runner().value().toString().substring(0, 8) : name;
                })
                .toList();
    }

    private void tell(Player player, List<ParkourPort.Best> top, List<String> names) {
        if (top.isEmpty()) {
            messages.send(player, "parkour.top_empty");
            return;
        }
        messages.sendPlain(player, "parkour.top_header");
        entries(player, top, names);
    }

    private void entries(Player player, List<ParkourPort.Best> top, List<String> names) {
        for (int rank = 0; rank < top.size(); rank++) {
            ParkourPort.Best best = top.get(rank);
            messages.sendPlain(
                    player,
                    "parkour.top_entry",
                    Placeholder.unparsed("place", Integer.toString(rank + 1)),
                    Placeholder.unparsed("player", names.get(rank)),
                    Placeholder.unparsed("time", ParkourRuns.format(Duration.ofMillis(best.millis()))),
                    Placeholder.unparsed("runs", Integer.toString(best.runs())));
        }
    }
}
