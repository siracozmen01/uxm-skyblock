package com.uxplima.uxmskyblock.core.application.mission;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import com.uxplima.uxmskyblock.core.domain.gamemode.GameModeType;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.mission.MissionBranch;
import com.uxplima.uxmskyblock.core.domain.mission.MissionDefinition;
import com.uxplima.uxmskyblock.core.domain.mission.MissionId;
import com.uxplima.uxmskyblock.core.domain.mission.MissionProgress;
import com.uxplima.uxmskyblock.core.domain.mission.MissionTriggerType;
import org.jspecify.annotations.Nullable;

/**
 * Core application service orchestrating event-driven mission progress,
 * dynamic branching trees, manual item submissions, and reward dispatches.
 */
public final class IslandMissionService {

    private static final java.util.logging.Logger LOGGER =
            java.util.logging.Logger.getLogger(IslandMissionService.class.getName());

    private final IslandMissionStoragePort storagePort;
    private volatile @Nullable IslandMissionRewardPort rewardPort;

    /**
     * Told when a mission is finished, once, by whoever finished it.
     *
     * <p>The island's activity feed wants to know, and only this class can tell it: the moment a
     * mission crosses its target is inside the advance, not in any caller. It is a listener rather
     * than a port because it is a side channel: a feed that is not listening must not change what a
     * mission does.
     */
    private volatile @Nullable Consumer<MissionFinished> finishedListener;

    private final Map<MissionId, MissionDefinition> missionCatalog = new ConcurrentHashMap<>();

    /** The lines of every mission of several, each kept as a mission of its own that pays nothing. */
    private final Map<MissionId, MissionDefinition> lines = new ConcurrentHashMap<>();

    /** The mission each line belongs to. */
    private final Map<MissionId, MissionId> lineOf = new ConcurrentHashMap<>();

    private final Map<String, Map<MissionId, MissionProgress>> progressCache = new ConcurrentHashMap<>();
    private final Map<String, DirtyProgressEntry> dirtyEntries = new ConcurrentHashMap<>();

    public record DirtyProgressEntry(IslandId islandId, ProfileId profileId, MissionProgress progress) {
        public DirtyProgressEntry {
            Objects.requireNonNull(islandId, "islandId must not be null");
            Objects.requireNonNull(profileId, "profileId must not be null");
            Objects.requireNonNull(progress, "progress must not be null");
        }
    }

    public IslandMissionService(IslandMissionStoragePort storagePort, @Nullable IslandMissionRewardPort rewardPort) {
        this.storagePort = Objects.requireNonNull(storagePort, "storagePort must not be null");
        this.rewardPort = rewardPort;
    }

    public IslandMissionService(IslandMissionStoragePort storagePort) {
        this(storagePort, null);
    }

    /**
     * The mode an island plays, read the first time a mission that names its modes asks. An island never changes
     * the mode it plays: a player who wants another mode makes another island. Until told, every island plays
     * no mode in particular and is offered only the missions that name none.
     */
    private volatile java.util.function.Function<IslandId, Optional<GameModeType>> modeOf = island -> Optional.empty();

    private final Map<IslandId, Optional<GameModeType>> modes = new ConcurrentHashMap<>();

    /** Lets go of the mode kept for an island that was erased. */
    public void forgetIsland(IslandId islandId) {
        drawn.remove(islandId);
        Objects.requireNonNull(islandId, "islandId must not be null");
        modes.remove(islandId);
    }

    /** Tells this service how the mode an island plays is read. */
    public void readModesWith(java.util.function.Function<IslandId, Optional<GameModeType>> reader) {
        this.modeOf = Objects.requireNonNull(reader, "reader must not be null");
        modes.clear();
    }

    /**
     * Whether the island is offered the mission at {@code now}: it plays one of the mission's modes, or the mission
     * names none, and the mission it follows, if any, is finished.
     */
    public boolean offered(
            IslandId islandId, MissionDefinition def, Map<MissionId, MissionProgress> progress, Instant now) {
        MissionId whole = lineOf.get(def.id());
        if (whole != null) {
            MissionDefinition mission = missionCatalog.get(whole);
            return mission != null && offered(islandId, mission, progress, now);
        }
        if (!playable(islandId, def)) {
            return false;
        }
        if (def.repeat() == com.uxplima.uxmskyblock.core.domain.mission.MissionRepeat.DAILY
                && dailySlots > 0
                && !drawnFor(islandId, now).contains(def.id())) {
            return false;
        }
        if (def.after().isEmpty()) {
            return true;
        }
        MissionDefinition first = missionCatalog.get(def.after().get());
        MissionProgress done = progress.get(def.after().get());
        // A chain whose first link the file no longer holds is offered: nobody could ever finish what is not there.
        return first == null || (done != null && current(first, done, now).completed());
    }

    /** Whether the island plays one of the mission's modes, or the mission names none. */
    private boolean playable(IslandId islandId, MissionDefinition def) {
        if (def.modes().isEmpty()) {
            return true;
        }
        Optional<GameModeType> mode = modes.computeIfAbsent(islandId, island -> modeOf.apply(island));
        return mode.isPresent() && def.playedIn(mode.get());
    }

    /** How many daily missions each island is offered a day, drawn from every one; none for no limit. */
    private volatile int dailySlots;

    /** The daily missions each island drew, with the day it drew them on. */
    private final Map<IslandId, java.util.Map.Entry<Long, java.util.Set<MissionId>>> drawn = new ConcurrentHashMap<>();

    /**
     * Offers each island only {@code slots} of the daily missions its mode plays each day, drawn afresh at the turn
     * of the day; zero offers them all.
     *
     * <p>The draw is worked out from the island, the day and each mission, not kept, so a restart or a second server
     * offers the island the same missions all day.
     */
    public void useDailySlots(int slots) {
        this.dailySlots = Math.max(0, slots);
        drawn.clear();
    }

    /** The daily missions {@code islandId} is offered on the day {@code now} falls in. */
    java.util.Set<MissionId> drawnFor(IslandId islandId, Instant now) {
        long day = now.atZone(resetZone).toLocalDate().toEpochDay();
        java.util.Map.Entry<Long, java.util.Set<MissionId>> today = drawn.get(islandId);
        if (today != null && today.getKey() == day) {
            return today.getValue();
        }
        java.util.Set<MissionId> picked = missionCatalog.values().stream()
                .filter(def -> def.repeat() == com.uxplima.uxmskyblock.core.domain.mission.MissionRepeat.DAILY)
                .filter(def -> playable(islandId, def))
                .map(MissionDefinition::id)
                .sorted(java.util.Comparator.comparingLong((MissionId id) -> java.util
                                .UUID
                                .nameUUIDFromBytes((islandId.value() + ":" + day + ":" + id.value())
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8))
                                .getMostSignificantBits())
                        .thenComparing(MissionId::value))
                .limit(dailySlots)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        drawn.put(islandId, java.util.Map.entry(day, picked));
        return picked;
    }

    /** Every mission the island is offered at {@code now}, for the profile's progress. */
    public List<MissionDefinition> offeredMissions(IslandId islandId, ProfileId profileId, Instant now) {
        Map<MissionId, MissionProgress> progress = findAllProgress(islandId, profileId);
        return missionCatalog.values().stream()
                .filter(def -> offered(islandId, def, progress, now))
                .toList();
    }

    /** The zone a day and a week turn in, for the missions that come back. The server's own until told. */
    private volatile java.time.ZoneId resetZone = java.time.ZoneId.systemDefault();

    /** Tells this service the zone its days and weeks turn in. */
    public void resetIn(java.time.ZoneId zone) {
        this.resetZone = Objects.requireNonNull(zone, "zone must not be null");
    }

    /**
     * Where {@code progress} stands at {@code now}: as stored, or started over when it was made in a day or a
     * week that is over, for a mission that comes back.
     */
    MissionProgress current(MissionDefinition def, MissionProgress progress, Instant now) {
        if (def.repeat() == com.uxplima.uxmskyblock.core.domain.mission.MissionRepeat.ONCE
                || !progress.updatedAt().isBefore(def.repeat().periodStart(now, resetZone))) {
            return progress;
        }
        return new MissionProgress(def.id(), 0L, false, null, now);
    }

    /**
     * Every mission this profile has made progress on, as it stands at {@code now}: a mission that came back since
     * reads as not started. This is what a window shows.
     */
    public Map<MissionId, MissionProgress> currentProgress(IslandId islandId, ProfileId profileId, Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        Map<MissionId, MissionProgress> current = new java.util.HashMap<>();
        findAllProgress(islandId, profileId).forEach((id, progress) -> {
            MissionDefinition def = definitionOf(id);
            current.put(id, def == null ? progress : current(def, progress, now));
        });
        return Collections.unmodifiableMap(current);
    }

    public void registerMission(MissionDefinition definition) {
        Objects.requireNonNull(definition, "definition must not be null");
        missionCatalog.put(definition.id(), definition);
    }

    public void registerMissions(Collection<MissionDefinition> definitions) {
        Objects.requireNonNull(definitions, "definitions must not be null");
        for (MissionDefinition def : definitions) {
            registerMission(def);
        }
    }

    /**
     * Gives {@code mission}, which counts {@link MissionTriggerType#LINES}, its lines: each counted on its own, and the
     * mission moved on by one as each is finished, so it is finished when every line is. A line comes back with the
     * mission and is offered while the mission is.
     */
    public void registerLines(
            MissionDefinition mission, List<com.uxplima.uxmskyblock.core.domain.mission.MissionLine> written) {
        Objects.requireNonNull(mission, "mission must not be null");
        Objects.requireNonNull(written, "written must not be null");
        if (mission.triggerType() != MissionTriggerType.LINES || written.size() != mission.requiredAmount()) {
            throw new IllegalArgumentException(
                    "A mission of lines counts LINES and needs as many as it has: " + mission.id());
        }
        for (int number = 1; number <= written.size(); number++) {
            com.uxplima.uxmskyblock.core.domain.mission.MissionLine line = written.get(number - 1);
            MissionId id = com.uxplima.uxmskyblock.core.domain.mission.MissionLine.idOf(mission.id(), number);
            lines.put(
                    id,
                    new MissionDefinition(
                            id,
                            mission.branch(),
                            mission.displayName(),
                            "",
                            line.triggerType(),
                            line.targetFilter(),
                            line.requiredAmount(),
                            com.uxplima.uxmskyblock.core.domain.mission.MissionReward.empty(),
                            mission.repeat(),
                            mission.modes(),
                            Optional.empty()));
            lineOf.put(id, mission.id());
        }
    }

    /** The lines of {@code mission}, in order, or none for a mission of one. */
    public List<MissionDefinition> linesOf(MissionId mission) {
        List<MissionDefinition> found = new ArrayList<>();
        for (int number = 1; ; number++) {
            MissionDefinition line =
                    lines.get(com.uxplima.uxmskyblock.core.domain.mission.MissionLine.idOf(mission, number));
            if (line == null) {
                return found;
            }
            found.add(line);
        }
    }

    /** A mission or a line, by its id. */
    private @Nullable MissionDefinition definitionOf(MissionId id) {
        MissionDefinition mission = missionCatalog.get(id);
        return mission != null ? mission : lines.get(id);
    }

    public Optional<MissionDefinition> findMission(MissionId missionId) {
        Objects.requireNonNull(missionId, "missionId must not be null");
        return Optional.ofNullable(missionCatalog.get(missionId));
    }

    public List<MissionDefinition> allMissions() {
        return List.copyOf(missionCatalog.values());
    }

    public List<MissionDefinition> missionsByBranch(MissionBranch branch) {
        Objects.requireNonNull(branch, "branch must not be null");
        List<MissionDefinition> result = new ArrayList<>();
        for (MissionDefinition def : missionCatalog.values()) {
            if (def.branch() == branch) {
                result.add(def);
            }
        }
        return Collections.unmodifiableList(result);
    }

    private String cacheKey(IslandId islandId, ProfileId profileId) {
        return islandId.value() + ":" + profileId.value();
    }

    public Map<MissionId, MissionProgress> findAllProgress(IslandId islandId, ProfileId profileId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(profileId, "profileId must not be null");
        String key = cacheKey(islandId, profileId);
        return progressCache.computeIfAbsent(key, k -> {
            Map<MissionId, MissionProgress> fromStorage = storagePort.findAllProgress(islandId, profileId);
            return new ConcurrentHashMap<>(fromStorage);
        });
    }

    /**
     * How many missions this profile has finished on this island.
     *
     * <p>The island level counts finished missions, weighted by {@code levels.quest-weight}. The
     * command that shows a level passed a hardcoded zero, so that weight was a number an operator
     * could configure and never see applied: a player who finished every mission on the server got
     * the same level as one who finished none.
     */
    public int countCompleted(IslandId islandId, ProfileId profileId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(profileId, "profileId must not be null");
        // A mission that comes back counts for none: the level would fall every time its day or week turned.
        int completed = 0;
        for (MissionProgress progress : findAllProgress(islandId, profileId).values()) {
            if (lineOf.containsKey(progress.missionId())) {
                continue;
            }
            MissionDefinition def = missionCatalog.get(progress.missionId());
            if (progress.completed()
                    && (def == null
                            || def.repeat() == com.uxplima.uxmskyblock.core.domain.mission.MissionRepeat.ONCE)) {
                completed++;
            }
        }
        return completed;
    }

    public Optional<MissionProgress> findProgress(IslandId islandId, ProfileId profileId, MissionId missionId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(profileId, "profileId must not be null");
        Objects.requireNonNull(missionId, "missionId must not be null");

        Map<MissionId, MissionProgress> playerProgress = findAllProgress(islandId, profileId);
        MissionProgress existing = playerProgress.get(missionId);
        if (existing != null) {
            return Optional.of(existing);
        }
        Optional<MissionProgress> fromStorage = storagePort.findProgress(islandId, profileId, missionId);
        fromStorage.ifPresent(p -> playerProgress.put(missionId, p));
        return fromStorage;
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

        Map<MissionId, MissionProgress> playerProgress = findAllProgress(islandId, profileId);
        List<MissionProgress> updated = new ArrayList<>();

        for (MissionDefinition def : countable()) {
            if (def.triggerType() != triggerType
                    || !matchesFilter(def.targetFilter(), target)
                    || !offered(islandId, def, playerProgress, now)) {
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
        Map<MissionId, MissionProgress> playerProgress = findAllProgress(islandId, profileId);
        List<MissionProgress> updated = new ArrayList<>();
        for (MissionDefinition def : countable()) {
            if (def.triggerType() != triggerType || !offered(islandId, def, playerProgress, now)) {
                continue;
            }
            move(islandId, profileId, def, counted -> reached - counted, playerProgress, now, updated);
        }
        return updated;
    }

    /** Every mission and every line, which is what a trigger counts for. */
    private List<MissionDefinition> countable() {
        List<MissionDefinition> all = new ArrayList<>(missionCatalog.values());
        all.addAll(lines.values());
        return all;
    }

    /** Moves one mission on by what {@code toAdd} answers, writes where it stands, and pays it when this finished it. */
    private void move(
            IslandId islandId,
            ProfileId profileId,
            MissionDefinition def,
            java.util.function.LongUnaryOperator toAdd,
            Map<MissionId, MissionProgress> playerProgress,
            Instant now,
            List<MissionProgress> updated) {
        Advance advance = advance(playerProgress, def, toAdd, now);
        if (advance == null) {
            return;
        }
        MissionProgress next = advance.progress();
        if (next.completed()) {
            dirtyEntries.remove(dirtyKey(islandId, profileId, def.id()));
            storagePort.saveProgress(islandId, profileId, next);
        } else {
            dirtyEntries.put(
                    dirtyKey(islandId, profileId, def.id()), new DirtyProgressEntry(islandId, profileId, next));
        }
        updated.add(next);
        MissionId whole = lineOf.get(def.id());
        if (whole != null) {
            // A line pays nothing of its own: finishing it moves its mission on by one.
            MissionDefinition mission = missionCatalog.get(whole);
            if (advance.finishedItNow() && mission != null) {
                move(islandId, profileId, mission, counted -> 1L, playerProgress, now, updated);
            }
            return;
        }
        if (advance.finishedItNow()) {
            IslandMissionRewardPort rewards = this.rewardPort;
            if (rewards != null) {
                rewards.dispatchReward(islandId, profileId, def);
            }
            tellSomebodyItFinished(islandId, profileId, def);
        }
    }

    /**
     * What a hand-in actually cost the player.
     *
     * <p>A mission that needs two more takes two, whatever the player handed over, so the caller has
     * to be told how many were credited to give the rest back. It used to be told only where the
     * mission now stood, so a player who handed in ten to a mission needing two lost eight.
     */
    public record MissionSubmission(MissionProgress progress, long credited) {}

    /** Hands items in, crediting no more than the mission still asks for. */
    public Optional<MissionSubmission> submitManualItems(
            IslandId islandId, ProfileId profileId, MissionId missionId, long amount, Instant now) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(profileId, "profileId must not be null");
        Objects.requireNonNull(missionId, "missionId must not be null");
        Objects.requireNonNull(now, "now must not be null");
        if (amount <= 0) {
            return Optional.empty();
        }

        MissionDefinition def = missionCatalog.get(missionId);
        if (def == null || def.triggerType() != MissionTriggerType.ITEM_SUBMIT) {
            return Optional.empty();
        }

        Map<MissionId, MissionProgress> playerProgress = findAllProgress(islandId, profileId);
        if (!offered(islandId, def, playerProgress, now)) {
            return Optional.empty();
        }
        Advance advance = advance(playerProgress, def, amount, now);
        if (advance == null || advance.credited() <= 0) {
            return Optional.of(new MissionSubmission(
                    Optional.ofNullable(playerProgress.get(def.id()))
                            .orElseGet(() -> MissionProgress.initial(def.id())),
                    0L));
        }

        MissionProgress next = advance.progress();
        dirtyEntries.remove(dirtyKey(islandId, profileId, def.id()));
        storagePort.saveProgress(islandId, profileId, next);

        if (advance.finishedItNow()) {
            IslandMissionRewardPort rewards = this.rewardPort;
            if (rewards != null) {
                rewards.dispatchReward(islandId, profileId, def);
            }
            tellSomebodyItFinished(islandId, profileId, def);
        }
        return Optional.of(new MissionSubmission(next, advance.credited()));
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

        MissionDefinition def = missionCatalog.get(missionId);
        if (def == null || def.triggerType() != MissionTriggerType.ITEM_SUBMIT) {
            return Optional.empty();
        }

        return submitManualItems(islandId, profileId, missionId, amount, now).map(MissionSubmission::progress);
    }

    /** A mission somebody just finished. */
    public record MissionFinished(IslandId islandId, ProfileId profileId, MissionDefinition definition) {
        public MissionFinished {
            Objects.requireNonNull(islandId, "islandId must not be null");
            Objects.requireNonNull(profileId, "profileId must not be null");
            Objects.requireNonNull(definition, "definition must not be null");
        }
    }

    /** Hands this service what pays a finished mission's reward, once for each finish. */
    public void useRewards(@Nullable IslandMissionRewardPort rewards) {
        this.rewardPort = rewards;
    }

    /** Tells this service who to tell when a mission is finished. */
    public void setFinishedListener(@Nullable Consumer<MissionFinished> listener) {
        this.finishedListener = listener;
    }

    /** Tells whoever is listening, without letting them change what the mission did. */
    private void tellSomebodyItFinished(IslandId islandId, ProfileId profileId, MissionDefinition def) {
        Consumer<MissionFinished> listener = this.finishedListener;
        if (listener == null) {
            return;
        }
        try {
            listener.accept(new MissionFinished(islandId, profileId, def));
        } catch (RuntimeException e) {
            LOGGER.log(
                    java.util.logging.Level.WARNING,
                    e,
                    () -> "Telling somebody that mission " + def.id() + " finished failed.");
        }
    }

    /**
     * One mission moved: where it now stands, whether this caller is the one that finished it, and
     * how much of what it offered the mission took.
     */
    private record Advance(MissionProgress progress, boolean finishedItNow, long credited) {}

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
    private @Nullable Advance advance(
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
    private @Nullable Advance advance(
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
                    existing != null ? current(def, existing, now) : MissionProgress.initial(missionId);
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
        return new Advance(next, finishedItNow[0], credited[0]);
    }

    /**
     * Flushes all buffered unpersisted non-completion progress increments to relational storage.
     *
     * @return count of dirty progress entries persisted
     */
    public int flushDirtyProgress() {
        if (dirtyEntries.isEmpty()) {
            return 0;
        }
        int count = 0;
        List<Map.Entry<String, DirtyProgressEntry>> snapshot = new ArrayList<>(dirtyEntries.entrySet());
        for (Map.Entry<String, DirtyProgressEntry> entry : snapshot) {
            if (dirtyEntries.remove(entry.getKey(), entry.getValue())) {
                DirtyProgressEntry val = entry.getValue();
                storagePort.saveProgress(val.islandId(), val.profileId(), val.progress());
                count++;
            }
        }
        return count;
    }

    /**
     * Flushes buffered unpersisted progress increments for the specified island and profile.
     *
     * @return count of dirty progress entries persisted
     */
    public int flushFor(IslandId islandId, ProfileId profileId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(profileId, "profileId must not be null");
        String prefix = islandId.value() + ":" + profileId.value() + ":";
        int count = 0;
        List<Map.Entry<String, DirtyProgressEntry>> snapshot = new ArrayList<>(dirtyEntries.entrySet());
        for (Map.Entry<String, DirtyProgressEntry> entry : snapshot) {
            if (entry.getKey().startsWith(prefix)) {
                if (dirtyEntries.remove(entry.getKey(), entry.getValue())) {
                    DirtyProgressEntry val = entry.getValue();
                    storagePort.saveProgress(val.islandId(), val.profileId(), val.progress());
                    count++;
                }
            }
        }
        return count;
    }

    public int dirtyEntriesCount() {
        return dirtyEntries.size();
    }

    public void invalidate(IslandId islandId, ProfileId profileId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(profileId, "profileId must not be null");
        flushFor(islandId, profileId);
        progressCache.remove(cacheKey(islandId, profileId));
    }

    private String dirtyKey(IslandId islandId, ProfileId profileId, MissionId missionId) {
        return islandId.value() + ":" + profileId.value() + ":" + missionId.value();
    }

    private boolean matchesFilter(String filter, @Nullable String target) {
        if (filter.isEmpty() || filter.equals("*")) {
            return true;
        }
        if (target == null) {
            return false;
        }
        return filter.equalsIgnoreCase(target.trim());
    }
}
