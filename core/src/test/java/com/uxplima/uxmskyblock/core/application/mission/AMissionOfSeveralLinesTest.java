package com.uxplima.uxmskyblock.core.application.mission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.mission.MissionBranch;
import com.uxplima.uxmskyblock.core.domain.mission.MissionDefinition;
import com.uxplima.uxmskyblock.core.domain.mission.MissionId;
import com.uxplima.uxmskyblock.core.domain.mission.MissionLine;
import com.uxplima.uxmskyblock.core.domain.mission.MissionProgress;
import com.uxplima.uxmskyblock.core.domain.mission.MissionRepeat;
import com.uxplima.uxmskyblock.core.domain.mission.MissionReward;
import com.uxplima.uxmskyblock.core.domain.mission.MissionTriggerType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** SuperiorSkyblock2 missions may ask for several things at once; here a mission counted one. */
class AMissionOfSeveralLinesTest {

    private static final IslandId ISLAND = IslandId.of(UUID.randomUUID());
    private static final ProfileId PROFILE = new ProfileId(UUID.randomUUID());
    private static final MissionId MINER = MissionId.of("miner");
    private static final Instant NOW = Instant.parse("2026-10-14T12:00:00Z");

    private final List<MissionId> paid = new ArrayList<>();
    private IslandMissionService service;

    private static MissionDefinition miner(MissionRepeat repeat) {
        return new MissionDefinition(
                MINER,
                MissionBranch.MINING,
                "Miner",
                "Mine and fight",
                MissionTriggerType.LINES,
                "",
                2L,
                new MissionReward(5, 0, 0, List.of()),
                repeat,
                Set.of(),
                Optional.empty());
    }

    private static final List<MissionLine> LINES = List.of(
            new MissionLine(MissionTriggerType.BLOCK_BREAK, "STONE", 3L),
            new MissionLine(MissionTriggerType.MOB_KILL, "ZOMBIE", 2L));

    private void use(MissionRepeat repeat) {
        service = new IslandMissionService(new MissionStorageInMemory(), (isl, prof, def) -> paid.add(def.id()));
        service.resetIn(java.time.ZoneOffset.UTC);
        MissionDefinition mission = miner(repeat);
        service.registerMission(mission);
        service.registerLines(mission, LINES);
    }

    @BeforeEach
    void setUp() {
        use(MissionRepeat.ONCE);
    }

    private void fire(MissionTriggerType trigger, String target, long amount, Instant at) {
        service.handleTrigger(ISLAND, PROFILE, trigger, target, amount, at);
    }

    @Test
    @DisplayName("Each line counts on its own; the mission is finished and paid once, when the last line is")
    void finishedWhenEveryLineIs() {
        fire(MissionTriggerType.BLOCK_BREAK, "STONE", 5, NOW);
        fire(MissionTriggerType.BLOCK_BREAK, "DIRT", 9, NOW);
        assertThat(service.findProgress(ISLAND, PROFILE, MINER).map(MissionProgress::progressCount))
                .contains(1L);
        assertThat(paid).isEmpty();

        fire(MissionTriggerType.MOB_KILL, "ZOMBIE", 1, NOW);
        assertThat(paid).isEmpty();
        fire(MissionTriggerType.MOB_KILL, "ZOMBIE", 1, NOW);
        fire(MissionTriggerType.MOB_KILL, "ZOMBIE", 1, NOW);
        fire(MissionTriggerType.BLOCK_BREAK, "STONE", 5, NOW);

        assertThat(paid).containsExactly(MINER);
        assertThat(service.findProgress(ISLAND, PROFILE, MINER).orElseThrow().completed())
                .isTrue();
        assertThat(service.countCompleted(ISLAND, PROFILE))
                .describedAs("the lines are not missions")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("The lines are read in order and nothing else is shown as a mission")
    void theLinesAreItsOwn() {
        assertThat(service.linesOf(MINER))
                .extracting(MissionDefinition::id)
                .containsExactly(MissionLine.idOf(MINER, 1), MissionLine.idOf(MINER, 2));
        assertThat(service.linesOf(MINER).get(1).requiredAmount()).isEqualTo(2L);
        assertThat(service.allMissions()).extracting(MissionDefinition::id).containsExactly(MINER);
        assertThat(service.findMission(MissionLine.idOf(MINER, 1))).isEmpty();
        assertThat(service.offeredMissions(ISLAND, PROFILE, NOW))
                .extracting(MissionDefinition::id)
                .containsExactly(MINER);
        assertThat(service.linesOf(MissionId.of("other"))).isEmpty();
    }

    @Test
    @DisplayName("A daily mission of lines comes back with its lines started over")
    void theLinesComeBackWithIt() {
        use(MissionRepeat.DAILY);
        fire(MissionTriggerType.BLOCK_BREAK, "STONE", 3, NOW);
        fire(MissionTriggerType.MOB_KILL, "ZOMBIE", 2, NOW);
        assertThat(paid).containsExactly(MINER);

        Instant tomorrow = NOW.plus(Duration.ofDays(1));
        assertThat(java.util.Objects.requireNonNull(service.currentProgress(ISLAND, PROFILE, tomorrow)
                                .get(MissionLine.idOf(MINER, 1)))
                        .progressCount())
                .describedAs("a line shown the next day reads as started over")
                .isZero();
        fire(MissionTriggerType.BLOCK_BREAK, "STONE", 3, tomorrow);
        assertThat(paid).describedAs("one line done today").containsExactly(MINER);
        fire(MissionTriggerType.MOB_KILL, "ZOMBIE", 2, tomorrow);
        assertThat(paid).containsExactly(MINER, MINER);
    }

    @Test
    @DisplayName("Lines need a mission counting LINES with as many as it has, and a line cannot count a hand-in")
    void linesAreChecked() {
        MissionDefinition one = new MissionDefinition(
                MissionId.of("one"),
                MissionBranch.MINING,
                "One",
                "",
                MissionTriggerType.BLOCK_BREAK,
                "",
                2L,
                MissionReward.empty());
        assertThatThrownBy(() -> service.registerLines(one, LINES)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.registerLines(miner(MissionRepeat.ONCE), LINES.subList(0, 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MissionLine(MissionTriggerType.ITEM_SUBMIT, "", 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MissionLine(MissionTriggerType.LINES, "", 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("A mission not offered yet counts nothing on its lines")
    void notOfferedCountsNothing() {
        service = new IslandMissionService(new MissionStorageInMemory(), (isl, prof, def) -> paid.add(def.id()));
        MissionDefinition first = new MissionDefinition(
                MissionId.of("first"),
                MissionBranch.MINING,
                "First",
                "",
                MissionTriggerType.FISHING,
                "",
                1L,
                MissionReward.empty());
        MissionDefinition later = new MissionDefinition(
                MINER,
                MissionBranch.MINING,
                "Miner",
                "",
                MissionTriggerType.LINES,
                "",
                2L,
                MissionReward.empty(),
                MissionRepeat.ONCE,
                Set.of(),
                Optional.of(first.id()));
        service.registerMission(first);
        service.registerMission(later);
        service.registerLines(later, LINES);

        fire(MissionTriggerType.BLOCK_BREAK, "STONE", 3, NOW);

        assertThat(service.findProgress(ISLAND, PROFILE, MissionLine.idOf(MINER, 1)))
                .isEmpty();
    }
}
