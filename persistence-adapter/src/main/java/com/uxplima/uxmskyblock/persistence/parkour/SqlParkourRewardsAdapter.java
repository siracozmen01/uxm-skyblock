package com.uxplima.uxmskyblock.persistence.parkour;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Objects;

import javax.sql.DataSource;

import com.uxplima.uxmskyblock.core.application.parkour.ParkourRewardsPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;

/** When each runner was last paid each reward list of each course, in {@code parkour_rewards_paid}. */
public final class SqlParkourRewardsAdapter implements ParkourRewardsPort {

    private final DataSource dataSource;

    public SqlParkourRewardsAdapter(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
    }

    @Override
    public boolean claim(IslandId course, PlayerUuid runner, String list, long now, long paidBefore) {
        String island = course.value().toString();
        String player = runner.value().toString();
        try (Connection conn = dataSource.getConnection()) {
            // A row whose period has passed is taken by the one update that finds it so; a list never paid is taken
            // by the one insert the primary key lets in.
            try (PreparedStatement ps = conn.prepareStatement("UPDATE parkour_rewards_paid SET paid_at = ?"
                    + " WHERE island_id = ? AND player_uuid = ? AND list = ? AND paid_at <= ?")) {
                ps.setLong(1, now);
                ps.setString(2, island);
                ps.setString(3, player);
                ps.setString(4, list);
                ps.setLong(5, paidBefore);
                if (ps.executeUpdate() > 0) {
                    return true;
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO parkour_rewards_paid (island_id, player_uuid, list, paid_at) VALUES (?, ?, ?, ?)")) {
                ps.setString(1, island);
                ps.setString(2, player);
                ps.setString(3, list);
                ps.setLong(4, now);
                ps.executeUpdate();
                return true;
            } catch (SQLException e) {
                if (isDuplicate(e)) {
                    return false;
                }
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not claim the " + list + " rewards on course " + course, e);
        }
    }

    private static boolean isDuplicate(SQLException e) {
        String state = e.getSQLState();
        return (state != null && state.startsWith("23")) || (state == null && e.getErrorCode() == 19);
    }
}
