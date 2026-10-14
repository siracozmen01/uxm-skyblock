package com.uxplima.uxmskyblock.core.application.brix;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import com.uxplima.uxmskyblock.core.domain.brix.Contest;
import com.uxplima.uxmskyblock.core.domain.brix.ContestPayout;
import com.uxplima.uxmskyblock.core.domain.brix.ContestStanding;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;

/**
 * The Brix build contests: one at a time is opened, plots are entered and built while it builds, voted for while it
 * takes votes, and placed once the votes end, each member of a placed plot owed the list of its place until paid.
 *
 * <p>Every call reads or writes the database: off the main thread.
 */
public final class BrixContests {

    /** What became of a request. */
    public enum Outcome {
        DONE,
        /** No contest is under way. */
        NONE,
        /** A contest is under way already. */
        UNDER_WAY,
        /** The contest is not in the phase that takes this. */
        WRONG_PHASE,
        /** The plot was entered already. */
        ALREADY,
        /** The plot is not entered. */
        NOT_ENTERED,
        /** The stars are not from 1 to the most. */
        BAD_STARS
    }

    private final BrixContestsPort port;
    private final Clock clock;

    public BrixContests(BrixContestsPort port, Clock clock) {
        this.port = Objects.requireNonNull(port, "port must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /** The contest not yet settled, if one is. */
    public Optional<Contest> current() {
        return port.latest().filter(contest -> !contest.settled());
    }

    /** The last contest settled or under way, for its standings. */
    public Optional<Contest> latest() {
        return port.latest();
    }

    /** The contest of a number, settled or not. */
    public Optional<Contest> find(int contest) {
        return port.find(contest);
    }

    /** Opens a contest on a theme, building for {@code build} and then voting for {@code vote}. */
    public Outcome open(String theme, Duration build, Duration vote) {
        if (current().isPresent()) {
            return Outcome.UNDER_WAY;
        }
        Instant now = clock.instant();
        int number = port.latest().map(Contest::number).orElse(0) + 1;
        port.open(new Contest(
                number, theme.strip(), now, now.plus(build), now.plus(build).plus(vote), false));
        return Outcome.DONE;
    }

    /** Takes the contest under way off, with all it holds. */
    public Outcome close() {
        Optional<Contest> under = current();
        if (under.isEmpty()) {
            return Outcome.NONE;
        }
        port.close(under.get().number());
        return Outcome.DONE;
    }

    /** Enters a plot, while the contest builds. */
    public Outcome enter(IslandId plot) {
        Optional<Contest> under = current();
        if (under.isEmpty()) {
            return Outcome.NONE;
        }
        if (under.get().phaseAt(clock.instant()) != Contest.Phase.BUILDING) {
            return Outcome.WRONG_PHASE;
        }
        return port.enter(under.get().number(), plot) ? Outcome.DONE : Outcome.ALREADY;
    }

    /** Gives an entered plot a voter's stars, from 1 to {@code most}, while the contest takes votes. */
    public Outcome vote(IslandId plot, PlayerUuid voter, int stars, int most) {
        Optional<Contest> under = current();
        if (under.isEmpty()) {
            return Outcome.NONE;
        }
        if (under.get().phaseAt(clock.instant()) != Contest.Phase.VOTING) {
            return Outcome.WRONG_PHASE;
        }
        if (stars < 1 || stars > most) {
            return Outcome.BAD_STARS;
        }
        if (!port.entries(under.get().number()).contains(plot)) {
            return Outcome.NOT_ENTERED;
        }
        port.vote(under.get().number(), plot, voter, stars);
        return Outcome.DONE;
    }

    /** Whether a plot is entered in the contest under way. */
    public boolean entered(IslandId plot) {
        return current()
                .map(contest -> port.entries(contest.number()).contains(plot))
                .orElse(false);
    }

    /**
     * The entered plots of a contest, best first: the most stars on average, then the most votes, then by the plot's
     * id so the order is the same each time it is read.
     */
    public List<ContestStanding> standings(int contest) {
        List<ContestStanding> all = new ArrayList<>(port.standings(contest));
        all.sort(Comparator.comparingDouble(ContestStanding::average)
                .reversed()
                .thenComparing(Comparator.comparingInt(ContestStanding::votes).reversed())
                .thenComparing(standing -> standing.island().value().toString()));
        return all;
    }

    /**
     * Settles the contest under way once its votes have ended. Only a plot that drew {@code minVotes} votes or more is
     * placed: one nobody looked at earns nothing, and a plot alone in a contest does not win it by being there. Each
     * member of each placed plot is owed the list every entry is paid, and the members of the plots placed 1 to
     * {@code placesPaid} the list of their place; a member is paid only if they joined the team before the votes
     * began, so a team filled once the building is judged earns nothing more.
     *
     * @param members each member of a plot with when they joined its team, read when the contest is settled
     * @return the contest settled with its placed plots, best first, or nothing when none was due
     */
    public Optional<Settled> settleIfDue(
            int placesPaid, int minVotes, Function<IslandId, Map<PlayerUuid, Instant>> members) {
        Optional<Contest> under = current();
        if (under.isEmpty() || under.get().phaseAt(clock.instant()) != Contest.Phase.ENDED) {
            return Optional.empty();
        }
        Contest contest = under.get();
        List<ContestStanding> all = standings(contest.number());
        List<ContestStanding> placed =
                all.stream().filter(standing -> standing.votes() >= minVotes).toList();
        List<ContestPayout> payouts = new ArrayList<>();
        for (int at = 0; at < placed.size(); at++) {
            IslandId plot = placed.get(at).island();
            for (Map.Entry<PlayerUuid, Instant> member : members.apply(plot).entrySet()) {
                if (member.getValue().isAfter(contest.buildsUntil())) {
                    continue;
                }
                payouts.add(new ContestPayout(contest.number(), plot, member.getKey(), ContestPayout.ENTERED));
                if (at < placesPaid) {
                    payouts.add(new ContestPayout(contest.number(), plot, member.getKey(), at + 1));
                }
            }
        }
        port.settle(contest.number(), payouts);
        return Optional.of(new Settled(contest.settle(), placed, all.size()));
    }

    /** What a player is owed and has not been paid. */
    public List<ContestPayout> unpaid(PlayerUuid player) {
        return port.unpaid(player);
    }

    /** Marks a payout paid; false when it was paid already. */
    public boolean markPaid(ContestPayout payout) {
        return port.markPaid(payout);
    }

    /**
     * A contest just settled.
     *
     * @param standings its placed plots, best first
     * @param entered how many plots were entered, placed or not
     */
    public record Settled(Contest contest, List<ContestStanding> standings, int entered) {

        public Settled {
            Objects.requireNonNull(contest, "contest must not be null");
            standings = List.copyOf(standings);
        }
    }
}
