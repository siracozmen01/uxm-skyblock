package com.uxplima.uxmskyblock.persistence.oneblock;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import javax.sql.DataSource;

import com.uxplima.uxmskyblock.core.application.oneblock.OneBlockRewardPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.persistence.chunkblock.SqlConstraint;

/** {@link OneBlockRewardPort} over the {@code oneblock_rewards} table, on every supported database. */
public final class SqlOneBlockRewardAdapter implements OneBlockRewardPort {

    private final DataSource dataSource;

    public SqlOneBlockRewardAdapter(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
    }

    @Override
    public List<Paid> findAll() {
        String sql = "SELECT island_id, phase, reward, paid_cycle FROM oneblock_rewards";
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {
            return read(rs);
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read what the OneBlock islands were paid", e);
        }
    }

    @Override
    public List<Paid> find(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        String sql = "SELECT island_id, phase, reward, paid_cycle FROM oneblock_rewards WHERE island_id = ?";
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, islandId.value().toString());
            try (ResultSet rs = ps.executeQuery()) {
                return read(rs);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read what OneBlock island " + islandId + " was paid", e);
        }
    }

    private static List<Paid> read(ResultSet rs) throws SQLException {
        List<Paid> all = new ArrayList<>();
        while (rs.next()) {
            all.add(new Paid(IslandId.fromString(rs.getString(1)), rs.getString(2), rs.getString(3), rs.getLong(4)));
        }
        return all;
    }

    /**
     * Raises the row's pass when it is below {@code cycle}, or writes the row when there is none. Two nodes paying
     * at once both try; the raise is one statement, and of two inserts the second is refused.
     */
    @Override
    public boolean pay(IslandId islandId, String phase, String reward, long cycle) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(phase, "phase must not be null");
        Objects.requireNonNull(reward, "reward must not be null");
        String raise = "UPDATE oneblock_rewards SET paid_cycle = ?, paid_at = CURRENT_TIMESTAMP"
                + " WHERE island_id = ? AND phase = ? AND reward = ? AND paid_cycle < ?";
        String insert = "INSERT INTO oneblock_rewards (island_id, phase, reward, paid_cycle) VALUES (?, ?, ?, ?)";
        String island = islandId.value().toString();
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(raise)) {
                ps.setLong(1, cycle);
                ps.setString(2, island);
                ps.setString(3, phase);
                ps.setString(4, reward);
                ps.setLong(5, cycle);
                if (ps.executeUpdate() == 1) {
                    return true;
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(insert)) {
                ps.setString(1, island);
                ps.setString(2, phase);
                ps.setString(3, reward);
                ps.setLong(4, cycle);
                ps.executeUpdate();
                return true;
            } catch (SQLException taken) {
                // The row is there, at this pass or a later one.
                if (SqlConstraint.violated(taken)) {
                    return false;
                }
                throw taken;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Could not record the OneBlock reward " + phase + "/" + reward + " of island " + islandId, e);
        }
    }

    @Override
    public void clear(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement("DELETE FROM oneblock_rewards WHERE island_id = ?")) {
            ps.setString(1, islandId.value().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not clear what OneBlock island " + islandId + " was paid", e);
        }
    }
}
