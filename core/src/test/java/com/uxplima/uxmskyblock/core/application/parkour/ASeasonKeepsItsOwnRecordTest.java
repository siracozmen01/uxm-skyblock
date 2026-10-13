package com.uxplima.uxmskyblock.core.application.parkour;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.application.season.IslandSeasonStoragePort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.season.SeasonId;
import com.uxplima.uxmskyblock.core.domain.season.SeasonRecord;
import com.uxplima.uxmskyblock.core.domain.season.SeasonState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A finish counts in the season under way, and the season's fastest runner holds its record. */
class ASeasonKeepsItsOwnRecordTest {

    private static final Instant NOW = Instant.parse("2026-10-14T12:00:00Z");
    private static final IslandId COURSE = IslandId.of(UUID.randomUUID());
    private static final PlayerUuid ADA = PlayerUuid.of(UUID.fromString("00000000-0000-0000-0000-00000000000a"));
    private static final PlayerUuid BO = PlayerUuid.of(UUID.fromString("00000000-0000-0000-0000-00000000000b"));

    /** The season times in memory, as the table keeps them. */
    private static final class Times implements ParkourSeasonsPort {
        final Map<String, ParkourPort.Best> rows = new HashMap<>();

        @Override
        public void finish(IslandId course, int season, PlayerUuid runner, long millis) {
            rows.merge(
                    course + "/" + season + "/" + runner.value(),
                    new ParkourPort.Best(runner, millis, 1),
                    (was, now) -> new ParkourPort.Best(runner, Math.min(was.millis(), millis), was.runs() + 1));
        }

        @Override
        public List<ParkourPort.Best> top(IslandId course, int season, int limit) {
            List<ParkourPort.Best> top = new ArrayList<>();
            rows.forEach((key, best) -> {
                if (key.startsWith(course + "/" + season + "/")) {
                    top.add(best);
                }
            });
            top.sort(Comparator.comparingLong(ParkourPort.Best::millis)
                    .thenComparing(best -> best.runner().value()));
            return top.subList(0, Math.min(limit, top.size()));
        }

        @Override
        public int clear(IslandId course, Optional<PlayerUuid> runner) {
            int before = rows.size();
            rows.keySet()
                    .removeIf(key -> key.startsWith(course + "/")
                            && runner.map(r -> key.endsWith("/" + r.value())).orElse(true));
            return before - rows.size();
        }
    }

    private final Times times = new Times();
    private final IslandSeasonStoragePort storage = mock(IslandSeasonStoragePort.class);
    private final ParkourSeasonService seasons =
            new ParkourSeasonService(times, storage, Clock.fixed(NOW, ZoneOffset.UTC));

    private static SeasonRecord season(int number, Instant starts, Instant ends, SeasonState state) {
        return new SeasonRecord(SeasonId.of(number), "Season " + number, starts, ends, state);
    }

    private void active(SeasonRecord season) {
        when(storage.findActiveSeason()).thenReturn(Optional.of(season));
    }

    @Test
    @DisplayName(
            "A finish counts in the season under way; the first is a record, a faster one is, a slower or a tie is not")
    void records() {
        SeasonRecord two = season(2, NOW.minusSeconds(60), NOW.plusSeconds(60), SeasonState.ACTIVE);
        active(two);

        assertThat(seasons.count(COURSE, ADA, Duration.ofSeconds(30)))
                .contains(new ParkourSeasonService.Counted(two, true, Optional.empty()));
        assertThat(seasons.count(COURSE, BO, Duration.ofSeconds(30)))
                .describedAs("a tie")
                .contains(new ParkourSeasonService.Counted(two, false, Optional.of(30_000L)));
        assertThat(seasons.count(COURSE, BO, Duration.ofMillis(29_999)))
                .contains(new ParkourSeasonService.Counted(two, true, Optional.of(30_000L)));
        assertThat(seasons.count(COURSE, ADA, Duration.ofSeconds(31)))
                .contains(new ParkourSeasonService.Counted(two, false, Optional.of(29_999L)));
        assertThat(seasons.top(COURSE, two, 10))
                .containsExactly(new ParkourPort.Best(BO, 29_999, 2), new ParkourPort.Best(ADA, 30_000, 2));
        assertThat(seasons.top(COURSE, two, 0)).describedAs("at least one").hasSize(1);

        PlayerUuid first = PlayerUuid.of(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        assertThat(seasons.count(COURSE, first, Duration.ofMillis(29_999)))
                .describedAs("a tie by a runner the board lists first")
                .contains(new ParkourSeasonService.Counted(two, false, Optional.of(29_999L)));
    }

    @Test
    @DisplayName("No season under way counts nothing: none, one not started, one ended, one frozen")
    void noSeason() {
        when(storage.findActiveSeason()).thenReturn(Optional.empty());
        assertThat(seasons.count(COURSE, ADA, Duration.ofSeconds(9))).isEmpty();
        active(season(3, NOW.plusSeconds(1), NOW.plusSeconds(60), SeasonState.ACTIVE));
        assertThat(seasons.count(COURSE, ADA, Duration.ofSeconds(9))).isEmpty();
        active(season(3, NOW.minusSeconds(60), NOW, SeasonState.ACTIVE));
        assertThat(seasons.count(COURSE, ADA, Duration.ofSeconds(9))).isEmpty();
        active(season(3, NOW.minusSeconds(60), NOW.plusSeconds(60), SeasonState.FROZEN));
        assertThat(seasons.count(COURSE, ADA, Duration.ofSeconds(9))).isEmpty();
        assertThat(times.rows).isEmpty();

        SeasonRecord starting = season(3, NOW, NOW.plusSeconds(60), SeasonState.ACTIVE);
        active(starting);
        assertThat(seasons.current()).describedAs("from its first instant").contains(starting);
    }

    @Test
    @DisplayName("A season is found by its number, none below one; a reset forgets a runner's season times or all")
    void lookupAndReset() {
        SeasonRecord one = season(1, NOW.minusSeconds(600), NOW.minusSeconds(300), SeasonState.COMPLETED);
        when(storage.findSeason(SeasonId.of(1))).thenReturn(Optional.of(one));
        when(storage.findSeason(SeasonId.of(2))).thenReturn(Optional.empty());
        assertThat(seasons.season(1)).contains(one);
        assertThat(seasons.season(2)).isEmpty();
        assertThat(seasons.season(0)).isEmpty();

        active(season(2, NOW.minusSeconds(60), NOW.plusSeconds(60), SeasonState.ACTIVE));
        seasons.count(COURSE, ADA, Duration.ofSeconds(9));
        seasons.count(COURSE, BO, Duration.ofSeconds(8));
        assertThat(seasons.clear(COURSE, Optional.of(ADA))).isEqualTo(1);
        assertThat(seasons.clear(COURSE, Optional.empty())).isEqualTo(1);
    }
}
