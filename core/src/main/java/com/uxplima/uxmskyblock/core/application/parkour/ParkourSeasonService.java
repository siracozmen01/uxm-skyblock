package com.uxplima.uxmskyblock.core.application.parkour;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.uxplima.uxmskyblock.core.application.season.IslandSeasonStoragePort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.season.SeasonId;
import com.uxplima.uxmskyblock.core.domain.season.SeasonRecord;
import com.uxplima.uxmskyblock.core.domain.season.SeasonState;

/**
 * The Parkour times of each season the season system runs. A finish counts in the season under way, between its
 * start and its end; each season keeps its own board, and its fastest runner holds the season's record.
 *
 * <p>Everything here reads or writes the database, so it runs off the main thread.
 */
public final class ParkourSeasonService {

    /**
     * A finish counted in a season.
     *
     * @param record whether it took the season's record on the course
     * @param was the season's record on the course before it, empty when there was none
     */
    public record Counted(SeasonRecord season, boolean record, Optional<Long> was) {}

    private final ParkourSeasonsPort port;
    private final IslandSeasonStoragePort seasons;
    private final Clock clock;

    public ParkourSeasonService(ParkourSeasonsPort port, IslandSeasonStoragePort seasons, Clock clock) {
        this.port = Objects.requireNonNull(port, "port must not be null");
        this.seasons = Objects.requireNonNull(seasons, "seasons must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /** The season a finish counts in now: the active one, once it started and until it ends. */
    public Optional<SeasonRecord> current() {
        Instant now = clock.instant();
        return seasons.findActiveSeason()
                .filter(season -> season.state() == SeasonState.ACTIVE)
                .filter(season -> !now.isBefore(season.startsAt()) && now.isBefore(season.endsAt()));
    }

    /** A season by its number, under way or over. */
    public Optional<SeasonRecord> season(int number) {
        return number < 1 ? Optional.empty() : seasons.findSeason(SeasonId.of(number));
    }

    /**
     * Counts a finish in the season under way, if there is one. A record is a time below the season's best before it
     * that still leads once it is counted: of two finishes at once, the slower is no record.
     */
    public Optional<Counted> count(IslandId course, PlayerUuid runner, Duration time) {
        Objects.requireNonNull(course, "course must not be null");
        Objects.requireNonNull(runner, "runner must not be null");
        Objects.requireNonNull(time, "time must not be null");
        Optional<SeasonRecord> under = current();
        if (under.isEmpty()) {
            return Optional.empty();
        }
        int number = under.get().id().number();
        long millis = time.toMillis();
        Optional<ParkourPort.Best> before = leader(course, number);
        port.finish(course, number, runner, millis);
        Optional<ParkourPort.Best> after = leader(course, number);
        boolean record = after.filter(leader -> leader.runner().equals(runner) && leader.millis() == millis)
                        .isPresent()
                && before.map(was -> millis < was.millis()).orElse(true);
        return Optional.of(new Counted(under.get(), record, before.map(ParkourPort.Best::millis)));
    }

    /** The fastest runners of a course in a season, best first. */
    public List<ParkourPort.Best> top(IslandId course, SeasonRecord season, int limit) {
        return port.top(course, season.id().number(), Math.max(1, limit));
    }

    /** Forgets the season times on a course, of every runner or of one, as an operator's reset does. */
    public int clear(IslandId course, Optional<PlayerUuid> runner) {
        return port.clear(course, runner);
    }

    private Optional<ParkourPort.Best> leader(IslandId course, int season) {
        List<ParkourPort.Best> top = port.top(course, season, 1);
        return top.isEmpty() ? Optional.empty() : Optional.of(top.getFirst());
    }
}
