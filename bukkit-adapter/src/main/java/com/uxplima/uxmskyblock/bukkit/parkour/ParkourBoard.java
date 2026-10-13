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

    /** Shows the board of the course the player stands on, or says they are on none. */
    public void show(Player player) {
        Location at = player.getLocation();
        Optional<Island> island = at == null ? Optional.empty() : islands.findIslandAt(at);
        if (island.isEmpty() || !service.isCourse(island.get().id())) {
            messages.send(player, "parkour.not_course");
            return;
        }
        IslandId course = island.get().id();
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
            List<String> names = top.stream()
                    .map(best -> {
                        String name = nameOf.apply(best.runner().value());
                        return name == null ? best.runner().value().toString().substring(0, 8) : name;
                    })
                    .toList();
            scheduler.onEntity(who, () -> {
                if (telling != null && about != null) {
                    telling.tell(player, about);
                }
                tell(player, top, names);
            });
        });
    }

    private void tell(Player player, List<ParkourPort.Best> top, List<String> names) {
        if (top.isEmpty()) {
            messages.send(player, "parkour.top_empty");
            return;
        }
        messages.sendPlain(player, "parkour.top_header");
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
