package com.uxplima.uxmskyblock.core.domain.mission;

import java.util.Objects;

/**
 * One requirement of a mission of several lines: what it counts, for what, and how many.
 *
 * @param targetFilter the target counted, or empty or {@code *} for anything
 */
public record MissionLine(MissionTriggerType triggerType, String targetFilter, long requiredAmount) {

    public MissionLine {
        Objects.requireNonNull(triggerType, "triggerType must not be null");
        targetFilter = targetFilter == null ? "" : targetFilter.trim();
        if (requiredAmount <= 0) {
            throw new IllegalArgumentException("requiredAmount must be positive: " + requiredAmount);
        }
        if (triggerType == MissionTriggerType.LINES || triggerType == MissionTriggerType.ITEM_SUBMIT) {
            throw new IllegalArgumentException("A line cannot count " + triggerType);
        }
    }

    /** The id the line's own progress is kept under: the mission's, with its number after a hash. */
    public static MissionId idOf(MissionId mission, int number) {
        return MissionId.of(mission.value() + "#" + number);
    }
}
