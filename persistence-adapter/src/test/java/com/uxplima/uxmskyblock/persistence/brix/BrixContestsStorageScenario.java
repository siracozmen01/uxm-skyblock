package com.uxplima.uxmskyblock.persistence.brix;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.OptionalInt;
import java.util.UUID;

import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.domain.brix.Contest;
import com.uxplima.uxmskyblock.core.domain.brix.ContestPayout;
import com.uxplima.uxmskyblock.core.domain.brix.ContestStanding;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;

/** What every engine must keep of the Brix build contests. */
final class BrixContestsStorageScenario {

    private BrixContestsStorageScenario() {}

    static void run(Database database) throws SQLException {
        SqlBrixPlotsAdapter plots = new SqlBrixPlotsAdapter(database.dataSource());
        SqlBrixContestsAdapter contests = new SqlBrixContestsAdapter(database.dataSource());
        IslandId first = island(database);
        IslandId second = island(database);
        IslandId unvoted = island(database);
        plots.add(first);
        plots.add(second);
        plots.add(unvoted);
        assertThat(contests.latest()).isEmpty();

        Instant at = Instant.ofEpochMilli(1_760_000_000_123L);
        Contest one = new Contest(1, "A lighthouse", at, at.plusSeconds(3600), at.plusSeconds(7200), false);
        contests.open(one);
        assertThat(contests.latest()).contains(one);

        assertThat(contests.enter(1, first)).isTrue();
        assertThat(contests.enter(1, first)).describedAs("entered once").isFalse();
        assertThat(contests.enter(1, second)).isTrue();
        assertThat(contests.enter(1, unvoted)).isTrue();
        assertThat(contests.entries(1)).containsExactlyInAnyOrder(first, second, unvoted);

        PlayerUuid ann = new PlayerUuid(UUID.randomUUID());
        PlayerUuid bob = new PlayerUuid(UUID.randomUUID());
        contests.vote(1, first, ann, 2);
        contests.vote(1, first, ann, 5);
        contests.vote(1, first, bob, 4);
        contests.vote(1, second, ann, 3);
        assertThat(contests.voteOf(1, first, ann))
                .describedAs("voted again, kept once")
                .isEqualTo(OptionalInt.of(5));
        assertThat(contests.voteOf(1, second, bob)).isEmpty();
        assertThat(contests.standings(1))
                .containsExactlyInAnyOrder(
                        new ContestStanding(first, 9, 2),
                        new ContestStanding(second, 3, 1),
                        new ContestStanding(unvoted, 0, 0));

        ContestPayout won = new ContestPayout(1, first, ann, 1);
        ContestPayout entered = new ContestPayout(1, first, ann, ContestPayout.ENTERED);
        ContestPayout bobs = new ContestPayout(1, second, bob, 2);
        contests.settle(1, List.of(won, entered, bobs));
        assertThat(contests.latest()).contains(one.settle());
        assertThat(contests.unpaid(ann)).containsExactly(entered, won);
        assertThat(contests.markPaid(won)).isTrue();
        assertThat(contests.markPaid(won)).describedAs("paid once").isFalse();
        assertThat(contests.unpaid(ann)).containsExactly(entered);
        assertThat(contests.unpaid(bob)).containsExactly(bobs);

        Contest two = new Contest(2, "A bridge", at, at.plusSeconds(60), at.plusSeconds(120), false);
        contests.open(two);
        assertThat(contests.latest()).describedAs("the highest number").contains(two);
        assertThat(contests.find(1)).contains(one.settle());
        assertThat(contests.find(2)).contains(two);
        assertThat(contests.find(3)).isEmpty();
        contests.enter(2, first);
        contests.vote(2, first, bob, 1);
        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement("DELETE FROM brix_plots WHERE island_id = ?")) {
            ps.setString(1, first.value().toString());
            ps.executeUpdate();
        }
        assertThat(contests.entries(2))
                .describedAs("an entry goes with its plot")
                .isEmpty();
        assertThat(contests.voteOf(2, first, bob))
                .describedAs("and its votes with it")
                .isEmpty();

        contests.close(1);
        assertThat(contests.latest()).contains(two);
        assertThat(contests.find(1)).isEmpty();
        assertThat(contests.unpaid(ann))
                .describedAs("a contest taken off takes what it owed")
                .isEmpty();
        assertThat(contests.entries(1)).isEmpty();
        contests.close(2);
        assertThat(contests.latest()).isEmpty();
    }

    private static IslandId island(Database database) throws SQLException {
        IslandId island = IslandId.of(UUID.randomUUID());
        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO islands (id, owner_account_uuid, owner_profile_id, lifecycle, custom_name,"
                                + " created_at, updated_at) VALUES (?, ?, ?, 'ACTIVE', NULL, CURRENT_TIMESTAMP,"
                                + " CURRENT_TIMESTAMP)")) {
            ps.setString(1, island.value().toString());
            ps.setString(2, UUID.randomUUID().toString());
            ps.setString(3, UUID.randomUUID().toString());
            ps.executeUpdate();
        }
        return island;
    }
}
