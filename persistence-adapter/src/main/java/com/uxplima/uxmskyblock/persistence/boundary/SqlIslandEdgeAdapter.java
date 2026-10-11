package com.uxplima.uxmskyblock.persistence.boundary;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import javax.sql.DataSource;

import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.application.boundary.IslandEdgePort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.persistence.chunkblock.SqlConstraint;

/** How each island chose to have its edge drawn, in {@code island_edges}. */
public final class SqlIslandEdgeAdapter implements IslandEdgePort {

    private final DataSource dataSource;

    public SqlIslandEdgeAdapter(Database database) {
        this.dataSource =
                Objects.requireNonNull(database, "database must not be null").dataSource();
    }

    @Override
    public Optional<String> find(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement("SELECT style FROM island_edges WHERE island_id = ?")) {
            ps.setString(1, islandId.value().toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read how island " + islandId + " draws its edge", e);
        }
    }

    @Override
    public Map<IslandId, String> findAll() {
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement("SELECT island_id, style FROM island_edges");
                ResultSet rs = ps.executeQuery()) {
            Map<IslandId, String> all = new HashMap<>();
            while (rs.next()) {
                all.put(IslandId.of(java.util.UUID.fromString(rs.getString(1))), rs.getString(2));
            }
            return Map.copyOf(all);
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read how the islands draw their edges", e);
        }
    }

    @Override
    public void keep(IslandId islandId, String style) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(style, "style must not be null");
        String island = islandId.value().toString();
        try (Connection conn = dataSource.getConnection()) {
            for (int attempt = 0; attempt < 2; attempt++) {
                try (PreparedStatement ps =
                        conn.prepareStatement("UPDATE island_edges SET style = ? WHERE island_id = ?")) {
                    ps.setString(1, style);
                    ps.setString(2, island);
                    if (ps.executeUpdate() > 0) {
                        return;
                    }
                }
                try (PreparedStatement ps =
                        conn.prepareStatement("INSERT INTO island_edges (island_id, style) VALUES (?, ?)")) {
                    ps.setString(1, island);
                    ps.setString(2, style);
                    ps.executeUpdate();
                    return;
                } catch (SQLException taken) {
                    if (!SqlConstraint.violated(taken)) {
                        throw taken;
                    }
                }
            }
            throw new IllegalStateException("The edge of island " + islandId + " found no row twice");
        } catch (SQLException e) {
            throw new IllegalStateException("Could not keep how island " + islandId + " draws its edge", e);
        }
    }
}
