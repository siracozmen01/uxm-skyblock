package com.uxplima.uxmskyblock.core.application.brix;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

import com.uxplima.uxmskyblock.core.domain.brix.Contest;
import com.uxplima.uxmskyblock.core.domain.brix.ContestPayout;
import com.uxplima.uxmskyblock.core.domain.brix.ContestStanding;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;

/** Where the Brix build contests are kept. */
public interface BrixContestsPort {

    /** The contest numbered highest, settled or not. */
    Optional<Contest> latest();

    /** The contest of a number, settled or not. */
    Optional<Contest> find(int contest);

    /** Keeps a new contest. */
    void open(Contest contest);

    /** Takes a contest off, with its entries, votes and payouts. */
    void close(int contest);

    /** Enters a plot. Answers false when it was entered already. */
    boolean enter(int contest, IslandId island);

    /** The plots entered. */
    Set<IslandId> entries(int contest);

    /** Keeps a voter's stars for a plot, over any they gave it before. */
    void vote(int contest, IslandId island, PlayerUuid voter, int stars);

    /** The stars a voter gave a plot, if any. */
    OptionalInt voteOf(int contest, IslandId island, PlayerUuid voter);

    /** Every entered plot with its stars and votes, any order. */
    List<ContestStanding> standings(int contest);

    /** Marks a contest settled and keeps what its placed members are owed, at once. */
    void settle(int contest, List<ContestPayout> payouts);

    /** What a player is owed and has not been paid, of every contest. */
    List<ContestPayout> unpaid(PlayerUuid player);

    /** Marks one payout paid. Answers false when it was paid already, so it is paid once. */
    boolean markPaid(ContestPayout payout);
}
