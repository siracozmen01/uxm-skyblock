package com.uxplima.uxmskyblock.core.application.parkour;

import java.util.Map;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;

/** Where the split times of each runner's best run on each course are kept: how long the run took to each checkpoint. */
public interface ParkourSplitsPort {

    /** A checkpoint, by the block its plate stands on. */
    record Checkpoint(int x, int y, int z) {}

    /** The split times of the runner's best run on the course, by checkpoint; empty before their first finish. */
    Map<Checkpoint, Long> best(IslandId course, PlayerUuid runner);

    /**
     * Keeps {@code splits}, in milliseconds from the start, as those of the runner's best run on the course, in place
     * of the ones kept. The runner has a record on the course, and the splits go with it.
     */
    void keep(IslandId course, PlayerUuid runner, Map<Checkpoint, Long> splits);
}
