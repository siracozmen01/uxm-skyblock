package com.uxplima.uxmskyblock.core.application.parkour;

import java.util.Objects;
import java.util.Optional;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;

/** Where each runner's best run on each course is kept, as its ghost replays it. */
public interface ParkourGhostsPort {

    /** A runner's best run on a course: who ran it, how long it took, and the way it went. */
    record Ghost(PlayerUuid runner, long millis, GhostPath path) {

        public Ghost {
            Objects.requireNonNull(runner, "runner must not be null");
            Objects.requireNonNull(path, "path must not be null");
        }
    }

    /**
     * Keeps the run as the runner's ghost on the course, unless the one kept is as fast or faster. The runner has a
     * record on the course, and the ghost goes with it.
     */
    void keep(IslandId course, PlayerUuid runner, long millis, GhostPath path);

    /** The runner's own ghost on the course. */
    Optional<Ghost> of(IslandId course, PlayerUuid runner);

    /** The fastest ghost on the course, whoever ran it. */
    Optional<Ghost> fastest(IslandId course);
}
