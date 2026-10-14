package com.uxplima.uxmskyblock.persistence.brix;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

import javax.sql.DataSource;

import com.uxplima.uxmskyblock.core.application.brix.BrixContestsPort;
import com.uxplima.uxmskyblock.core.domain.brix.Contest;
import com.uxplima.uxmskyblock.core.domain.brix.ContestPayout;
import com.uxplima.uxmskyblock.core.domain.brix.ContestStanding;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;

/**
 * The {@code brix_contests}, {@code brix_contest_entries}, {@code brix_contest_votes} and {@code brix_contest_payouts}
 * tables. Each instant is kept as milliseconds since the epoch.
 */
public final class SqlBrixContestsAdapter implements BrixContestsPort {

    private final DataSource dataSource;

    public SqlBrixContestsAdapter(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
    }

    private static final String COLUMNS =
            "SELECT contest_number, theme, opens_at, builds_until, votes_until, settled FROM brix_contests";

    @Override
    public Optional<Contest> latest() {
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(COLUMNS + " ORDER BY contest_number DESC");
                ResultSet rs = ps.executeQuery()) {
            return rs.next() ? Optional.of(contest(rs)) : Optional.empty();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the last Brix contest", e);
        }
    }

    @Override
    public Optional<Contest> find(int contest) {
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(COLUMNS + " WHERE contest_number = ?")) {
            ps.setInt(1, contest);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(contest(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read Brix contest " + contest, e);
        }
    }

    private static Contest contest(ResultSet rs) throws SQLException {
        return new Contest(
                rs.getInt(1),
                rs.getString(2),
                Instant.ofEpochMilli(rs.getLong(3)),
                Instant.ofEpochMilli(rs.getLong(4)),
                Instant.ofEpochMilli(rs.getLong(5)),
                rs.getInt(6) != 0);
    }

    @Override
    public void open(Contest contest) {
        Objects.requireNonNull(contest, "contest must not be null");
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO brix_contests (contest_number, theme, opens_at, builds_until, votes_until, settled)"
                                + " VALUES (?, ?, ?, ?, ?, ?)")) {
            ps.setInt(1, contest.number());
            ps.setString(2, contest.theme());
            ps.setLong(3, contest.opensAt().toEpochMilli());
            ps.setLong(4, contest.buildsUntil().toEpochMilli());
            ps.setLong(5, contest.votesUntil().toEpochMilli());
            ps.setInt(6, contest.settled() ? 1 : 0);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not open Brix contest " + contest.number(), e);
        }
    }

    @Override
    public void close(int contest) {
        // The entries, votes and payouts go with it by their keys; the votes are taken first for the engines that
        // cascade one step at a time.
        inTransaction("close Brix contest " + contest, conn -> {
            for (String table :
                    List.of("brix_contest_votes", "brix_contest_payouts", "brix_contest_entries", "brix_contests")) {
                try (PreparedStatement ps =
                        conn.prepareStatement("DELETE FROM " + table + " WHERE contest_number = ?")) {
                    ps.setInt(1, contest);
                    ps.executeUpdate();
                }
            }
            return Boolean.TRUE;
        });
    }

    @Override
    public boolean enter(int contest, IslandId island) {
        Objects.requireNonNull(island, "island must not be null");
        return inTransaction("enter a plot in Brix contest " + contest, conn -> {
            if (entries(conn, contest).contains(island)) {
                return false;
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO brix_contest_entries (contest_number, island_id) VALUES (?, ?)")) {
                ps.setInt(1, contest);
                ps.setString(2, island.value().toString());
                ps.executeUpdate();
            }
            return true;
        });
    }

    @Override
    public Set<IslandId> entries(int contest) {
        try (Connection conn = dataSource.getConnection()) {
            return entries(conn, contest);
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the plots of Brix contest " + contest, e);
        }
    }

    private static Set<IslandId> entries(Connection conn, int contest) throws SQLException {
        Set<IslandId> entered = new HashSet<>();
        try (PreparedStatement ps =
                conn.prepareStatement("SELECT island_id FROM brix_contest_entries WHERE contest_number = ?")) {
            ps.setInt(1, contest);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    entered.add(IslandId.of(UUID.fromString(rs.getString(1))));
                }
            }
        }
        return entered;
    }

    @Override
    public void vote(int contest, IslandId island, PlayerUuid voter, int stars) {
        Objects.requireNonNull(island, "island must not be null");
        Objects.requireNonNull(voter, "voter must not be null");
        inTransaction("keep a vote in Brix contest " + contest, conn -> {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM brix_contest_votes"
                    + " WHERE contest_number = ? AND island_id = ? AND voter_uuid = ?")) {
                ps.setInt(1, contest);
                ps.setString(2, island.value().toString());
                ps.setString(3, voter.value().toString());
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO brix_contest_votes"
                    + " (contest_number, island_id, voter_uuid, stars) VALUES (?, ?, ?, ?)")) {
                ps.setInt(1, contest);
                ps.setString(2, island.value().toString());
                ps.setString(3, voter.value().toString());
                ps.setInt(4, stars);
                ps.executeUpdate();
            }
            return Boolean.TRUE;
        });
    }

    @Override
    public OptionalInt voteOf(int contest, IslandId island, PlayerUuid voter) {
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement("SELECT stars FROM brix_contest_votes"
                        + " WHERE contest_number = ? AND island_id = ? AND voter_uuid = ?")) {
            ps.setInt(1, contest);
            ps.setString(2, island.value().toString());
            ps.setString(3, voter.value().toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? OptionalInt.of(rs.getInt(1)) : OptionalInt.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read a vote of Brix contest " + contest, e);
        }
    }

    @Override
    public List<ContestStanding> standings(int contest) {
        List<ContestStanding> all = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement("SELECT e.island_id, COALESCE(SUM(v.stars), 0),"
                        + " COUNT(v.voter_uuid) FROM brix_contest_entries e LEFT JOIN brix_contest_votes v"
                        + " ON v.contest_number = e.contest_number AND v.island_id = e.island_id"
                        + " WHERE e.contest_number = ? GROUP BY e.island_id")) {
            ps.setInt(1, contest);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    all.add(new ContestStanding(
                            IslandId.of(UUID.fromString(rs.getString(1))), rs.getInt(2), rs.getInt(3)));
                }
            }
            return all;
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the standings of Brix contest " + contest, e);
        }
    }

    @Override
    public void settle(int contest, List<ContestPayout> payouts) {
        inTransaction("settle Brix contest " + contest, conn -> {
            try (PreparedStatement ps =
                    conn.prepareStatement("UPDATE brix_contests SET settled = 1 WHERE contest_number = ?")) {
                ps.setInt(1, contest);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO brix_contest_payouts"
                    + " (contest_number, island_id, player_uuid, place, paid) VALUES (?, ?, ?, ?, 0)")) {
                for (ContestPayout payout : payouts) {
                    ps.setInt(1, payout.contest());
                    ps.setString(2, payout.island().value().toString());
                    ps.setString(3, payout.player().value().toString());
                    ps.setInt(4, payout.place());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return Boolean.TRUE;
        });
    }

    @Override
    public List<ContestPayout> unpaid(PlayerUuid player) {
        List<ContestPayout> owed = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement("SELECT contest_number, island_id, place"
                        + " FROM brix_contest_payouts WHERE player_uuid = ? AND paid = 0"
                        + " ORDER BY contest_number, place")) {
            ps.setString(1, player.value().toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    owed.add(new ContestPayout(
                            rs.getInt(1), IslandId.of(UUID.fromString(rs.getString(2))), player, rs.getInt(3)));
                }
            }
            return owed;
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read what " + player + " is owed of the Brix contests", e);
        }
    }

    @Override
    public boolean markPaid(ContestPayout payout) {
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(
                        "UPDATE brix_contest_payouts SET paid = 1"
                                + " WHERE contest_number = ? AND island_id = ? AND player_uuid = ? AND place = ? AND paid = 0")) {
            ps.setInt(1, payout.contest());
            ps.setString(2, payout.island().value().toString());
            ps.setString(3, payout.player().value().toString());
            ps.setInt(4, payout.place());
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("Could not mark a payout of Brix contest " + payout.contest() + " paid", e);
        }
    }

    private interface Work<T> {
        T in(Connection conn) throws SQLException;
    }

    private <T> T inTransaction(String what, Work<T> work) {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                T done = work.in(conn);
                conn.commit();
                return done;
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not " + what, e);
        }
    }
}
