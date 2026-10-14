package com.uxplima.uxmskyblock.core.domain.brix;

import java.time.Instant;
import java.util.Objects;

/**
 * A Brix build contest: plots are entered and built from its opening, votes are taken once the building ends, and it
 * is settled once the votes end.
 *
 * @param number the contest's number, one past the last
 * @param theme what the plots are built on
 * @param opensAt when it opened
 * @param buildsUntil when the building ends and the votes begin
 * @param votesUntil when the votes end
 * @param settled whether its places have been given
 */
public record Contest(
        int number, String theme, Instant opensAt, Instant buildsUntil, Instant votesUntil, boolean settled) {

    /** Where a contest stands. */
    public enum Phase {
        /** Plots are entered and built. */
        BUILDING,
        /** Votes are taken. */
        VOTING,
        /** The votes have ended. */
        ENDED
    }

    public Contest {
        Objects.requireNonNull(theme, "theme must not be null");
        Objects.requireNonNull(opensAt, "opensAt must not be null");
        Objects.requireNonNull(buildsUntil, "buildsUntil must not be null");
        Objects.requireNonNull(votesUntil, "votesUntil must not be null");
        if (number < 1 || theme.isBlank() || buildsUntil.isBefore(opensAt) || votesUntil.isBefore(buildsUntil)) {
            throw new IllegalArgumentException("a contest has a number, a theme, and its windows in order");
        }
    }

    /** Where the contest stands at {@code now}. */
    public Phase phaseAt(Instant now) {
        if (now.isBefore(buildsUntil)) {
            return Phase.BUILDING;
        }
        return now.isBefore(votesUntil) ? Phase.VOTING : Phase.ENDED;
    }

    public Contest settle() {
        return new Contest(number, theme, opensAt, buildsUntil, votesUntil, true);
    }
}
