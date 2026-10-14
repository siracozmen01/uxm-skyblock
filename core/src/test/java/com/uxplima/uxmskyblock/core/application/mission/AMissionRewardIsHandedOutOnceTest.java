package com.uxplima.uxmskyblock.core.application.mission;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.mission.MissionBranch;
import com.uxplima.uxmskyblock.core.domain.mission.MissionDefinition;
import com.uxplima.uxmskyblock.core.domain.mission.MissionId;
import com.uxplima.uxmskyblock.core.domain.mission.MissionProgress;
import com.uxplima.uxmskyblock.core.domain.mission.MissionReward;
import com.uxplima.uxmskyblock.core.domain.mission.MissionTriggerType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A mission is finished once and pays once, however fast the player is.
 *
 * <p>Every block a player breaks is a trigger, and every trigger is handed to a pool. The progress
 * was read, added to and written back with nothing held, so two triggers read the same count and
 * both wrote the same one back: the player mined two and the mission counted one. Two that read one
 * short of the target both crossed it and both believed they had crossed it, so the reward was
 * dispatched twice.
 */
class AMissionRewardIsHandedOutOnceTest {

    private static final IslandId ISLAND = IslandId.of(UUID.randomUUID());
    private static final ProfileId PROFILE = new ProfileId(UUID.randomUUID());
    private static final MissionId MISSION = MissionId.of("mine_stone");
    private static final Instant NOW = Instant.parse("2026-09-21T12:00:00Z");

    private static MissionDefinition missionNeeding(long required) {
        return new MissionDefinition(
                MISSION,
                MissionBranch.MINING,
                "Mine stone",
                "Mine some stone",
                MissionTriggerType.BLOCK_BREAK,
                "*",
                required,
                MissionReward.empty());
    }

    /** Keeps progress in memory and counts what was written, which is what a lost update loses. */
    private static final class InMemoryMissionStorage implements IslandMissionStoragePort {
        private final Map<MissionId, MissionProgress> stored = new ConcurrentHashMap<>();

        @Override
        public Optional<MissionProgress> findProgress(IslandId islandId, ProfileId profileId, MissionId missionId) {
            return Optional.ofNullable(stored.get(missionId));
        }

        @Override
        public Map<MissionId, MissionProgress> findAllProgress(IslandId islandId, ProfileId profileId) {
            return Map.copyOf(stored);
        }

        @Override
        public void saveProgress(IslandId islandId, ProfileId profileId, MissionProgress progress) {
            stored.put(progress.missionId(), progress);
        }

        @Override
        public void saveAllProgress(IslandId islandId, ProfileId profileId, Collection<MissionProgress> progresses) {
            for (MissionProgress progress : progresses) {
                saveProgress(islandId, profileId, progress);
            }
        }
    }

    /** Counts every reward that reached a player. */
    private static final class CountingRewards implements IslandMissionRewardPort {
        private final AtomicInteger dispatched = new AtomicInteger();

        @Override
        public void dispatchReward(IslandId islandId, ProfileId profileId, MissionDefinition mission) {
            dispatched.incrementAndGet();
        }
    }

    @Test
    @DisplayName("Sixteen blocks broken at once finish the mission once and pay once")
    void oneRewardHowEverFastThePlayerIs() throws Exception {
        int triggers = 16;
        InMemoryMissionStorage storage = new InMemoryMissionStorage();
        CountingRewards rewards = new CountingRewards();
        IslandMissionService service = new IslandMissionService(storage, rewards);
        service.registerMission(missionNeeding(triggers));

        fireTogether(service, triggers);

        assertThat(rewards.dispatched.get())
                .describedAs("rewards that reached the player")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("Every block broken is counted, so nothing the player mined is lost")
    void everyTriggerIsCounted() throws Exception {
        int triggers = 16;
        InMemoryMissionStorage storage = new InMemoryMissionStorage();
        IslandMissionService service = new IslandMissionService(storage, new CountingRewards());
        // Far more than the triggers can reach, so the count is the whole answer.
        service.registerMission(missionNeeding(1_000L));

        fireTogether(service, triggers);

        MissionProgress progress =
                service.findProgress(ISLAND, PROFILE, MISSION).orElseThrow();
        assertThat(progress.progressCount())
                .describedAs("blocks the mission counted against blocks the player broke")
                .isEqualTo(triggers);
        assertThat(progress.completed()).isFalse();
    }

    @Test
    @DisplayName("Whoever is listening is told once, when the mission crosses its target")
    void thelistenerIsToldOnce() {
        InMemoryMissionStorage storage = new InMemoryMissionStorage();
        IslandMissionService service = new IslandMissionService(storage, new CountingRewards());
        service.registerMission(missionNeeding(2L));
        List<IslandMissionService.MissionFinished> told = new ArrayList<>();
        service.setFinishedListener(told::add);

        service.handleTrigger(ISLAND, PROFILE, MissionTriggerType.BLOCK_BREAK, "STONE", 1L, NOW);
        assertThat(told).describedAs("one short of the target").isEmpty();

        service.handleTrigger(ISLAND, PROFILE, MissionTriggerType.BLOCK_BREAK, "STONE", 1L, NOW);
        service.handleTrigger(ISLAND, PROFILE, MissionTriggerType.BLOCK_BREAK, "STONE", 1L, NOW);

        assertThat(told)
                .describedAs("finished once, however many blocks come after")
                .hasSize(1);
        assertThat(told.get(0).islandId()).isEqualTo(ISLAND);
        assertThat(told.get(0).profileId()).isEqualTo(PROFILE);
        assertThat(told.get(0).definition().id()).isEqualTo(MISSION);
    }

    @Test
    @DisplayName("A listener that throws does not stop the mission from paying out")
    void afailingListenerChangesNothing() {
        InMemoryMissionStorage storage = new InMemoryMissionStorage();
        CountingRewards rewards = new CountingRewards();
        IslandMissionService service = new IslandMissionService(storage, rewards);
        service.registerMission(missionNeeding(1L));
        service.setFinishedListener(finished -> {
            throw new IllegalStateException("the feed is gone");
        });

        service.handleTrigger(ISLAND, PROFILE, MissionTriggerType.BLOCK_BREAK, "STONE", 1L, NOW);

        assertThat(rewards.dispatched.get())
                .describedAs("a feed that is not listening must not change what a mission does")
                .isEqualTo(1);
        assertThat(service.findProgress(ISLAND, PROFILE, MISSION).orElseThrow().completed())
                .isTrue();
    }

    @Test
    @DisplayName("A mission already finished is not finished again by a later block")
    void afinishedMissionStaysFinished() {
        InMemoryMissionStorage storage = new InMemoryMissionStorage();
        CountingRewards rewards = new CountingRewards();
        IslandMissionService service = new IslandMissionService(storage, rewards);
        service.registerMission(missionNeeding(1L));

        service.handleTrigger(ISLAND, PROFILE, MissionTriggerType.BLOCK_BREAK, "STONE", 1L, NOW);
        service.handleTrigger(ISLAND, PROFILE, MissionTriggerType.BLOCK_BREAK, "STONE", 1L, NOW);
        service.handleTrigger(ISLAND, PROFILE, MissionTriggerType.BLOCK_BREAK, "STONE", 1L, NOW);

        assertThat(rewards.dispatched.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("A hand-in submitted twice at once pays once")
    void aManualSubmissionPaysOnce() throws Exception {
        InMemoryMissionStorage storage = new InMemoryMissionStorage();
        CountingRewards rewards = new CountingRewards();
        IslandMissionService service = new IslandMissionService(storage, rewards);
        MissionId handIn = MissionId.of("hand_in_wheat");
        service.registerMission(new MissionDefinition(
                handIn,
                MissionBranch.FARMING,
                "Hand in wheat",
                "Hand in some wheat",
                MissionTriggerType.ITEM_SUBMIT,
                "WHEAT",
                2L,
                MissionReward.empty()));

        runTogether(
                () -> service.submitManualItem(ISLAND, PROFILE, handIn, 1L, NOW),
                () -> service.submitManualItem(ISLAND, PROFILE, handIn, 1L, NOW));

        assertThat(rewards.dispatched.get())
                .describedAs("rewards for one hand-in")
                .isEqualTo(1);
    }

    private void fireTogether(IslandMissionService service, int triggers) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(triggers);
        List<Future<?>> running = new ArrayList<>();
        try {
            for (int i = 0; i < triggers; i++) {
                running.add(pool.submit(() -> {
                    try {
                        var unused = start.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    service.handleTrigger(ISLAND, PROFILE, MissionTriggerType.BLOCK_BREAK, "STONE", 1L, NOW);
                }));
            }
            start.countDown();
            for (Future<?> task : running) {
                task.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private void runTogether(Runnable first, Runnable second) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<?>> running = new ArrayList<>();
        try {
            for (Runnable task : List.of(first, second)) {
                running.add(pool.submit(() -> {
                    try {
                        var unused = start.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    task.run();
                }));
            }
            start.countDown();
            for (Future<?> task : running) {
                task.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("A payer handed over after the service was built is the one paid, once, and none pays nothing")
    void aPayerHandedOverLaterIsPaid() throws Exception {
        IslandMissionService service = new IslandMissionService(new InMemoryMissionStorage(), null);
        service.registerMission(missionNeeding(2));
        fireTogether(service, 1);

        CountingRewards rewards = new CountingRewards();
        service.useRewards(rewards);
        fireTogether(service, 1);

        assertThat(rewards.dispatched.get()).isEqualTo(1);
    }
}
