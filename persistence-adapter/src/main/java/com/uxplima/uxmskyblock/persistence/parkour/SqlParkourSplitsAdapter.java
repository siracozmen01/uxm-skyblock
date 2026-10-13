package com.uxplima.uxmskyblock.persistence.parkour;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import javax.sql.DataSource;

import com.uxplima.uxmskyblock.core.application.parkour.ParkourSplitsPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;

/** The split times of each runner's best run on each course, a row for each checkpoint, in {@code parkour_splits}. */
public final class SqlParkourSplitsAdapter implements ParkourSplitsPort {

    private final DataSource dataSource;

    public SqlParkourSplitsAdapter(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
    }

    @Override
    public Map<Checkpoint, Long> best(IslandId course, PlayerUuid runner) {
        Map<Checkpoint, Long> splits = new HashMap<>();
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(
                        "SELECT x, y, z, millis FROM parkour_splits WHERE island_id = ? AND player_uuid = ?")) {
            ps.setString(1, course.value().toString());
            ps.setString(2, runner.value().toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    splits.put(new Checkpoint(rs.getInt(1), rs.getInt(2), rs.getInt(3)), rs.getLong(4));
                }
            }
            return Map.copyOf(splits);
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the split times on course " + course, e);
        }
    }

    @Override
    public void keep(IslandId course, PlayerUuid runner, Map<Checkpoint, Long> splits) {
        String island = course.value().toString();
        String player = runner.value().toString();
        try (Connection conn = dataSource.getConnection()) {
            // The old splits go and the new come in one transaction, so a reader sees one run's or the other's.
            boolean auto = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps =
                        conn.prepareStatement("DELETE FROM parkour_splits WHERE island_id = ? AND player_uuid = ?")) {
                    ps.setString(1, island);
                    ps.setString(2, player);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement("INSERT INTO parkour_splits"
                        + " (island_id, player_uuid, x, y, z, millis) VALUES (?, ?, ?, ?, ?, ?)")) {
                    for (Map.Entry<Checkpoint, Long> split : splits.entrySet()) {
                        ps.setString(1, island);
                        ps.setString(2, player);
                        ps.setInt(3, split.getKey().x());
                        ps.setInt(4, split.getKey().y());
                        ps.setInt(5, split.getKey().z());
                        ps.setLong(6, split.getValue());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(auto);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not keep the split times on course " + course, e);
        }
    }
}
