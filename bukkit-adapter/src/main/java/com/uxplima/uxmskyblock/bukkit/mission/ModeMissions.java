package com.uxplima.uxmskyblock.bukkit.mission;

import org.bukkit.entity.Player;

import com.uxplima.uxmskyblock.core.domain.mission.MissionTriggerType;

/**
 * Where a mode tells the missions what one of its own events counts for. A mode that is not told counts for
 * nothing, so a server without missions runs every mode just the same.
 */
@FunctionalInterface
public interface ModeMissions {

    /** Counts nothing. */
    ModeMissions NONE = (player, trigger, target, amount) -> {};

    /** Counts {@code amount} of {@code trigger}, for {@code target}, towards the missions of {@code player}. */
    void counted(Player player, MissionTriggerType trigger, String target, long amount);
}
