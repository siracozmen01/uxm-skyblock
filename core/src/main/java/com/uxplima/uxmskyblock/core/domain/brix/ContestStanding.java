package com.uxplima.uxmskyblock.core.domain.brix;

import java.util.Objects;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/**
 * Where an entered plot stands in a contest.
 *
 * @param island the plot
 * @param stars the stars all its votes gave
 * @param votes how many voted for it
 */
public record ContestStanding(IslandId island, int stars, int votes) {

    public ContestStanding {
        Objects.requireNonNull(island, "island must not be null");
        if (stars < 0 || votes < 0) {
            throw new IllegalArgumentException("stars and votes are not negative");
        }
    }

    /** The stars a vote gave it on average, or none for a plot nobody voted for. */
    public double average() {
        return votes == 0 ? 0 : (double) stars / votes;
    }
}
