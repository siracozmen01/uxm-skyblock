package com.uxplima.uxmskyblock.core.application.mission;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.mission.MissionDefinition;
import com.uxplima.uxmskyblock.core.domain.mission.MissionId;
import com.uxplima.uxmskyblock.core.domain.mission.MissionProgress;
import com.uxplima.uxmskyblock.core.domain.mission.MissionTriggerType;
import org.jspecify.annotations.Nullable;

/**
 * How a mission moves: a trigger, a level reached or handed-in items counted against what is still owed, the step written, and the island told when it finishes. Split out of {@link IslandMissionService}, whose services and words it uses, so neither file is over the size the
 * standards draw a line at.
 */
final class MissionAdvances {

    private static final java.util.logging.Logger LOGGER =
            java.util.logging.Logger.getLogger(MissionAdvances.class.getName());

    private final IslandMissionService host;

    MissionAdvances(IslandMissionService host) {
        this.host = host;
    }

    public List<MissionProgress> handleTrigger(
            IslandId islandId,
            ProfileId profileId,
            MissionTriggerType triggerType,
            @Nullable String target,
            long amount,
            Instant now) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(profileId, "profileId must not be null");
        Objects.requireNonNull(triggerType, "triggerType must not be null");
        Objects.requireNonNull(now, "now must not be null");
        if (amount <= 0) {
            return List.of();
        }

        Map<MissionId, MissionProgress> playerProgress = host.findAllProgress(islandId, profileId);
        List<MissionProgress> updated = new ArrayList<>();

        for (MissionDefinition def : host.countable()) {
            if (def.triggerType() != triggerType
                    || !host.matchesFilter(def.targetFilter(), target)
                    || !host.offered(islandId, def, playerProgress, now)) {
                continue;
            }
            move(islandId, profileId, def, counted -> amount, playerProgress, now, updated);
        }
        return updated;
    }
    /**
     * Moves the missions that count {@code triggerType} up to {@code reached}, a level or a balance the island
     * stands at now rather than one more of something. A mission already further on stays where it is.
     */
    public List<MissionProgress> handleReach(
            IslandId islandId, ProfileId profileId, MissionTriggerType triggerType, long reached, Instant now) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(profileId, "profileId must not be null");
        Objects.requireNonNull(triggerType, "triggerType must not be null");
        Objects.requireNonNull(now, "now must not be null");
        Map<MissionId, MissionProgress> playerProgress = host.findAllProgress(islandId, profileId);
        List<MissionProgress> updated = new ArrayList<>();
        for (MissionDefinition def : host.countable()) {
            if (def.triggerType() != triggerType || !host.offered(islandId, def, playerProgress, now)) {
                continue;
            }
            move(islandId, profileId, def, counted -> reached - counted, playerProgress, now, updated);
        }
        return updated;
    }
    /** Hands items in, crediting no more than the mission still asks for. */
    public Optional<IslandMissionService.MissionSubmission> submitManualItems(
            IslandId islandId, ProfileId profileId, MissionId missionId, long amount, Instant now) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(profileId, "profileId must not be null");
        Objects.requireNonNull(missionId, "missionId must not be null");
        Objects.requireNonNull(now, "now must not be null");
        if (amount <= 0) {
            return Optional.empty();
        }

        MissionDefinition def = host.missionCatalog.get(missionId);
        if (def == null || def.triggerType() != MissionTriggerType.ITEM_SUBMIT) {
            return Optional.empty();
        }

        Map<MissionId, MissionProgress> playerProgress = host.findAllProgress(islandId, profileId);
        if (!host.offered(islandId, def, playerProgress, now)) {
            return Optional.empty();
        }
        IslandMissionService.Advance advance = advance(playerProgress, def, amount, now);
        if (advance == null || advance.credited() <= 0) {
            return Optional.of(new IslandMissionService.MissionSubmission(
                    Optional.ofNullable(playerProgress.get(def.id()))
                            .orElseGet(() -> MissionProgress.initial(def.id())),
                    0L));
        }

        MissionProgress next = advance.progress();
        host.dirtyEntries.remove(host.dirtyKey(islandId, profileId, def.id()));
        host.storagePort.saveProgress(islandId, profileId, next);

        if (advance.finishedItNow()) {
            IslandMissionRewardPort rewards = host.rewardPort;
            if (rewards != null) {
                rewards.dispatchReward(islandId, profileId, def);
            }
            tellSomebodyItFinished(islandId, profileId, def);
        }
        return Optional.of(new IslandMissionService.MissionSubmission(next, advance.credited()));
    }

    public Optional<MissionProgress> submitManualItem(
            IslandId islandId, ProfileId profileId, MissionId missionId, long amount, Instant now) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(profileId, "profileId must not be null");
        Objects.requireNonNull(missionId, "missionId must not be null");
        Objects.requireNonNull(now, "now must not be null");
        if (amount <= 0) {
            return Optional.empty();
        }

        MissionDefinition def = host.missionCatalog.get(missionId);
        if (def == null || def.triggerType() != MissionTriggerType.ITEM_SUBMIT) {
            return Optional.empty();
        }

        return submitManualItems(islandId, profileId, missionId, amount, now)
                .map(IslandMissionService.MissionSubmission::progress);
    }
    /** Moves one mission on by what {@code toAdd} answers, writes where it stands, and pays it when this finished it. */
    void move(
            IslandId islandId,
            ProfileId profileId,
            MissionDefinition def,
            java.util.function.LongUnaryOperator toAdd,
            Map<MissionId, MissionProgress> playerProgress,
            Instant now,
            List<MissionProgress> updated) {
        IslandMissionService.Advance advance = advance(playerProgress, def, toAdd, now);
        if (advance == null) {
            return;
        }
        MissionProgress next = advance.progress();
        if (next.completed()) {
            host.dirtyEntries.remove(host.dirtyKey(islandId, profileId, def.id()));
            host.storagePort.saveProgress(islandId, profileId, next);
        } else {
            host.dirtyEntries.put(
                    host.dirtyKey(islandId, profileId, def.id()),
                    new IslandMissionService.DirtyProgressEntry(islandId, profileId, next));
        }
        updated.add(next);
        MissionId whole = host.lineOf.get(def.id());
        if (whole != null) {
            // A line pays nothing of its own: finishing it moves its mission on by one.
            MissionDefinition mission = host.missionCatalog.get(whole);
            if (advance.finishedItNow() && mission != null) {
                move(islandId, profileId, mission, counted -> 1L, playerProgress, now, updated);
            }
            return;
        }
        if (advance.finishedItNow()) {
            IslandMissionRewardPort rewards = host.rewardPort;
            if (rewards != null) {
                rewards.dispatchReward(islandId, profileId, def);
            }
            tellSomebodyItFinished(islandId, profileId, def);
        }
    }
    /**
     * Moves one mission on by {@code amount}, atomically for that mission.
     *
     * <p>Reading the progress, adding to it and writing it back leaves a window, and the triggers
     * arrive on a pool: every block a player breaks is one. Two of them read the same count and both
     * wrote the same one back, so the player mined two and the mission counted one. Worse, two that
     * read one short of the target both crossed it, and both saw themselves cross it, so the reward
     * was handed out twice.
     *
     * <p>The progress map is keyed by mission, so one {@code compute} does the whole move under that
     * key. Only the caller whose own increment crossed the line is told it finished it, and the
     * storage write and the reward stay outside the map, where slow work belongs.
     *
     * @return where the mission now stands, or null when it was already finished
     */
    IslandMissionService.@Nullable Advance advance(
            Map<MissionId, MissionProgress> playerProgress, MissionDefinition def, long amount, Instant now) {
        return advance(playerProgress, def, counted -> amount, now);
    }
    /**
     * Moves one mission on by what {@code toAdd} answers for where it stands, read under the mission's key. A level
     * or a balance reached asks for the difference between it and where the mission stands: read outside, two of
     * them read the same count and both added their difference, so the mission went past what was ever reached.
     *
     * @return where the mission now stands, or null when it was already finished or did not move
     */
    IslandMissionService.@Nullable Advance advance(
            Map<MissionId, MissionProgress> playerProgress,
            MissionDefinition def,
            java.util.function.LongUnaryOperator toAdd,
            Instant now) {
        boolean[] finishedItNow = {false};
        boolean[] moved = {false};
        long[] credited = {0L};
        MissionProgress next = playerProgress.compute(def.id(), (missionId, existing) -> {
            // A mission that came back since the last of this progress starts over before it moves on.
            MissionProgress before =
                    existing != null ? host.current(def, existing, now) : MissionProgress.initial(missionId);
            if (before.completed()) {
                return before;
            }
            long amount = toAdd.applyAsLong(before.progressCount());
            if (amount <= 0) {
                return existing;
            }
            moved[0] = true;
            // What the mission still wants is read here, under the key, not before it. Two hand ins
            // that read it outside both took what was left, the second one's items went past the
            // target, and the player was told they counted while nothing handed them back.
            long take = Math.min(amount, Math.max(0L, def.requiredAmount() - before.progressCount()));
            credited[0] = take;
            MissionProgress after = before.increment(take, def.requiredAmount(), now);
            finishedItNow[0] = after.completed();
            return after;
        });
        if (next == null || !moved[0] || (next.completed() && !finishedItNow[0])) {
            return null;
        }
        return new IslandMissionService.Advance(next, finishedItNow[0], credited[0]);
    }
    /** Tells whoever is listening, without letting them change what the mission did. */
    void tellSomebodyItFinished(IslandId islandId, ProfileId profileId, MissionDefinition def) {
        Consumer<IslandMissionService.MissionFinished> listener = host.finishedListener;
        if (listener == null) {
            return;
        }
        try {
            listener.accept(new IslandMissionService.MissionFinished(islandId, profileId, def));
        } catch (RuntimeException e) {
            LOGGER.log(
                    java.util.logging.Level.WARNING,
                    e,
                    () -> "Telling somebody that mission " + def.id() + " finished failed.");
        }
    }
}
