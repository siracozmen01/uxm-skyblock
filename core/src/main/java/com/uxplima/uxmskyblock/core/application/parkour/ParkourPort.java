package com.uxplima.uxmskyblock.core.application.parkour;

import java.util.List;
import java.util.OptionalLong;
import java.util.Set;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;

/** Where the Parkour courses and the runners' best times are kept. */
public interface ParkourPort {

    /** One runner's best on one course. */
    record Best(PlayerUuid runner, long millis, int runs) {}

    /** Every Parkour course. */
    Set<IslandId> findAll();

    /** Whether the island is a Parkour course. */
    boolean exists(IslandId course);

    /** Records an island as a Parkour course. A second record changes nothing. */
    void add(IslandId course);

    /** The runner's best time on the course, in milliseconds, or empty before their first finish. */
    OptionalLong best(IslandId course, PlayerUuid runner);

    /** Counts a finished run, keeping the time as the runner's best when it beats the one kept. */
    void finish(IslandId course, PlayerUuid runner, long millis);

    /**
     * Forgets the runs on the course, of {@code runner} alone when one is named, as an operator does; their next
     * finish is their first. Says how many runners' records went.
     */
    int clear(IslandId course, java.util.Optional<PlayerUuid> runner);

    /** The course's best runners, fastest first. */
    List<Best> top(IslandId course, int limit);

    /** One course and how many runs have been finished on it, by everybody. */
    record Runs(IslandId course, String name, long runs) {}

    /** The courses finished most often, the most first. */
    List<Runs> mostRun(int limit);
}
