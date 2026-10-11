package com.uxplima.uxmskyblock.persistence.recycle;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.uxplima.uxmlib.storage.StorageException;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.application.recycle.IslandFootprintPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.recycle.IslandFootprint;

/**
 * Where each island wrote blocks past its border, in {@code island_footprints}: one row per world and centre, as
 * wide as the widest square written there.
 */
public final class SqlIslandFootprintAdapter implements IslandFootprintPort {

    private final Database database;

    public SqlIslandFootprintAdapter(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    @Override
    public void record(IslandId islandId, IslandFootprint footprint) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(footprint, "footprint must not be null");
        String widen = """
                UPDATE island_footprints SET radius = ?
                WHERE island_id = ? AND world_name = ? AND center_x = ? AND center_z = ? AND radius < ?
                """;
        String held = """
                SELECT 1 FROM island_footprints
                WHERE island_id = ? AND world_name = ? AND center_x = ? AND center_z = ?
                """;
        String insert = """
                INSERT INTO island_footprints (island_id, world_name, center_x, center_z, radius)
                VALUES (?, ?, ?, ?, ?)
                """;
        try (Connection conn = database.connection()) {
            try (PreparedStatement ps = conn.prepareStatement(widen)) {
                ps.setInt(1, footprint.radius());
                key(ps, 2, islandId, footprint);
                ps.setInt(6, footprint.radius());
                if (ps.executeUpdate() > 0) {
                    return;
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(held)) {
                key(ps, 1, islandId, footprint);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        // As wide already, or wider.
                        return;
                    }
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(insert)) {
                key(ps, 1, islandId, footprint);
                ps.setInt(5, footprint.radius());
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new StorageException("Failed to record where island " + islandId + " wrote", e);
        }
    }

    private static void key(PreparedStatement ps, int from, IslandId islandId, IslandFootprint footprint)
            throws SQLException {
        ps.setString(from, islandId.value().toString());
        ps.setString(from + 1, footprint.world());
        ps.setInt(from + 2, footprint.centerX());
        ps.setInt(from + 3, footprint.centerZ());
    }

    @Override
    public List<IslandFootprint> of(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        String sql = "SELECT world_name, center_x, center_z, radius FROM island_footprints WHERE island_id = ?";
        List<IslandFootprint> footprints = new ArrayList<>();
        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, islandId.value().toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    footprints.add(new IslandFootprint(
                            rs.getString("world_name"),
                            rs.getInt("center_x"),
                            rs.getInt("center_z"),
                            rs.getInt("radius")));
                }
            }
        } catch (SQLException e) {
            throw new StorageException("Failed to read where island " + islandId + " wrote", e);
        }
        return List.copyOf(footprints);
    }

    @Override
    public void forget(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement("DELETE FROM island_footprints WHERE island_id = ?")) {
            ps.setString(1, islandId.value().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new StorageException("Failed to forget where island " + islandId + " wrote", e);
        }
    }
}
