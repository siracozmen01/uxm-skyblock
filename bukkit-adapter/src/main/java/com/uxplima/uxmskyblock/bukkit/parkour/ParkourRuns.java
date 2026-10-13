package com.uxplima.uxmskyblock.bukkit.parkour;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import com.uxplima.uxmskyblock.bukkit.config.ParkourConfiguration;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffectPlayer;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffects;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.core.application.parkour.ParkourService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.island.Island;

/**
 * Runs over a Parkour course: a run starts on the start plate, is saved at every checkpoint, and ends on
 * the finish plate, where its time is weighed against the runner's best. A runner who falls too far
 * below the last checkpoint is put back on it; one who leaves the course or takes too long loses the run.
 *
 * <p>Every event here runs on the thread that owns the runner. The best time is read and written on
 * the scheduler, and the answer comes back to the runner's thread.
 */
public final class ParkourRuns implements Listener {

    private static final Logger LOGGER = Logger.getLogger(ParkourRuns.class.getName());

    /** How long a runner standing on the start plate goes before being told again that a run began. */
    private static final Duration TOLD_AGAIN = Duration.ofSeconds(3);

    /** One run under way. */
    record Run(IslandId course, Instant started, Location checkpoint, Instant told) {}

    private final ParkourService service;
    private final IslandProtectionListener islands;
    private final SchedulerPort scheduler;
    private final ParkourConfiguration config;
    private final Messages messages;
    private final InteractionEffects effects;
    private final InteractionEffectPlayer effectPlayer;
    private final Clock clock;
    private final Map<UUID, Run> runs = new ConcurrentHashMap<>();
    private volatile java.util.function.Consumer<Player> whenStarted = runner -> {};
    private volatile @org.jspecify.annotations.Nullable ParkourGhosts ghosts;

    public ParkourRuns(
            ParkourService service,
            IslandProtectionListener islands,
            SchedulerPort scheduler,
            ParkourConfiguration config,
            Messages messages,
            InteractionEffects effects,
            InteractionEffectPlayer effectPlayer,
            Clock clock) {
        this.service = Objects.requireNonNull(service, "service must not be null");
        this.islands = Objects.requireNonNull(islands, "islands must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
        this.effects = Objects.requireNonNull(effects, "effects must not be null");
        this.effectPlayer = Objects.requireNonNull(effectPlayer, "effectPlayer must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /** Tells {@code listener} each time a run starts, on the runner's thread. */
    public void whenStarted(java.util.function.Consumer<Player> listener) {
        this.whenStarted = Objects.requireNonNull(listener, "listener must not be null");
    }

    /** Records each run and shows the ghost it races. */
    public void ghosts(ParkourGhosts racing) {
        this.ghosts = Objects.requireNonNull(racing, "racing must not be null");
    }

    /** The run the player has under way, for the commands and the tests. */
    public Optional<IslandId> runningOn(Player player) {
        return Optional.ofNullable(runs.get(player.getUniqueId())).map(Run::course);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onStep(PlayerInteractEvent event) {
        Block plate = event.getClickedBlock();
        if (event.getAction() != Action.PHYSICAL || plate == null) {
            return;
        }
        Material type = plate.getType();
        ParkourConfiguration.Markers markers = config.markers();
        if (type != markers.plate() && type != markers.checkpoint()) {
            return;
        }
        Optional<Island> island = islands.findIslandAt(plate.getLocation());
        if (island.isEmpty() || !service.isCourse(island.get().id())) {
            return;
        }
        Player runner = event.getPlayer();
        IslandId course = island.get().id();
        // A new location, never the block's own: moving that one would move the block.
        Location standing = new Location(plate.getWorld(), plate.getX() + 0.5, plate.getY(), plate.getZ() + 0.5);
        Material under = plate.getRelative(0, -1, 0).getType();
        if (type == markers.checkpoint()) {
            checkpoint(runner, course, standing);
        } else if (under == markers.startUnder()) {
            start(runner, course, standing);
        } else if (under == markers.finishUnder()) {
            finish(runner, course, standing);
        }
    }

    private void start(Player runner, IslandId course, Location plate) {
        Instant now = clock.instant();
        Run was = runs.get(runner.getUniqueId());
        boolean tell = was == null
                || !was.course().equals(course)
                || Duration.between(was.told(), now).compareTo(TOLD_AGAIN) >= 0;
        // Standing on the start plate starts the run again, so the clock runs from the moment the
        // runner steps off it.
        runs.put(runner.getUniqueId(), new Run(course, now, plate, tell || was == null ? now : was.told()));
        ParkourGhosts racing = ghosts;
        if (racing != null) {
            racing.started(runner, course, plate, now);
        }
        whenStarted.accept(runner);
        if (tell) {
            messages.send(runner, "parkour.started");
            effectPlayer.fire(effects, "parkour-run-started", runner);
        }
    }

    private void checkpoint(Player runner, IslandId course, Location plate) {
        Run run = runs.get(runner.getUniqueId());
        if (run == null || !run.course().equals(course) || sameBlock(run.checkpoint(), plate)) {
            return;
        }
        runs.put(runner.getUniqueId(), new Run(course, run.started(), plate, run.told()));
        messages.send(runner, "parkour.checkpoint");
        effectPlayer.fire(effects, "parkour-checkpoint", runner);
    }

    private void finish(Player runner, IslandId course, Location plate) {
        Run run = runs.get(runner.getUniqueId());
        if (run == null || !run.course().equals(course)) {
            return;
        }
        runs.remove(runner.getUniqueId());
        Duration time = Duration.between(run.started(), clock.instant());
        PlayerUuid who = PlayerUuid.of(runner.getUniqueId());
        ParkourGhosts racing = ghosts;
        com.uxplima.uxmskyblock.core.application.parkour.GhostPath path =
                racing == null ? null : racing.finished(runner, time, plate);
        effectPlayer.fire(effects, "parkour-finished", runner, said(time));
        scheduler.async(() -> {
            ParkourService.Finish finish;
            try {
                finish = service.finish(course, who, time);
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, e, () -> "A run on course " + course + " could not be counted.");
                scheduler.onEntity(who, () -> messages.send(runner, "parkour.finished", timed("time", time)));
                return;
            }
            if (racing != null
                    && path != null
                    && (finish instanceof ParkourService.Finish.First
                            || finish instanceof ParkourService.Finish.Beaten)) {
                racing.keep(course, who, time, path);
            }
            scheduler.onEntity(who, () -> told(runner, finish));
        });
    }

    private void told(Player runner, ParkourService.Finish finish) {
        switch (finish) {
            case ParkourService.Finish.First first -> {
                messages.send(runner, "parkour.first", timed("time", first.time()));
                effectPlayer.fire(effects, "parkour-record", runner, said(first.time()));
            }
            case ParkourService.Finish.Beaten beaten -> {
                messages.send(runner, "parkour.beaten", timed("time", beaten.time()), timed("was", beaten.was()));
                effectPlayer.fire(
                        effects,
                        "parkour-record",
                        runner,
                        Map.of(
                                "time", format(beaten.time()),
                                "millis", Long.toString(beaten.time().toMillis()),
                                "was", format(beaten.was())));
            }
            case ParkourService.Finish.Slower slower ->
                messages.send(runner, "parkour.slower", timed("time", slower.time()), timed("best", slower.best()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player runner = event.getPlayer();
        Run run = runs.get(runner.getUniqueId());
        if (run == null) {
            return;
        }
        if (Duration.between(run.started(), clock.instant())
                        .compareTo(config.runs().timeLimit())
                > 0) {
            runs.remove(runner.getUniqueId());
            ended(runner.getUniqueId());
            messages.send(runner, "parkour.timed_out");
            return;
        }
        Location to = event.getTo();
        Optional<Island> island = islands.findIslandAt(to);
        if (island.isEmpty() || !island.get().id().equals(run.course())) {
            runs.remove(runner.getUniqueId());
            ended(runner.getUniqueId());
            messages.send(runner, "parkour.left");
            return;
        }
        ParkourGhosts racing = ghosts;
        if (racing != null) {
            racing.moved(runner, to);
        }
        if (to.getY() < run.checkpoint().getY() - config.runs().fallDepth()) {
            Location back = run.checkpoint().clone();
            back.setYaw(to.getYaw());
            back.setPitch(to.getPitch());
            runner.setFallDistance(0);
            var unused = runner.teleportAsync(back);
            messages.send(runner, "parkour.returned");
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        runs.remove(event.getPlayer().getUniqueId());
        ended(event.getPlayer().getUniqueId());
    }

    /** A run ended without a finish: its ghost goes. */
    private void ended(UUID runner) {
        ParkourGhosts racing = ghosts;
        if (racing != null) {
            racing.ended(runner);
        }
    }

    /** A time the way a runner reads it: minutes, seconds and milliseconds. */
    public static String format(Duration time) {
        long millis = Math.max(0, time.toMillis());
        return String.format(Locale.ROOT, "%d:%02d.%03d", millis / 60_000, millis / 1000 % 60, millis % 1000);
    }

    /** What a finish says to its effect list: the time as the chat writes it, and in milliseconds. */
    private static Map<String, String> said(Duration time) {
        return Map.of("time", format(time), "millis", Long.toString(time.toMillis()));
    }

    private static net.kyori.adventure.text.minimessage.tag.resolver.TagResolver timed(String name, Duration time) {
        return Placeholder.unparsed(name, format(time));
    }

    private static boolean sameBlock(Location a, Location b) {
        return a.getBlockX() == b.getBlockX()
                && a.getBlockY() == b.getBlockY()
                && a.getBlockZ() == b.getBlockZ()
                && Objects.equals(a.getWorld(), b.getWorld());
    }
}
