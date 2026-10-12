package com.uxplima.uxmskyblock.core.application.mission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.domain.gamemode.GameModeType;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.mission.MissionBranch;
import com.uxplima.uxmskyblock.core.domain.mission.MissionDefinition;
import com.uxplima.uxmskyblock.core.domain.mission.MissionId;
import com.uxplima.uxmskyblock.core.domain.mission.MissionRepeat;
import com.uxplima.uxmskyblock.core.domain.mission.MissionReward;
import com.uxplima.uxmskyblock.core.domain.mission.MissionTriggerType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A mission names the modes it belongs to and the mission it follows. An island of another mode is neither offered
 * it nor moves it on, and a link of a chain waits for the one before it.
 */
class AMissionBelongsToItsModesAndItsChainTest {

    private static final Instant NOW = Instant.parse("2026-10-12T12:00:00Z");

    private final IslandId oneBlock = new IslandId(UUID.randomUUID());
    private final IslandId skyblock = new IslandId(UUID.randomUUID());
    private final IslandId unknown = new IslandId(UUID.randomUUID());
    private final ProfileId profile = new ProfileId(UUID.randomUUID());
    private final List<String> paid = new ArrayList<>();
    private final List<IslandId> asked = new ArrayList<>();
    private IslandMissionService service;

    private static MissionDefinition mission(String id, Set<GameModeType> modes, Optional<String> after) {
        return new MissionDefinition(
                MissionId.of(id),
                MissionBranch.MINING,
                id,
                id,
                MissionTriggerType.BLOCK_BREAK,
                "STONE",
                2L,
                new MissionReward(1L, 0L, 0L, List.of()),
                MissionRepeat.ONCE,
                modes,
                after.map(MissionId::of));
    }

    @BeforeEach
    void setUp() {
        service = new IslandMissionService(
                new ByIsland(),
                (isl, prof, def) -> paid.add(isl.equals(oneBlock) ? def.id().value() : "?"));
        service.readModesWith(island -> {
            asked.add(island);
            return Map.of(oneBlock, GameModeType.ONEBLOCK, skyblock, GameModeType.SKYBLOCK).entrySet().stream()
                    .filter(entry -> entry.getKey().equals(island))
                    .map(Map.Entry::getValue)
                    .findFirst();
        });
        service.registerMissions(List.of(
                mission("everywhere", Set.of(), Optional.empty()),
                mission("phase_one", Set.of(GameModeType.ONEBLOCK), Optional.empty()),
                mission("phase_two", Set.of(GameModeType.ONEBLOCK), Optional.of("phase_one")),
                mission("orphan", Set.of(), Optional.of("taken_out"))));
    }

    private List<String> offered(IslandId island) {
        return service.offeredMissions(island, profile, NOW).stream()
                .map(def -> def.id().value())
                .sorted()
                .toList();
    }

    private void breakStone(IslandId island, long amount) {
        service.handleTrigger(island, profile, MissionTriggerType.BLOCK_BREAK, "STONE", amount, NOW);
    }

    @Test
    @DisplayName("An island is offered the missions of its mode and those of none; one of no known mode, only those")
    void byMode() {
        assertThat(offered(oneBlock)).containsExactly("everywhere", "orphan", "phase_one");
        assertThat(offered(skyblock)).containsExactly("everywhere", "orphan");
        assertThat(offered(unknown)).containsExactly("everywhere", "orphan");

        breakStone(skyblock, 5);
        assertThat(service.findAllProgress(skyblock, profile))
                .describedAs("another mode's mission does not move")
                .containsOnlyKeys(MissionId.of("everywhere"), MissionId.of("orphan"));
    }

    @Test
    @DisplayName("A link waits for the one before it, and a link whose first the file no longer holds is offered")
    void byChain() {
        breakStone(oneBlock, 1);
        assertThat(service.findAllProgress(oneBlock, profile)).doesNotContainKey(MissionId.of("phase_two"));

        breakStone(oneBlock, 1);
        assertThat(paid).contains("phase_one").doesNotContain("phase_two");
        assertThat(offered(oneBlock)).contains("phase_two");

        breakStone(oneBlock, 2);
        assertThat(paid).contains("phase_two");
        assertThat(paid).contains("orphan");
    }

    @Test
    @DisplayName("An island's mode is read once, until the island is let go")
    void readOnce() {
        offered(oneBlock);
        breakStone(oneBlock, 1);
        offered(oneBlock);
        assertThat(asked).containsExactly(oneBlock);

        service.forgetIsland(oneBlock);
        offered(oneBlock);
        assertThat(asked).describedAs("read again once let go").containsExactly(oneBlock, oneBlock);
    }

    @Test
    @DisplayName("Items handed in to a mission of another mode are taken by nothing")
    void aHandIn() {
        service.registerMission(new MissionDefinition(
                MissionId.of("hand_in"),
                MissionBranch.MINING,
                "hand_in",
                "hand_in",
                MissionTriggerType.ITEM_SUBMIT,
                "DIAMOND",
                4L,
                new MissionReward(1L, 0L, 0L, List.of()),
                MissionRepeat.ONCE,
                Set.of(GameModeType.ONEBLOCK),
                Optional.empty()));

        assertThat(service.submitManualItems(skyblock, profile, MissionId.of("hand_in"), 3, NOW))
                .isEmpty();
        assertThat(service.submitManualItems(oneBlock, profile, MissionId.of("hand_in"), 3, NOW))
                .hasValueSatisfying(taken -> assertThat(taken.credited()).isEqualTo(3));
    }

    @Test
    @DisplayName("A mission cannot follow itself")
    void notItself() {
        assertThatThrownBy(() -> mission("loop", Set.of(), Optional.of("loop")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Keeps each island's progress apart, in memory. */
    private static final class ByIsland implements IslandMissionStoragePort {
        private final Map<String, com.uxplima.uxmskyblock.core.domain.mission.MissionProgress> stored =
                new java.util.concurrent.ConcurrentHashMap<>();

        @Override
        public Optional<com.uxplima.uxmskyblock.core.domain.mission.MissionProgress> findProgress(
                IslandId islandId, ProfileId profileId, MissionId missionId) {
            return Optional.ofNullable(stored.get(islandId.value() + ":" + missionId.value()));
        }

        @Override
        public Map<MissionId, com.uxplima.uxmskyblock.core.domain.mission.MissionProgress> findAllProgress(
                IslandId islandId, ProfileId profileId) {
            Map<MissionId, com.uxplima.uxmskyblock.core.domain.mission.MissionProgress> found =
                    new java.util.HashMap<>();
            stored.forEach((key, progress) -> {
                if (key.startsWith(islandId.value() + ":")) {
                    found.put(progress.missionId(), progress);
                }
            });
            return found;
        }

        @Override
        public void saveProgress(
                IslandId islandId,
                ProfileId profileId,
                com.uxplima.uxmskyblock.core.domain.mission.MissionProgress progress) {
            stored.put(islandId.value() + ":" + progress.missionId().value(), progress);
        }

        @Override
        public void saveAllProgress(
                IslandId islandId,
                ProfileId profileId,
                java.util.Collection<com.uxplima.uxmskyblock.core.domain.mission.MissionProgress> progresses) {
            progresses.forEach(progress -> saveProgress(islandId, profileId, progress));
        }
    }
}
