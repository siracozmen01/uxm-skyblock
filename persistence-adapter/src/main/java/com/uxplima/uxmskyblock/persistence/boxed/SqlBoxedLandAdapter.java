package com.uxplima.uxmskyblock.persistence.boxed;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.OptionalInt;

import javax.sql.DataSource;

import com.uxplima.uxmskyblock.core.application.boxed.BoxedLandPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/** {@link BoxedLandPort} over the {@code boxed_land} table, on every supported database. */
public final class SqlBoxedLandAdapter implements BoxedLandPort {

    private final DataSource dataSource;

    public SqlBoxedLandAdapter(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
    }

    @Override
    public void begin(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps =
                        conn.prepareStatement("INSERT INTO boxed_land (island_id, rings) VALUES (?, -1)")) {
            ps.setString(1, islandId.value().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            // Already there. Class 23, or SQLite's code 19.
            String state = e.getSQLState();
            if ((state != null && state.startsWith("23")) || (state == null && e.getErrorCode() == 19)) {
                return;
            }
            throw new IllegalStateException("Could not put island " + islandId + " on the template's land", e);
        }
    }

    @Override
    public OptionalInt rings(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement("SELECT rings FROM boxed_land WHERE island_id = ?")) {
            ps.setString(1, islandId.value().toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? OptionalInt.of(rs.getInt(1)) : OptionalInt.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the land of island " + islandId, e);
        }
    }

    @Override
    public boolean copied(IslandId islandId, int from, int to) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps =
                        conn.prepareStatement("UPDATE boxed_land SET rings = ? WHERE island_id = ? AND rings = ?")) {
            ps.setInt(1, to);
            ps.setString(2, islandId.value().toString());
            ps.setInt(3, from);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("Could not record the land of island " + islandId, e);
        }
    }
}
