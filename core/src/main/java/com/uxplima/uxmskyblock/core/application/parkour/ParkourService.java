package com.uxplima.uxmskyblock.core.application.parkour;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;

/**
 * The Parkour courses, held in memory so a runner stepping on a plate never waits on the database, and
 * the best times, which are read and written off the main thread.
 */
public final class ParkourService {

    /** How a finished run stands against the runner's best. */
    public sealed interface Finish {

        Duration time();

        /** The runner's first finish on the course, which is their best. */
        record First(Duration time) implements Finish {}

        /** A time better than the runner's best, which it now is. */
        record Beaten(Duration time, Duration was) implements Finish {}

        /** A time no better than the runner's best. */
        record Slower(Duration time, Duration best) implements Finish {}
    }

    private final ParkourPort port;
    private final Set<IslandId> courses = ConcurrentHashMap.newKeySet();

    public ParkourService(ParkourPort port) {
        this.port = Objects.requireNonNull(port, "port must not be null");
    }

    /** Reads every Parkour course into memory. Off the main thread, when the server starts. */
    public int prime() {
        Set<IslandId> all = port.findAll();
        courses.addAll(all);
        return all.size();
    }

    /** Makes an island a Parkour course. Off the main thread: it writes a row. */
    public void start(IslandId course) {
        Objects.requireNonNull(course, "course must not be null");
        port.add(course);
        courses.add(course);
    }

    /** Whether the island is a Parkour course. Memory only. */
    public boolean isCourse(IslandId islandId) {
        return courses.contains(islandId);
    }

    /**
     * Reads the island again after it changed: kept while its row stands, dropped once it is gone
     * because the island was erased. Off the main thread: it reads a row.
     */
    public void forget(IslandId islandId) {
        if (port.exists(islandId)) {
            courses.add(islandId);
        } else {
            courses.remove(islandId);
        }
    }

    /** Counts a finished run and says how it stands against the runner's best. Off the main thread. */
    public Finish finish(IslandId course, PlayerUuid runner, Duration time) {
        Objects.requireNonNull(course, "course must not be null");
        Objects.requireNonNull(runner, "runner must not be null");
        Objects.requireNonNull(time, "time must not be null");
        OptionalLong best = port.best(course, runner);
        port.finish(course, runner, time.toMillis());
        if (best.isEmpty()) {
            return new Finish.First(time);
        }
        Duration was = Duration.ofMillis(best.getAsLong());
        return time.compareTo(was) < 0 ? new Finish.Beaten(time, was) : new Finish.Slower(time, was);
    }

    /** The runner who has run the course fastest, and their time. Off the main thread. */
    public java.util.Optional<ParkourPort.Best> leader(IslandId course) {
        List<ParkourPort.Best> top = port.top(course, 1);
        return top.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(top.getFirst());
    }

    /** The courses finished most often, the most first. Off the main thread. */
    public List<ParkourPort.Runs> mostRun(int limit) {
        return port.mostRun(Math.max(1, limit));
    }

    /**
     * Forgets the runs on the course, of {@code runner} alone when one is named, as an operator does. Off the main
     * thread. Says how many runners' records went.
     */
    public int clear(IslandId course, java.util.Optional<PlayerUuid> runner) {
        return port.clear(course, runner);
    }

    /** The course's best runners, fastest first. Off the main thread. */
    public List<ParkourPort.Best> top(IslandId course, int limit) {
        return port.top(course, Math.max(1, limit));
    }
}
