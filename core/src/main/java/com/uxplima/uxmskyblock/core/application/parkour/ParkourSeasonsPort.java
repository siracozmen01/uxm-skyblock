package com.uxplima.uxmskyblock.core.application.parkour;

import java.util.List;
import java.util.Optional;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;

/** Each runner's best time on each course in each season, beside the best of all time. */
public interface ParkourSeasonsPort {

    /** Counts a finish in season {@code season}, keeping the runner's faster time there. */
    void finish(IslandId course, int season, PlayerUuid runner, long millis);

    /** The fastest runners of a course in a season, best first. */
    List<ParkourPort.Best> top(IslandId course, int season, int limit);

    /** Forgets the season times on a course, of every runner or of one. Returns how many were forgotten. */
    int clear(IslandId course, Optional<PlayerUuid> runner);
}
