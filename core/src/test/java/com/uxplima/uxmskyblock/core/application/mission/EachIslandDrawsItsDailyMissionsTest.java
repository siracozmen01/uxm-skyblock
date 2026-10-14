package com.uxplima.uxmskyblock.core.application.mission;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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

/** SuperiorSkyblock2 and IridiumSkyblock offer an island a few daily missions drawn from a pool; here it was all of them. */
class EachIslandDrawsItsDailyMissionsTest {

    private static final ProfileId PROFILE = new ProfileId(UUID.randomUUID());
    private static final Instant NOON = Instant.parse("2026-10-14T12:00:00Z");

    private IslandMissionService service;

    private static MissionDefinition mission(String id, MissionRepeat repeat, Set<GameModeType> modes) {
        return new MissionDefinition(
                MissionId.of(id),
                MissionBranch.MINING,
                id,
                "",
                MissionTriggerType.BLOCK_BREAK,
                "",
                1L,
                MissionReward.empty(),
                repeat,
                modes,
                Optional.empty());
    }

    @BeforeEach
    void setUp() {
        service = new IslandMissionService(new MissionStorageInMemory());
        service.resetIn(ZoneOffset.UTC);
        for (int i = 0; i < 8; i++) {
            service.registerMission(mission("daily" + i, MissionRepeat.DAILY, Set.of()));
        }
        service.registerMission(mission("oneblock_daily", MissionRepeat.DAILY, Set.of(GameModeType.ONEBLOCK)));
        service.registerMission(mission("weekly", MissionRepeat.WEEKLY, Set.of()));
        service.registerMission(mission("once", MissionRepeat.ONCE, Set.of()));
        service.readModesWith(island -> Optional.of(GameModeType.SKYBLOCK));
    }

    private Set<MissionId> dailyOffered(IslandId island, Instant now) {
        Set<MissionId> daily = new HashSet<>();
        for (MissionDefinition def : service.offeredMissions(island, PROFILE, now)) {
            if (def.repeat() == MissionRepeat.DAILY) {
                daily.add(def.id());
            }
        }
        return daily;
    }

    @Test
    @DisplayName("With no slots every daily mission the island's mode plays is offered")
    void noSlotsOffersAll() {
        assertThat(dailyOffered(IslandId.of(UUID.randomUUID()), NOON)).hasSize(8);
    }

    @Test
    @DisplayName("With three slots an island is offered three of its mode's daily missions, the same all day")
    void threeAllDay() {
        service.useDailySlots(3);
        IslandId island = IslandId.of(UUID.randomUUID());

        Set<MissionId> morning = dailyOffered(island, NOON.minus(Duration.ofHours(11)));
        assertThat(morning).hasSize(3).doesNotContain(MissionId.of("oneblock_daily"));
        assertThat(dailyOffered(island, NOON.plus(Duration.ofHours(11)))).isEqualTo(morning);
        assertThat(service.offeredMissions(island, PROFILE, NOON))
                .extracting(MissionDefinition::id)
                .contains(MissionId.of("weekly"), MissionId.of("once"));
    }

    @Test
    @DisplayName("The draw is worked out, not kept: another server, or one after a restart, draws the same")
    void anotherServerDrawsTheSame() {
        service.useDailySlots(3);
        IslandId island = IslandId.of(UUID.randomUUID());
        Set<MissionId> here = dailyOffered(island, NOON);

        IslandMissionService there = service;
        setUp();
        service.useDailySlots(3);
        assertThat(dailyOffered(island, NOON)).isEqualTo(here);
        assertThat(there).isNotSameAs(service);
    }

    @Test
    @DisplayName("The day turning draws again, and islands draw their own")
    void drawnAgainAndApart() {
        service.useDailySlots(3);
        IslandId island = IslandId.of(UUID.randomUUID());
        List<Set<MissionId>> week = new ArrayList<>();
        for (int day = 0; day < 7; day++) {
            week.add(dailyOffered(island, NOON.plus(Duration.ofDays(day))));
        }
        assertThat(new HashSet<>(week))
                .describedAs("not the same three every day")
                .hasSizeGreaterThan(1);

        List<Set<MissionId>> islands = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            islands.add(dailyOffered(IslandId.of(UUID.randomUUID()), NOON));
        }
        assertThat(new HashSet<>(islands))
                .describedAs("not the same three for everyone")
                .hasSizeGreaterThan(1);
    }

    @Test
    @DisplayName("A daily mission not drawn today counts nothing")
    void notDrawnCountsNothing() {
        service.useDailySlots(1);
        IslandId island = IslandId.of(UUID.randomUUID());
        Set<MissionId> today = dailyOffered(island, NOON);

        service.handleTrigger(island, PROFILE, MissionTriggerType.BLOCK_BREAK, "STONE", 1, NOON);

        assertThat(service.findAllProgress(island, PROFILE).keySet())
                .filteredOn(id -> id.value().startsWith("daily"))
                .containsExactlyElementsOf(today);
    }
}
