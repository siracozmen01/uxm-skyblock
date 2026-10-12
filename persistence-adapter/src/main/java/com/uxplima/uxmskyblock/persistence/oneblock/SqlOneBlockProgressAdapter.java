package com.uxplima.uxmskyblock.persistence.oneblock;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;

import javax.sql.DataSource;

import com.uxplima.uxmskyblock.core.application.oneblock.OneBlockProgressPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/** {@link OneBlockProgressPort} over the {@code oneblock_progress} table, on every supported database. */
public final class SqlOneBlockProgressAdapter implements OneBlockProgressPort {

    private final DataSource dataSource;

    public SqlOneBlockProgressAdapter(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
    }

    @Override
    public void start(IslandId islandId, int x, int y, int z) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        String sql = "INSERT INTO oneblock_progress (island_id, block_x, block_y, block_z, blocks_broken)"
                + " VALUES (?, ?, ?, ?, 0)";
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, islandId.value().toString());
            ps.setInt(2, x);
            ps.setInt(3, y);
            ps.setInt(4, z);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not make island " + islandId + " a OneBlock island", e);
        }
    }

    @Override
    public java.util.List<OneBlockIsland> findAll() {
        String sql = "SELECT island_id, block_x, block_y, block_z, blocks_broken FROM oneblock_progress";
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {
            java.util.List<OneBlockIsland> all = new java.util.ArrayList<>();
            while (rs.next()) {
                all.add(new OneBlockIsland(
                        IslandId.fromString(rs.getString(1)), rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getLong(5)));
            }
            return all;
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the OneBlock islands", e);
        }
    }

    @Override
    public Optional<OneBlockIsland> find(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        String sql = "SELECT block_x, block_y, block_z, blocks_broken FROM oneblock_progress WHERE island_id = ?";
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, islandId.value().toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(
                        new OneBlockIsland(islandId, rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getLong(4)));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the OneBlock island " + islandId, e);
        }
    }

    @Override
    public void addBreaks(IslandId islandId, long breaks) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        if (breaks < 0) {
            throw new IllegalArgumentException("Breaks cannot be taken back: " + breaks);
        }
        String sql = "UPDATE oneblock_progress SET blocks_broken = blocks_broken + ?, updated_at = CURRENT_TIMESTAMP"
                + " WHERE island_id = ?";
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, breaks);
            ps.setString(2, islandId.value().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not count the breaks of OneBlock island " + islandId, e);
        }
    }

    @Override
    public void setBreaks(IslandId islandId, long count) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        if (count < 0) {
            throw new IllegalArgumentException("A count of breaks cannot be negative: " + count);
        }
        String sql =
                "UPDATE oneblock_progress SET blocks_broken = ?, updated_at = CURRENT_TIMESTAMP WHERE island_id = ?";
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, count);
            ps.setString(2, islandId.value().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not set the count of OneBlock island " + islandId, e);
        }
    }
}
