package com.uxplima.uxmskyblock.core.application.leaderboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.uxplima.uxmskyblock.api.leaderboard.LeaderboardMetricProvider;
import com.uxplima.uxmskyblock.api.leaderboard.MetricConsistency;
import com.uxplima.uxmskyblock.api.leaderboard.RankedReading;
import com.uxplima.uxmskyblock.api.leaderboard.SortDirection;
import com.uxplima.uxmskyblock.core.domain.boxed.BoxRules;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.oneblock.OneBlockPhase;
import com.uxplima.uxmskyblock.core.domain.oneblock.OneBlockPhases;
import com.uxplima.uxmskyblock.core.domain.oneblock.WeightedPool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Each mode ranks its islands on boards of its own: OneBlock by breaks and by phase, Parkour courses by their best
 * time, Boxed by the size of the box and ChunkBlock by the chunks open, read from storage each time.
 */
class EachModeRanksItsIslandsTest {

    private static final IslandId FIRST = IslandId.of(UUID.fromString("00000000-0000-0000-0000-00000000000a"));
    private static final IslandId SECOND = IslandId.of(UUID.fromString("00000000-0000-0000-0000-00000000000b"));
    private static final IslandId THIRD = IslandId.of(UUID.fromString("00000000-0000-0000-0000-00000000000c"));

    private final List<Integer> asked = new ArrayList<>();
    private final LeaderboardMetricRegistry registry = new LeaderboardMetricRegistry();

    private static OneBlockPhase phase(String key, long blocks) {
        return new OneBlockPhase(key, blocks, new WeightedPool(Map.of("DIRT", 1.0)), WeightedPool.empty(), 0);
    }

    private final ModeStandingsPort port = new ModeStandingsPort() {
        @Override
        public List<Standing> mostBroken(int limit) {
            asked.add(limit);
            return List.of(
                    new Standing(FIRST, "Tower", 26), new Standing(SECOND, "Garden", 4), new Standing(THIRD, "c", 0));
        }

        @Override
        public List<Standing> fastestCourses(int limit) {
            asked.add(limit);
            // In no order: the board sorts.
            return List.of(new Standing(FIRST, "Long", 125_004), new Standing(SECOND, "Short", 9_870));
        }

        @Override
        public List<Standing> mostEarned(int limit) {
            asked.add(limit);
            return List.of(new Standing(FIRST, "Big", 1_000), new Standing(SECOND, "Small", 3));
        }

        @Override
        public List<Standing> mostChunks(int limit) {
            asked.add(limit);
            return List.of(new Standing(FIRST, "Wide", 7), new Standing(SECOND, "Narrow", 1));
        }
    };

    private List<String> board(com.uxplima.uxmskyblock.api.NamespacedId id) {
        LeaderboardMetricProvider provider = registry.provider(id).orElseThrow();
        return registry.ranked(id, 5).stream()
                .map(RankedReading::reading)
                .map(reading -> reading.displayName() + " " + provider.format(reading.value()))
                .toList();
    }

    @Test
    @DisplayName("OneBlock ranks by breaks and by the phases an island has reached in its life")
    void oneBlock() {
        // Ten breaks a pass: plains for three, the cave for seven, then round again.
        ModeMetrics.oneBlock(
                registry,
                port,
                new OneBlockPhases(List.of(phase("plains", 3), phase("cave", 7)), OneBlockPhases.AfterTheLast.REPEAT));

        assertThat(board(ModeMetrics.ONEBLOCK_BLOCKS)).containsExactly("Tower 26", "Garden 4", "c 0");
        assertThat(board(ModeMetrics.ONEBLOCK_PHASE))
                .describedAs("26 breaks is two passes and six into the third, in its cave: the sixth phase reached")
                .containsExactly("Tower 6", "Garden 2", "c 1");
        assertThat(registry.provider(ModeMetrics.ONEBLOCK_PHASE).orElseThrow().consistency())
                .describedAs("breaks are written now and then")
                .isEqualTo(MetricConsistency.BATCH_SCHEDULED);
        assertThat(asked).isNotEmpty().allSatisfy(limit -> assertThat(limit).isEqualTo(5));
    }

    @Test
    @DisplayName("Each island's phases are counted along its own way, which its picks set where a phase branches")
    void alongItsOwnWay() {
        OneBlockPhases file =
                new OneBlockPhases(List.of(phase("plains", 3), phase("cave", 7)), OneBlockPhases.AfterTheLast.REPEAT);
        OneBlockPhases picked = new OneBlockPhases(
                List.of(phase("plains", 3), phase("forest", 1), phase("end", 5)), OneBlockPhases.AfterTheLast.REPEAT);
        ModeMetrics.oneBlock(registry, port, island -> island.equals(SECOND) ? picked : file);

        assertThat(board(ModeMetrics.ONEBLOCK_PHASE))
                .describedAs("4 breaks along plains, forest and end is the third phase reached")
                .containsExactly("Tower 6", "Garden 3", "c 1");
    }

    @Test
    @DisplayName("An island that stays in the last phase has reached every phase, and no more")
    void stayingInTheLast() {
        OneBlockPhases phases =
                new OneBlockPhases(List.of(phase("plains", 3), phase("cave", 7)), OneBlockPhases.AfterTheLast.STAY);

        assertThat(ModeMetrics.phasesReached(phases, 0)).isEqualTo(1);
        assertThat(ModeMetrics.phasesReached(phases, 3)).isEqualTo(2);
        assertThat(ModeMetrics.phasesReached(phases, 9)).isEqualTo(2);
        assertThat(ModeMetrics.phasesReached(phases, 10))
                .describedAs("the first break past the last")
                .isEqualTo(2);
        assertThat(ModeMetrics.phasesReached(phases, 26)).isEqualTo(2);
    }

    @Test
    @DisplayName("Parkour courses rank by their record, the fastest first, written as a time")
    void parkour() {
        ModeMetrics.parkour(registry, port);

        assertThat(board(ModeMetrics.PARKOUR_RECORD)).containsExactly("Short 0:09.870", "Long 2:05.004");
        assertThat(registry.provider(ModeMetrics.PARKOUR_RECORD).orElseThrow().sortDirection())
                .isEqualTo(SortDirection.LOWEST_FIRST);
    }

    @Test
    @DisplayName("Boxed ranks by the box's sides, as far as the rules let it grow")
    void boxed() {
        ModeMetrics.boxed(registry, port, BoxRules.shipped());

        assertThat(board(ModeMetrics.BOXED_SIZE))
                .describedAs("a box earns no more than its largest: 400 out, 801 across")
                .containsExactly("Big 801×801", "Small 17×17");
    }

    @Test
    @DisplayName("ChunkBlock ranks by the chunks open, and every board ranks islands")
    void chunkBlock() {
        ModeMetrics.chunkBlock(registry, port);
        ModeMetrics.parkour(registry, port);
        ModeMetrics.boxed(registry, port, BoxRules.shipped());

        assertThat(board(ModeMetrics.CHUNKBLOCK_CHUNKS)).containsExactly("Wide 7", "Narrow 1");
        assertThat(registry.ranked(ModeMetrics.CHUNKBLOCK_CHUNKS, 5)
                        .getFirst()
                        .reading()
                        .rootId())
                .isEqualTo(FIRST.value().toString());
        for (var id : List.of(ModeMetrics.CHUNKBLOCK_CHUNKS, ModeMetrics.PARKOUR_RECORD, ModeMetrics.BOXED_SIZE)) {
            LeaderboardMetricProvider provider = registry.provider(id).orElseThrow();
            assertThat(provider.rootType()).isEqualTo("ISLAND");
            assertThat(provider.consistency()).isEqualTo(MetricConsistency.EVENT_DRIVEN_EXACT);
        }
    }
}
