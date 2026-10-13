package com.uxplima.uxmskyblock.bukkit.boxed;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.WorldBorder;
import org.bukkit.entity.Player;

import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.core.application.boxed.BoxedService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;

/**
 * Shows each player on a Boxed island where the box ends, as a world border of their own.
 *
 * <p>The border is the player's alone: the world's own border stays where the operator put it, and
 * every other player sees their own box. A beat on the global thread hands each player to their own
 * thread, which reads the island they stand on from memory and only sends a border when the box they
 * are in changed.
 */
public final class BoxedBorders {

    private final BoxedService service;
    private final IslandProtectionListener islands;
    private final SchedulerPort scheduler;
    private final Duration every;
    private final java.util.function.Supplier<WorldBorder> borders;
    private final Map<UUID, String> shown = new ConcurrentHashMap<>();
    private volatile java.util.function.Consumer<com.uxplima.uxmskyblock.core.domain.identity.IslandId> entered =
            islandId -> {};

    public BoxedBorders(
            BoxedService service, IslandProtectionListener islands, SchedulerPort scheduler, Duration every) {
        this(service, islands, scheduler, every, Bukkit::createWorldBorder);
    }

    /** Borders made by {@code borders}, a new one each time a player's box changes. */
    public BoxedBorders(
            BoxedService service,
            IslandProtectionListener islands,
            SchedulerPort scheduler,
            Duration every,
            java.util.function.Supplier<WorldBorder> borders) {
        this.borders = Objects.requireNonNull(borders, "borders must not be null");
        this.service = Objects.requireNonNull(service, "service must not be null");
        this.islands = Objects.requireNonNull(islands, "islands must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.every = Objects.requireNonNull(every, "every must not be null");
    }

    /** What is told of an island whose box a player is shown anew, as when they step onto it or it grows. */
    public void whenShown(java.util.function.Consumer<com.uxplima.uxmskyblock.core.domain.identity.IslandId> then) {
        this.entered = Objects.requireNonNull(then, "then must not be null");
    }

    /** Starts the beat. Closing what it returns stops it. */
    public AutoCloseable start() {
        return scheduler.repeatGlobal(this::round, every, every);
    }

    private void round() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            scheduler.onEntity(PlayerUuid.of(player.getUniqueId()), () -> show(player));
        }
    }

    /** Brings one player's border up to date, on the player's own thread. */
    public void show(Player player) {
        if (!player.isOnline()) {
            shown.remove(player.getUniqueId());
            return;
        }
        Location at = player.getLocation();
        if (at == null) {
            return;
        }
        Optional<Island> island = islands.findIslandAt(at).filter(found -> service.isBoxed(found.id()));
        Optional<IslandBounds> box = island.map(Island::bounds);
        String world = player.getWorld().getName();
        String now = box.map(bounds -> world + ":" + bounds.centerX() + ":" + bounds.centerZ() + ":" + bounds.radius())
                .orElse("");
        String before = shown.getOrDefault(player.getUniqueId(), "");
        if (now.equals(before)) {
            return;
        }
        if (box.isEmpty()) {
            player.setWorldBorder(null);
            shown.remove(player.getUniqueId());
            return;
        }
        IslandBounds bounds = box.get();
        WorldBorder border = borders.get();
        border.setCenter(bounds.centerX() + 0.5, bounds.centerZ() + 0.5);
        border.setSize(bounds.radius() * 2.0 + 1);
        border.setWarningDistance(0);
        player.setWorldBorder(border);
        shown.put(player.getUniqueId(), now);
        entered.accept(island.orElseThrow().id());
    }
}
