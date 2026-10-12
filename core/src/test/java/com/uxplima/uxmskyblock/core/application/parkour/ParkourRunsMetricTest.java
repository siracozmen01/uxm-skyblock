package com.uxplima.uxmskyblock.core.application.parkour;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;

import com.uxplima.uxmskyblock.api.leaderboard.MetricConsistency;
import com.uxplima.uxmskyblock.api.leaderboard.RankedReading;
import com.uxplima.uxmskyblock.api.leaderboard.SortDirection;
import com.uxplima.uxmskyblock.core.application.leaderboard.LeaderboardMetricRegistry;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The courses run most rank on the boards, read from the parkour records and never written back. */
class ParkourRunsMetricTest {

    private final IslandId busy = IslandId.of(UUID.fromString("00000000-0000-0000-0000-00000000000b"));
    private final IslandId quiet = IslandId.of(UUID.fromString("00000000-0000-0000-0000-00000000000a"));
    private final List<Integer> asked = new ArrayList<>();

    @Test
    @DisplayName("The metric ranks the courses by their finished runs, most first, as the records hold them")
    void theCoursesRankByRuns() {
        LeaderboardMetricRegistry registry = new LeaderboardMetricRegistry();
        ParkourRunsMetric metric = new ParkourRunsMetric(new ParkourService(port()));
        registry.register(metric);

        List<RankedReading> board = registry.ranked(ParkourRunsMetric.ID, 5);

        assertThat(board)
                .extracting(ranked -> ranked.reading().rootId())
                .containsExactly(busy.value().toString(), quiet.value().toString());
        assertThat(board).extracting(ranked -> ranked.reading().displayName()).containsExactly("Tower", "Garden");
        assertThat(board.get(0).reading().value()).isEqualTo(12);
        assertThat(asked).isNotEmpty().allSatisfy(limit -> assertThat(limit).isEqualTo(5));
        assertThat(metric.rootType()).isEqualTo("ISLAND");
        assertThat(metric.sortDirection()).isEqualTo(SortDirection.HIGHEST_FIRST);
        assertThat(metric.consistency()).isEqualTo(MetricConsistency.EVENT_DRIVEN_EXACT);
        assertThat(metric.owner()).isEqualTo("parkour records");
    }

    private ParkourPort port() {
        return new ParkourPort() {
            @Override
            public Set<IslandId> findAll() {
                return Set.of(busy, quiet);
            }

            @Override
            public boolean exists(IslandId islandId) {
                return true;
            }

            @Override
            public void add(IslandId islandId) {
                throw new UnsupportedOperationException("the metric adds no course");
            }

            @Override
            public OptionalLong best(IslandId islandId, PlayerUuid who) {
                return OptionalLong.empty();
            }

            @Override
            public void finish(IslandId islandId, PlayerUuid who, long millis) {
                throw new UnsupportedOperationException("the metric writes no run");
            }

            @Override
            public List<Best> top(IslandId islandId, int limit) {
                return List.of();
            }

            @Override
            public int clear(IslandId cleared, java.util.Optional<PlayerUuid> runner) {
                throw new UnsupportedOperationException("nothing here clears a course");
            }

            @Override
            public List<Runs> mostRun(int limit) {
                asked.add(limit);
                return List.of(new Runs(busy, "Tower", 12), new Runs(quiet, "Garden", 3));
            }
        };
    }
}
