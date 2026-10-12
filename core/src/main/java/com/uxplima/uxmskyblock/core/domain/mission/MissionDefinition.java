package com.uxplima.uxmskyblock.core.domain.mission;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.uxplima.uxmskyblock.core.domain.gamemode.GameModeType;

/**
 * One mission. It belongs to the modes it names, or to every mode when it names none, and is offered once the
 * mission it follows is finished, which makes a chain of missions one after another.
 *
 * @param modes the modes an island plays for the mission to be offered to it; empty for every mode
 * @param after the mission that has to be finished first, or nothing for one offered from the start
 */
public record MissionDefinition(
        MissionId id,
        MissionBranch branch,
        String displayName,
        String description,
        MissionTriggerType triggerType,
        String targetFilter,
        long requiredAmount,
        MissionReward reward,
        MissionRepeat repeat,
        Set<GameModeType> modes,
        Optional<MissionId> after) {

    public MissionDefinition {
        Objects.requireNonNull(repeat, "repeat must not be null");
        modes = Set.copyOf(Objects.requireNonNull(modes, "modes must not be null"));
        Objects.requireNonNull(after, "after must not be null");
        if (after.isPresent() && after.get().equals(id)) {
            throw new IllegalArgumentException("A mission cannot follow itself: " + id);
        }
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(branch, "branch must not be null");
        Objects.requireNonNull(displayName, "displayName must not be null");
        Objects.requireNonNull(description, "description must not be null");
        Objects.requireNonNull(triggerType, "triggerType must not be null");
        targetFilter = (targetFilter == null) ? "" : targetFilter.trim();
        if (requiredAmount <= 0) {
            throw new IllegalArgumentException("requiredAmount must be positive: " + requiredAmount);
        }
        reward = (reward == null) ? MissionReward.empty() : reward;
    }

    /** A mission for every mode, offered from the start. */
    public MissionDefinition(
            MissionId id,
            MissionBranch branch,
            String displayName,
            String description,
            MissionTriggerType triggerType,
            String targetFilter,
            long requiredAmount,
            MissionReward reward,
            MissionRepeat repeat) {
        this(
                id,
                branch,
                displayName,
                description,
                triggerType,
                targetFilter,
                requiredAmount,
                reward,
                repeat,
                Set.of(),
                Optional.empty());
    }

    /** Whether an island playing {@code mode} is offered the mission. */
    public boolean playedIn(GameModeType mode) {
        return modes.isEmpty() || modes.contains(mode);
    }

    /** A mission finished once, as every mission was before missions came back. */
    public MissionDefinition(
            MissionId id,
            MissionBranch branch,
            String displayName,
            String description,
            MissionTriggerType triggerType,
            String targetFilter,
            long requiredAmount,
            MissionReward reward) {
        this(
                id,
                branch,
                displayName,
                description,
                triggerType,
                targetFilter,
                requiredAmount,
                reward,
                MissionRepeat.ONCE);
    }
}
