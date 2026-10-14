package com.uxplima.uxmskyblock.core.application.brix;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.domain.brix.Contest;
import com.uxplima.uxmskyblock.core.domain.brix.ContestPayout;
import com.uxplima.uxmskyblock.core.domain.brix.ContestStanding;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A Brix contest opens, takes plots while it builds, votes while it votes, and places them once it ends. */
class AContestIsBuiltVotedAndPlacedTest {

    private static final Instant START = Instant.parse("2026-10-14T12:00:00Z");

    private final Kept kept = new Kept();
    private Instant now = START;
    private final BrixContests contests = new BrixContests(kept, new Clock() {
        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    });

    private final IslandId first = IslandId.of(UUID.randomUUID());
    private final IslandId second = IslandId.of(UUID.randomUUID());
    private final IslandId third = IslandId.of(UUID.randomUUID());
    private final PlayerUuid ann = player();
    private final PlayerUuid bob = player();
    private final PlayerUuid cem = player();

    @Test
    @DisplayName("Plots enter while it builds, are voted for while it votes, and nothing is taken out of its phase")
    void thePhasesHold() {
        assertThat(contests.enter(first)).isEqualTo(BrixContests.Outcome.NONE);
        assertThat(contests.vote(first, ann, 3, 5)).isEqualTo(BrixContests.Outcome.NONE);
        assertThat(contests.close()).isEqualTo(BrixContests.Outcome.NONE);

        assertThat(contests.open("  A lighthouse ", Duration.ofHours(2), Duration.ofHours(1)))
                .isEqualTo(BrixContests.Outcome.DONE);
        assertThat(contests.current()).get().extracting(Contest::theme).isEqualTo("A lighthouse");
        assertThat(contests.open("A bridge", Duration.ofHours(2), Duration.ofHours(1)))
                .isEqualTo(BrixContests.Outcome.UNDER_WAY);

        assertThat(contests.enter(first)).isEqualTo(BrixContests.Outcome.DONE);
        assertThat(contests.enter(first)).isEqualTo(BrixContests.Outcome.ALREADY);
        assertThat(contests.entered(first)).isTrue();
        assertThat(contests.entered(second)).isFalse();
        assertThat(contests.vote(first, ann, 3, 5))
                .describedAs("before the votes")
                .isEqualTo(BrixContests.Outcome.WRONG_PHASE);

        now = START.plus(Duration.ofHours(2)).minusMillis(1);
        assertThat(contests.enter(second))
                .describedAs("the building's last moment")
                .isEqualTo(BrixContests.Outcome.DONE);
        now = START.plus(Duration.ofHours(2));
        assertThat(contests.enter(third)).describedAs("the votes begun").isEqualTo(BrixContests.Outcome.WRONG_PHASE);

        assertThat(contests.vote(first, ann, 0, 5)).isEqualTo(BrixContests.Outcome.BAD_STARS);
        assertThat(contests.vote(first, ann, 6, 5)).isEqualTo(BrixContests.Outcome.BAD_STARS);
        assertThat(contests.vote(third, ann, 3, 5)).isEqualTo(BrixContests.Outcome.NOT_ENTERED);
        assertThat(contests.vote(first, ann, 1, 5)).isEqualTo(BrixContests.Outcome.DONE);
        assertThat(contests.vote(first, ann, 5, 5)).isEqualTo(BrixContests.Outcome.DONE);
        assertThat(kept.votes).describedAs("voted again, kept once").containsEntry(List.of(1, first, ann), 5);

        now = START.plus(Duration.ofHours(3)).minusMillis(1);
        assertThat(contests.settleIfDue(3, plot -> Set.of()))
                .describedAs("the votes' last moment")
                .isEmpty();
        now = START.plus(Duration.ofHours(3));
        assertThat(contests.vote(second, ann, 3, 5))
                .describedAs("the votes over")
                .isEqualTo(BrixContests.Outcome.WRONG_PHASE);
        assertThat(contests.enter(third)).isEqualTo(BrixContests.Outcome.WRONG_PHASE);
    }

    @Test
    @DisplayName("Once the votes end the plots are placed by their average, then their votes; each member is owed")
    void thePlotsArePlaced() {
        contests.open("A lighthouse", Duration.ofHours(1), Duration.ofHours(1));
        IslandId fourth = IslandId.of(UUID.randomUUID());
        for (IslandId plot : List.of(first, second, third, fourth)) {
            contests.enter(plot);
        }
        now = START.plus(Duration.ofHours(1));
        // first: 4 and 4, average 4 from two; second: 5, average 5 from one; third: 4, average 4 from one.
        contests.vote(first, ann, 4, 5);
        contests.vote(first, bob, 4, 5);
        contests.vote(second, ann, 5, 5);
        contests.vote(third, cem, 4, 5);

        Map<IslandId, Set<PlayerUuid>> teams = Map.of(first, Set.of(ann), second, Set.of(bob, cem), third, Set.of(cem));
        assertThat(contests.settleIfDue(2, plot -> teams.getOrDefault(plot, Set.of())))
                .describedAs("not before the votes end")
                .isEmpty();
        now = START.plus(Duration.ofHours(2));
        Optional<BrixContests.Settled> settled = contests.settleIfDue(2, plot -> teams.getOrDefault(plot, Set.of()));

        assertThat(settled).isPresent();
        assertThat(settled.get().contest().settled()).isTrue();
        assertThat(settled.get().standings())
                .extracting(ContestStanding::island)
                .containsExactly(second, first, third, fourth);
        assertThat(contests.current()).describedAs("settled, none under way").isEmpty();
        assertThat(contests.latest()).get().extracting(Contest::settled).isEqualTo(true);
        assertThat(contests.find(1)).get().extracting(Contest::theme).isEqualTo("A lighthouse");
        assertThat(kept.payouts)
                .containsExactlyInAnyOrder(
                        new ContestPayout(1, second, bob, ContestPayout.ENTERED),
                        new ContestPayout(1, second, bob, 1),
                        new ContestPayout(1, second, cem, ContestPayout.ENTERED),
                        new ContestPayout(1, second, cem, 1),
                        new ContestPayout(1, first, ann, ContestPayout.ENTERED),
                        new ContestPayout(1, first, ann, 2),
                        new ContestPayout(1, third, cem, ContestPayout.ENTERED));
        assertThat(contests.settleIfDue(2, plot -> Set.of()))
                .describedAs("settled once")
                .isEmpty();

        assertThat(contests.unpaid(ann)).hasSize(2);
        assertThat(contests.markPaid(new ContestPayout(1, first, ann, 2))).isTrue();
        assertThat(contests.markPaid(new ContestPayout(1, first, ann, 2))).isFalse();
        assertThat(contests.unpaid(ann)).containsExactly(new ContestPayout(1, first, ann, ContestPayout.ENTERED));

        assertThat(contests.open("A bridge", Duration.ofHours(1), Duration.ofHours(1)))
                .isEqualTo(BrixContests.Outcome.DONE);
        assertThat(contests.current()).get().extracting(Contest::number).isEqualTo(2);
        assertThat(contests.close()).isEqualTo(BrixContests.Outcome.DONE);
        assertThat(kept.closed).containsExactly(2);
    }

    @Test
    @DisplayName("Equal on average and on votes, plots are placed the same way each time they are read")
    void tiesAreSteady() {
        contests.open("A lighthouse", Duration.ZERO, Duration.ofHours(1));
        kept.entries.add(first);
        kept.entries.add(second);
        List<IslandId> once =
                contests.standings(1).stream().map(ContestStanding::island).toList();
        java.util.Collections.reverse(kept.order);
        assertThat(contests.standings(1).stream().map(ContestStanding::island).toList())
                .isEqualTo(once);
    }

    private static PlayerUuid player() {
        return new PlayerUuid(UUID.randomUUID());
    }

    /** The contests in memory, one at a time as the service keeps them. */
    private static final class Kept implements BrixContestsPort {

        private final List<Contest> all = new ArrayList<>();
        private final Set<IslandId> entries = new HashSet<>();
        private final List<IslandId> order = new ArrayList<>();
        private final Map<List<Object>, Integer> votes = new LinkedHashMap<>();
        private final List<ContestPayout> payouts = new ArrayList<>();
        private final Set<ContestPayout> paid = new HashSet<>();
        private final List<Integer> closed = new ArrayList<>();

        @Override
        public Optional<Contest> latest() {
            return all.isEmpty() ? Optional.empty() : Optional.of(all.get(all.size() - 1));
        }

        @Override
        public Optional<Contest> find(int contest) {
            return all.stream().filter(each -> each.number() == contest).findFirst();
        }

        @Override
        public void open(Contest contest) {
            all.add(contest);
            entries.clear();
            order.clear();
        }

        @Override
        public void close(int contest) {
            closed.add(contest);
            all.removeIf(each -> each.number() == contest);
        }

        @Override
        public boolean enter(int contest, IslandId island) {
            order.add(island);
            return entries.add(island);
        }

        @Override
        public Set<IslandId> entries(int contest) {
            return Set.copyOf(entries);
        }

        @Override
        public void vote(int contest, IslandId island, PlayerUuid voter, int stars) {
            votes.put(List.of(contest, island, voter), stars);
        }

        @Override
        public OptionalInt voteOf(int contest, IslandId island, PlayerUuid voter) {
            Integer stars = votes.get(List.of(contest, island, voter));
            return stars == null ? OptionalInt.empty() : OptionalInt.of(stars);
        }

        @Override
        public List<ContestStanding> standings(int contest) {
            Map<IslandId, int[]> sums = new HashMap<>();
            List<IslandId> ordered = new ArrayList<>(order);
            for (IslandId entry : entries) {
                if (!ordered.contains(entry)) {
                    ordered.add(entry);
                }
            }
            for (IslandId entry : ordered) {
                sums.put(entry, new int[2]);
            }
            votes.forEach((key, stars) -> {
                int[] sum = sums.get((IslandId) key.get(1));
                if (sum != null) {
                    sum[0] += stars;
                    sum[1]++;
                }
            });
            return ordered.stream()
                    .map(entry -> {
                        int[] sum = sums.getOrDefault(entry, new int[2]);
                        return new ContestStanding(entry, sum[0], sum[1]);
                    })
                    .toList();
        }

        @Override
        public void settle(int contest, List<ContestPayout> owed) {
            all.replaceAll(each -> each.number() == contest ? each.settle() : each);
            payouts.addAll(owed);
        }

        @Override
        public List<ContestPayout> unpaid(PlayerUuid player) {
            return payouts.stream()
                    .filter(each -> each.player().equals(player) && !paid.contains(each))
                    .toList();
        }

        @Override
        public boolean markPaid(ContestPayout payout) {
            return paid.add(payout);
        }
    }
}
