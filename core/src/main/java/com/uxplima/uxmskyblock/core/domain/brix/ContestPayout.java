package com.uxplima.uxmskyblock.core.domain.brix;

import java.util.Objects;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;

/**
 * What one member of an entered plot is owed of a contest: the list of its place, or of every entry.
 *
 * @param contest the contest's number
 * @param island the plot
 * @param player the member
 * @param place the plot's place, from 1; {@link #ENTERED} for the list every entry is paid
 */
public record ContestPayout(int contest, IslandId island, PlayerUuid player, int place) {

    /** The place of the list every entered plot is paid. */
    public static final int ENTERED = 0;

    public ContestPayout {
        Objects.requireNonNull(island, "island must not be null");
        Objects.requireNonNull(player, "player must not be null");
        if (contest < 1 || place < ENTERED) {
            throw new IllegalArgumentException("a payout is of a contest and a place");
        }
    }
}
