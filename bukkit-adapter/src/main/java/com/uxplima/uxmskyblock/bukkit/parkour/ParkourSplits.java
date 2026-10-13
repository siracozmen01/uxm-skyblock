package com.uxplima.uxmskyblock.bukkit.parkour;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Location;

import com.uxplima.uxmskyblock.core.application.parkour.ParkourSplitsPort;
import com.uxplima.uxmskyblock.core.application.parkour.ParkourSplitsPort.Checkpoint;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import org.jspecify.annotations.Nullable;

/**
 * The split times of a run: how long it took to each checkpoint, set against the same checkpoint on the runner's best
 * run. The best run's splits are read on the scheduler as the run starts; a run that sets the runner's best keeps its
 * own in their place.
 */
public final class ParkourSplits {

    private static final Logger LOGGER = Logger.getLogger(ParkourSplits.class.getName());

    /**
     * A checkpoint reached for the first time in a run.
     *
     * @param millis how long the run took to it
     * @param best how long the runner's best run took to it, when it reached it
     */
    public record Split(long millis, @Nullable Long best) {}

    /** One runner's run: the course, the splits so far, and those of their best. */
    private static final class Splits {
        private final IslandId course;
        private final Map<Checkpoint, Long> now = new ConcurrentHashMap<>();
        private volatile Map<Checkpoint, Long> best = Map.of();

        private Splits(IslandId course) {
            this.course = course;
        }
    }

    private final ParkourSplitsPort port;
    private final SchedulerPort scheduler;
    private final boolean enabled;
    private final Map<UUID, Splits> runs = new ConcurrentHashMap<>();

    public ParkourSplits(ParkourSplitsPort port, SchedulerPort scheduler, boolean enabled) {
        this.port = Objects.requireNonNull(port, "port must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.enabled = enabled;
    }

    /** A run starts, or starts again on the plate, which drops the splits so far. On the runner's thread. */
    void started(UUID runner, IslandId course) {
        if (!enabled) {
            return;
        }
        Splits was = runs.get(runner);
        if (was != null && was.course.equals(course)) {
            was.now.clear();
            return;
        }
        Splits splits = new Splits(course);
        runs.put(runner, splits);
        PlayerUuid who = PlayerUuid.of(runner);
        scheduler.async(() -> {
            try {
                splits.best = port.best(course, who);
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, e, () -> "The split times on course " + course + " could not be read.");
            }
        });
    }

    /** The runner reached the checkpoint on {@code plate} after {@code millis}: the split, the first time in the run. */
    Optional<Split> reached(UUID runner, Location plate, long millis) {
        Splits splits = runs.get(runner);
        if (splits == null) {
            return Optional.empty();
        }
        Checkpoint checkpoint = new Checkpoint(plate.getBlockX(), plate.getBlockY(), plate.getBlockZ());
        if (splits.now.putIfAbsent(checkpoint, millis) != null) {
            return Optional.empty();
        }
        return Optional.of(new Split(millis, splits.best.get(checkpoint)));
    }

    /** The run reached the finish: its splits, which a new best keeps. Nothing when none were taken. */
    Optional<Map<Checkpoint, Long>> finished(UUID runner) {
        Splits splits = runs.remove(runner);
        return splits == null ? Optional.empty() : Optional.of(Map.copyOf(splits.now));
    }

    /** The run ended without a finish. */
    void ended(UUID runner) {
        runs.remove(runner);
    }

    /** Keeps a new best's splits. Off the main thread. */
    void keep(IslandId course, PlayerUuid runner, Map<Checkpoint, Long> splits) {
        try {
            port.keep(course, runner, splits);
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, e, () -> "The split times on course " + course + " could not be kept.");
        }
    }
}
