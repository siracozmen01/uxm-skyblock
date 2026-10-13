package com.uxplima.uxmskyblock.bukkit.parkour;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import com.uxplima.uxmskyblock.bukkit.config.ParkourConfiguration;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffectPlayer;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffects;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.core.application.parkour.GhostPath;
import com.uxplima.uxmskyblock.core.application.parkour.ParkourGhostsPort;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import org.jspecify.annotations.Nullable;

/**
 * The ghosts runners race. Each run is recorded as it goes, measured from its start plate; a run that sets the
 * runner's best is kept as their ghost. As a run starts, the ghost it races is read and shown to the runner alone, and
 * it retraces its run on the runner's clock: it waits at the start while the runner stands on the plate, and stands
 * at the finish once its run is done.
 *
 * <p>The run is recorded on the runner's thread. The ghost moves on its own thread, every tick, and is read and kept
 * on the scheduler.
 */
public final class ParkourGhosts {

    private static final Logger LOGGER = Logger.getLogger(ParkourGhosts.class.getName());

    /** The most samples a run keeps, whatever the time limit and the sampling make of it. */
    private static final int MOST_SAMPLES = 100_000;

    /** How a ghost is put in the world, shown to its runner alone. */
    public interface Stage {

        /**
         * Shows the ghost of {@code whose} run to the runner, at {@code at}, and calls {@code tick} on the ghost's
         * thread every tick until it is taken away. On the runner's thread. Nothing when it cannot be shown there.
         */
        Optional<Shown> show(Player runner, Location at, PlayerUuid whose, Runnable tick);
    }

    /** A ghost in the world. */
    public interface Shown {

        /** Moves the ghost to {@code at}. On the ghost's thread. */
        void moveTo(Location at);

        /** Takes the ghost away. From any thread. */
        void remove();
    }

    /** One runner's run as it is recorded, and the ghost it races. */
    private static final class Watch {
        private final IslandId course;
        private final Location start;
        private final GhostPath.Recorder recorder;
        private volatile Instant started;
        private volatile @Nullable GhostPath path;
        private volatile @Nullable Shown shown;

        private Watch(IslandId course, Location start, Instant started, GhostPath.Recorder recorder) {
            this.course = course;
            this.start = start;
            this.started = started;
            this.recorder = recorder;
        }
    }

    private final ParkourGhostsPort port;
    private final SchedulerPort scheduler;
    private final ParkourConfiguration.Ghost config;
    private final int mostSamples;
    private final Messages messages;
    private final InteractionEffects effects;
    private final InteractionEffectPlayer effectPlayer;
    private final Clock clock;
    private final Stage stage;
    private final Map<UUID, Watch> watches = new ConcurrentHashMap<>();

    /** @param timeLimit how long a run may take, which is the longest a ghost's run can be */
    public ParkourGhosts(
            ParkourGhostsPort port,
            SchedulerPort scheduler,
            ParkourConfiguration.Ghost config,
            Duration timeLimit,
            Messages messages,
            InteractionEffects effects,
            InteractionEffectPlayer effectPlayer,
            Clock clock,
            Stage stage) {
        this.port = Objects.requireNonNull(port, "port must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.mostSamples =
                Math.clamp(timeLimit.toMillis() / config.sampleEvery().toMillis() + 1, 2, MOST_SAMPLES);
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
        this.effects = Objects.requireNonNull(effects, "effects must not be null");
        this.effectPlayer = Objects.requireNonNull(effectPlayer, "effectPlayer must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.stage = Objects.requireNonNull(stage, "stage must not be null");
    }

    /**
     * A run starts on {@code plate} at {@code now}. Standing on the plate starts it again, which starts the recording
     * and the ghost over. On the runner's thread.
     */
    void started(Player runner, IslandId course, Location plate, Instant now) {
        if (!config.enabled()) {
            return;
        }
        Watch was = watches.get(runner.getUniqueId());
        if (was != null && was.course.equals(course)) {
            was.started = now;
            was.recorder.clear();
            was.recorder.at(0, 0, 0, 0, plate.getYaw());
            return;
        }
        if (was != null) {
            hide(was);
        }
        Watch watch = new Watch(
                course,
                plate.clone(),
                now,
                new GhostPath.Recorder(config.sampleEvery().toMillis(), mostSamples));
        watch.recorder.at(0, 0, 0, 0, plate.getYaw());
        watches.put(runner.getUniqueId(), watch);
        race(runner, watch);
    }

    /** Reads the ghost the run races, then shows it on the runner's thread while the run is still the same. */
    private void race(Player runner, Watch watch) {
        PlayerUuid who = PlayerUuid.of(runner.getUniqueId());
        scheduler.async(() -> {
            Optional<ParkourGhostsPort.Ghost> ghost;
            try {
                ghost = config.against() == ParkourConfiguration.Ghost.Against.OWN
                        ? port.of(watch.course, who).or(() -> port.fastest(watch.course))
                        : port.fastest(watch.course);
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, e, () -> "The ghost on course " + watch.course + " could not be read.");
                return;
            }
            ghost.filter(found -> !found.path().poses().isEmpty())
                    .ifPresent(found -> scheduler.onEntity(who, () -> show(runner, watch, found)));
        });
    }

    private void show(Player runner, Watch watch, ParkourGhostsPort.Ghost ghost) {
        if (watches.get(runner.getUniqueId()) != watch || watch.shown != null) {
            return;
        }
        watch.path = ghost.path();
        Optional<Shown> shown = stage.show(runner, place(watch, ghost.path().at(0)), ghost.runner(), () -> tick(watch));
        if (shown.isEmpty()) {
            return;
        }
        watch.shown = shown.get();
        Duration time = Duration.ofMillis(ghost.millis());
        messages.send(runner, "parkour.ghost", Placeholder.unparsed("time", ParkourRuns.format(time)));
        effectPlayer.fire(
                effects,
                "parkour-ghost",
                runner,
                Map.of("time", ParkourRuns.format(time), "millis", Long.toString(time.toMillis())));
    }

    /** Moves the ghost to where its run was at this moment of the runner's. On the ghost's thread. */
    private void tick(Watch watch) {
        Shown shown = watch.shown;
        GhostPath path = watch.path;
        if (shown == null || path == null) {
            return;
        }
        long millis =
                Math.max(0, Duration.between(watch.started, clock.instant()).toMillis());
        shown.moveTo(place(watch, path.at(millis)));
    }

    /** Where a sample stands in the world: measured from the start plate, the item floating above it. */
    private Location place(Watch watch, GhostPath.Pose pose) {
        return new Location(
                watch.start.getWorld(),
                watch.start.getX() + pose.x(),
                watch.start.getY() + pose.y() + config.height(),
                watch.start.getZ() + pose.z(),
                pose.yaw(),
                0);
    }

    /** The runner moved during a run. On the runner's thread. */
    void moved(Player runner, Location to) {
        Watch watch = watches.get(runner.getUniqueId());
        if (watch == null || !Objects.equals(to.getWorld(), watch.start.getWorld())) {
            return;
        }
        long millis = Duration.between(watch.started, clock.instant()).toMillis();
        watch.recorder.at(
                millis,
                to.getX() - watch.start.getX(),
                to.getY() - watch.start.getY(),
                to.getZ() - watch.start.getZ(),
                to.getYaw());
    }

    /**
     * The run reached the finish after {@code time}, with the runner standing {@code at}: the ghost goes, and the run
     * is what may be kept. Nothing when no run was recorded. On the runner's thread.
     */
    @Nullable GhostPath finished(Player runner, Duration time, Location at) {
        Watch watch = watches.remove(runner.getUniqueId());
        if (watch == null) {
            return null;
        }
        hide(watch);
        return watch.recorder.until(
                time.toMillis(),
                at.getX() - watch.start.getX(),
                at.getY() - watch.start.getY(),
                at.getZ() - watch.start.getZ(),
                at.getYaw());
    }

    /** The run ended without a finish: the ghost goes and the recording with it. */
    void ended(UUID runner) {
        Watch watch = watches.remove(runner);
        if (watch != null) {
            hide(watch);
        }
    }

    /** Keeps a run that set the runner's best as their ghost. Off the main thread. */
    void keep(IslandId course, PlayerUuid runner, Duration time, GhostPath path) {
        try {
            port.keep(course, runner, time.toMillis(), path);
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, e, () -> "A ghost on course " + course + " could not be kept.");
        }
    }

    /** Whether a ghost is shown to the runner now, for the tests. */
    boolean racing(UUID runner) {
        Watch watch = watches.get(runner);
        return watch != null && watch.shown != null;
    }

    private static void hide(Watch watch) {
        Shown shown = watch.shown;
        watch.shown = null;
        if (shown != null) {
            shown.remove();
        }
    }
}
