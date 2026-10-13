package com.uxplima.uxmskyblock.core.application.leaderboard;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.IntFunction;
import java.util.function.LongFunction;

import com.uxplima.uxmskyblock.api.NamespacedId;
import com.uxplima.uxmskyblock.api.leaderboard.LeaderboardMetricProvider;
import com.uxplima.uxmskyblock.api.leaderboard.MetricConsistency;
import com.uxplima.uxmskyblock.api.leaderboard.MetricReading;
import com.uxplima.uxmskyblock.api.leaderboard.SortDirection;
import com.uxplima.uxmskyblock.core.domain.boxed.BoxRules;
import com.uxplima.uxmskyblock.core.domain.oneblock.OneBlockPhases;

/**
 * The boards each mode ranks its islands on: OneBlock by breaks and by phase, Parkour courses by their best time,
 * Boxed by the size of the box and ChunkBlock by the chunks open. Each is registered only when its mode runs.
 */
public final class ModeMetrics {

    public static final NamespacedId ONEBLOCK_BLOCKS = NamespacedId.of("uxm:oneblock_blocks");
    public static final NamespacedId ONEBLOCK_PHASE = NamespacedId.of("uxm:oneblock_phase");
    public static final NamespacedId PARKOUR_RECORD = NamespacedId.of("uxm:parkour_record");
    public static final NamespacedId BOXED_SIZE = NamespacedId.of("uxm:boxed_size");
    public static final NamespacedId CHUNKBLOCK_CHUNKS = NamespacedId.of("uxm:chunkblock_chunks");

    private ModeMetrics() {}

    /**
     * The OneBlock boards. Breaks are counted in memory and written now and then, so both read as of the last
     * write. A phase is counted over the island's whole life: an island on its second pass is ahead of one on its
     * first.
     */
    public static void oneBlock(LeaderboardMetricRegistry registry, ModeStandingsPort port, OneBlockPhases phases) {
        Objects.requireNonNull(phases, "phases must not be null");
        oneBlock(registry, port, island -> phases);
    }

    /**
     * The same, each island's phases counted along its own way through them, which its picks set where a phase
     * branches.
     *
     * @param wayOf an island's way through the phases, read from memory
     */
    public static void oneBlock(
            LeaderboardMetricRegistry registry,
            ModeStandingsPort port,
            java.util.function.Function<com.uxplima.uxmskyblock.core.domain.identity.IslandId, OneBlockPhases> wayOf) {
        Objects.requireNonNull(wayOf, "wayOf must not be null");
        registry.register(new Board(
                ONEBLOCK_BLOCKS,
                "OneBlock blocks",
                "oneblock progress",
                SortDirection.HIGHEST_FIRST,
                MetricConsistency.BATCH_SCHEDULED,
                port::mostBroken,
                ModeStandingsPort.Standing::value,
                Long::toString));
        registry.register(new Board(
                ONEBLOCK_PHASE,
                "OneBlock phase",
                "oneblock progress",
                SortDirection.HIGHEST_FIRST,
                MetricConsistency.BATCH_SCHEDULED,
                port::mostBroken,
                standing -> phasesReached(wayOf.apply(standing.islandId()), standing.value()),
                Long::toString));
    }

    /** The courses by their record, the fastest first, written as minutes, seconds and milliseconds. */
    public static void parkour(LeaderboardMetricRegistry registry, ModeStandingsPort port) {
        registry.register(new Board(
                PARKOUR_RECORD,
                "Parkour record",
                "parkour records",
                SortDirection.LOWEST_FIRST,
                MetricConsistency.EVENT_DRIVEN_EXACT,
                port::fastestCourses,
                ModeStandingsPort.Standing::value,
                ModeMetrics::time));
    }

    /** The boxes by how far across they are now, written as the box's sides. */
    public static void boxed(LeaderboardMetricRegistry registry, ModeStandingsPort port, BoxRules rules) {
        Objects.requireNonNull(rules, "rules must not be null");
        registry.register(new Board(
                BOXED_SIZE,
                "Boxed box",
                "boxed advancements",
                SortDirection.HIGHEST_FIRST,
                MetricConsistency.EVENT_DRIVEN_EXACT,
                port::mostEarned,
                standing -> 2L * rules.radius(standing.value()) + 1,
                across -> across + "×" + across));
    }

    /** The ChunkBlock islands by the chunks they have open. */
    public static void chunkBlock(LeaderboardMetricRegistry registry, ModeStandingsPort port) {
        registry.register(new Board(
                CHUNKBLOCK_CHUNKS,
                "ChunkBlock chunks",
                "chunkblock territory",
                SortDirection.HIGHEST_FIRST,
                MetricConsistency.EVENT_DRIVEN_EXACT,
                port::mostChunks,
                ModeStandingsPort.Standing::value,
                Long::toString));
    }

    /** How many phases an island has reached in its life, the one it is in among them. */
    static long phasesReached(OneBlockPhases phases, long broken) {
        long cycle = phases.cycleLength();
        int count = phases.phases().size();
        if (broken >= cycle && phases.afterTheLast() == OneBlockPhases.AfterTheLast.STAY) {
            return count;
        }
        return broken / cycle * count + phases.positionAt(broken).index() + 1;
    }

    /** A time as a course's board writes it, as {@code 1:02.345}. */
    static String time(long millis) {
        long kept = Math.max(0, millis);
        return String.format(Locale.ROOT, "%d:%02d.%03d", kept / 60_000, kept / 1000 % 60, kept % 1000);
    }

    private record Board(
            NamespacedId metricId,
            String displayName,
            String owner,
            SortDirection sortDirection,
            MetricConsistency consistency,
            IntFunction<List<ModeStandingsPort.Standing>> standings,
            java.util.function.ToLongFunction<ModeStandingsPort.Standing> value,
            LongFunction<String> written)
            implements LeaderboardMetricProvider {

        @Override
        public String rootType() {
            return IslandMetricProviders.ISLAND;
        }

        @Override
        public List<MetricReading> read(int limit) {
            return standings.apply(limit).stream()
                    .map(standing -> new MetricReading(
                            standing.islandId().value().toString(), standing.name(), value.applyAsLong(standing)))
                    .toList();
        }

        @Override
        public String format(long value) {
            return written.apply(value);
        }
    }
}
