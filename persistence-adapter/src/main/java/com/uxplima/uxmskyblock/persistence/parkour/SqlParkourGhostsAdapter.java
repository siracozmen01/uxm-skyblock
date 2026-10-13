package com.uxplima.uxmskyblock.persistence.parkour;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import com.uxplima.uxmskyblock.core.application.parkour.GhostPath;
import com.uxplima.uxmskyblock.core.application.parkour.ParkourGhostsPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;

/** Each runner's best run on each course in {@code parkour_ghosts}, as its ghost replays it. */
public final class SqlParkourGhostsAdapter implements ParkourGhostsPort {

    private final DataSource dataSource;

    public SqlParkourGhostsAdapter(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
    }

    @Override
    public void keep(IslandId course, PlayerUuid runner, long millis, GhostPath path) {
        String island = course.value().toString();
        String player = runner.value().toString();
        byte[] bytes = path.encode();
        try (Connection conn = dataSource.getConnection()) {
            // A faster run replaces the one kept; a slower one, or one as fast, leaves it. A run with none to replace
            // inserts, and meets the primary key when one was kept after all, which leaves it to the update.
            if (replace(conn, island, player, millis, bytes) == 0) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO parkour_ghosts (island_id, player_uuid, millis, path) VALUES (?, ?, ?, ?)")) {
                    ps.setString(1, island);
                    ps.setString(2, player);
                    ps.setLong(3, millis);
                    ps.setBytes(4, bytes);
                    ps.executeUpdate();
                } catch (SQLException e) {
                    if (!isDuplicate(e)) {
                        throw e;
                    }
                    replace(conn, island, player, millis, bytes);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not keep a ghost on course " + course, e);
        }
    }

    private static int replace(Connection conn, String island, String player, long millis, byte[] bytes)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE parkour_ghosts SET millis = ?, path = ?"
                + " WHERE island_id = ? AND player_uuid = ? AND millis > ?")) {
            ps.setLong(1, millis);
            ps.setBytes(2, bytes);
            ps.setString(3, island);
            ps.setString(4, player);
            ps.setLong(5, millis);
            return ps.executeUpdate();
        }
    }

    @Override
    public Optional<Ghost> of(IslandId course, PlayerUuid runner) {
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement("SELECT player_uuid, millis, path FROM parkour_ghosts"
                        + " WHERE island_id = ? AND player_uuid = ?")) {
            ps.setString(1, course.value().toString());
            ps.setString(2, runner.value().toString());
            return read(ps);
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read a ghost on course " + course, e);
        }
    }

    @Override
    public Optional<Ghost> fastest(IslandId course) {
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement("SELECT player_uuid, millis, path FROM parkour_ghosts"
                        + " WHERE island_id = ? ORDER BY millis, player_uuid")) {
            ps.setString(1, course.value().toString());
            ps.setMaxRows(1);
            return read(ps);
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the fastest ghost on course " + course, e);
        }
    }

    private static Optional<Ghost> read(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) {
                return Optional.empty();
            }
            return Optional.of(new Ghost(
                    PlayerUuid.of(UUID.fromString(rs.getString(1))), rs.getLong(2), GhostPath.decode(rs.getBytes(3))));
        }
    }

    private static boolean isDuplicate(SQLException e) {
        String state = e.getSQLState();
        return (state != null && state.startsWith("23")) || (state == null && e.getErrorCode() == 19);
    }
}
