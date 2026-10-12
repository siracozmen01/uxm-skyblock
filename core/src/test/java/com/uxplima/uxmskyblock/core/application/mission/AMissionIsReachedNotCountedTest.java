package com.uxplima.uxmskyblock.core.application.mission;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.mission.MissionBranch;
import com.uxplima.uxmskyblock.core.domain.mission.MissionDefinition;
import com.uxplima.uxmskyblock.core.domain.mission.MissionId;
import com.uxplima.uxmskyblock.core.domain.mission.MissionReward;
import com.uxplima.uxmskyblock.core.domain.mission.MissionTriggerType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A level or a balance is reached, not counted one at a time: a mission moves up to where the island stands, never
 * back when it falls, and is paid once.
 */
class AMissionIsReachedNotCountedTest {

    private static final Instant NOW = Instant.parse("2026-10-12T12:00:00Z");

    private final IslandId island = new IslandId(UUID.randomUUID());
    private final ProfileId profile = new ProfileId(UUID.randomUUID());
    private final List<MissionId> paid = new ArrayList<>();
    private IslandMissionService service;

    @BeforeEach
    void setUp() {
        service = new IslandMissionService(new MissionStorageInMemory(), (isl, prof, def) -> paid.add(def.id()));
        service.registerMission(new MissionDefinition(
                MissionId.of("level_ten"),
                MissionBranch.ECONOMY,
                "level_ten",
                "level_ten",
                MissionTriggerType.LEVEL_REACH,
                "",
                10L,
                new MissionReward(1L, 0L, 0L, List.of())));
    }

    private long at() {
        return java.util.Objects.requireNonNull(
                        service.currentProgress(island, profile, NOW).get(MissionId.of("level_ten")), "level_ten")
                .progressCount();
    }

    @Test
    @DisplayName("A mission moves up to the level reached, stays when it falls, and is paid once")
    void reached() {
        service.handleReach(island, profile, MissionTriggerType.LEVEL_REACH, 4, NOW);
        assertThat(at()).isEqualTo(4);

        assertThat(service.handleReach(island, profile, MissionTriggerType.LEVEL_REACH, 3, NOW))
                .describedAs("nothing moved")
                .isEmpty();
        assertThat(at())
                .describedAs("a level that fell does not take the mission back")
                .isEqualTo(4);

        service.handleReach(island, profile, MissionTriggerType.LEVEL_REACH, 6, NOW);
        assertThat(at()).describedAs("up to six, not four and six").isEqualTo(6);

        service.handleReach(island, profile, MissionTriggerType.LEVEL_REACH, 12, NOW);
        service.handleReach(island, profile, MissionTriggerType.LEVEL_REACH, 15, NOW);
        assertThat(paid).containsExactly(MissionId.of("level_ten"));
        assertThat(at()).isEqualTo(10);
    }

    @Test
    @DisplayName("A balance reached moves no mission that counts a level")
    void onlyItsOwnTrigger() {
        service.handleReach(island, profile, MissionTriggerType.BANK_BALANCE, 50, NOW);

        assertThat(service.currentProgress(island, profile, NOW)).isEmpty();
    }
}
