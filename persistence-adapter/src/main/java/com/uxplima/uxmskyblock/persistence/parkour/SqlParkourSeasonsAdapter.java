package com.uxplima.uxmskyblock.persistence.parkour;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import com.uxplima.uxmskyblock.core.application.parkour.ParkourPort;
import com.uxplima.uxmskyblock.core.application.parkour.ParkourSeasonsPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;

/** The season times in parkour_season_times, one row per course, season and runner. */
public final class SqlParkourSeasonsAdapter implements ParkourSeasonsPort {

    private final DataSource dataSource;

    public SqlParkourSeasonsAdapter(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
    }

    @Override
    public void finish(IslandId course, int season, PlayerUuid runner, long millis) {
        String island = course.value().toString();
        String player = runner.value().toString();
        try (Connection conn = dataSource.getConnection()) {
            // The first finish of a season inserts; every later one keeps the faster time. Two finishes racing
            // to insert meet the primary key, and the loser updates instead.
            if (update(conn, island, season, player, millis) == 0) {
                try (PreparedStatement ps = conn.prepareStatement("INSERT INTO parkour_season_times"
                        + " (island_id, season_number, player_uuid, best_millis, runs) VALUES (?, ?, ?, ?, 1)")) {
                    ps.setString(1, island);
                    ps.setInt(2, season);
                    ps.setString(3, player);
                    ps.setLong(4, millis);
                    ps.executeUpdate();
                } catch (SQLException e) {
                    if (!isDuplicate(e)) {
                        throw e;
                    }
                    update(conn, island, season, player, millis);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not count a run of season " + season + " on course " + course, e);
        }
    }

    private static int update(Connection conn, String island, int season, String player, long millis)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE parkour_season_times SET runs = runs + 1,"
                + " best_millis = CASE WHEN ? < best_millis THEN ? ELSE best_millis END"
                + " WHERE island_id = ? AND season_number = ? AND player_uuid = ?")) {
            ps.setLong(1, millis);
            ps.setLong(2, millis);
            ps.setString(3, island);
            ps.setInt(4, season);
            ps.setString(5, player);
            return ps.executeUpdate();
        }
    }

    @Override
    public List<ParkourPort.Best> top(IslandId course, int season, int limit) {
        List<ParkourPort.Best> top = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement("SELECT player_uuid, best_millis, runs"
                        + " FROM parkour_season_times WHERE island_id = ? AND season_number = ?"
                        + " ORDER BY best_millis, player_uuid")) {
            ps.setString(1, course.value().toString());
            ps.setInt(2, season);
            ps.setMaxRows(Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next() && top.size() < limit) {
                    top.add(new ParkourPort.Best(
                            PlayerUuid.of(UUID.fromString(rs.getString(1))), rs.getLong(2), rs.getInt(3)));
                }
            }
            return top;
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read season " + season + " on course " + course, e);
        }
    }

    @Override
    public int clear(IslandId course, Optional<PlayerUuid> runner) {
        Objects.requireNonNull(course, "course must not be null");
        Objects.requireNonNull(runner, "runner must not be null");
        String sql = runner.isPresent()
                ? "DELETE FROM parkour_season_times WHERE island_id = ? AND player_uuid = ?"
                : "DELETE FROM parkour_season_times WHERE island_id = ?";
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, course.value().toString());
            if (runner.isPresent()) {
                ps.setString(2, runner.get().value().toString());
            }
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not forget the season times on course " + course, e);
        }
    }

    private static boolean isDuplicate(SQLException e) {
        String state = e.getSQLState();
        return (state != null && state.startsWith("23")) || (state == null && e.getErrorCode() == 19);
    }
}
