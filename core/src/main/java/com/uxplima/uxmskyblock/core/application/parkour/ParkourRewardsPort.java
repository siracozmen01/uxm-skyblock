package com.uxplima.uxmskyblock.core.application.parkour;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;

/**
 * When each runner was last paid each of a course's reward lists, so a list is paid once in its period however
 * often, and on however many servers at once, a runner earns it.
 */
public interface ParkourRewardsPort {

    /**
     * Claims the list for the runner at {@code now}, in epoch milliseconds, when it was never paid to them on the
     * course or last paid at or before {@code paidBefore}. Whether the claim was won: of two claims at once one wins.
     */
    boolean claim(IslandId course, PlayerUuid runner, String list, long now, long paidBefore);
}
