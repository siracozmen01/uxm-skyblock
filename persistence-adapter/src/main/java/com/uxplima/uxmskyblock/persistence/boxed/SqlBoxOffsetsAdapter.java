package com.uxplima.uxmskyblock.persistence.boxed;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import javax.sql.DataSource;

import com.uxplima.uxmskyblock.core.application.boxed.BoxOffsetsPort;
import com.uxplima.uxmskyblock.core.domain.boxed.BoxOffset;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/** {@link BoxOffsetsPort} over the {@code boxed_offsets} table, on every supported database. */
public final class SqlBoxOffsetsAdapter implements BoxOffsetsPort {

    private final DataSource dataSource;

    public SqlBoxOffsetsAdapter(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
    }

    @Override
    public Map<IslandId, BoxOffset> findAll() {
        Map<IslandId, BoxOffset> all = new HashMap<>();
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement("SELECT island_id, dx, dz FROM boxed_offsets");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                all.put(IslandId.of(UUID.fromString(rs.getString(1))), new BoxOffset(rs.getInt(2), rs.getInt(3)));
            }
            return all;
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the moved Boxed boxes", e);
        }
    }

    @Override
    public void move(IslandId islandId, BoxOffset offset) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(offset, "offset must not be null");
        String island = islandId.value().toString();
        // Two tries: a move that finds no row inserts one, and one that lost that race writes over the row it lost to.
        for (int attempt = 0; attempt < 2; attempt++) {
            try (Connection conn = dataSource.getConnection()) {
                try (PreparedStatement ps =
                        conn.prepareStatement("UPDATE boxed_offsets SET dx = ?, dz = ? WHERE island_id = ?")) {
                    ps.setInt(1, offset.dx());
                    ps.setInt(2, offset.dz());
                    ps.setString(3, island);
                    if (ps.executeUpdate() == 1) {
                        return;
                    }
                }
                try (PreparedStatement ps =
                        conn.prepareStatement("INSERT INTO boxed_offsets (island_id, dx, dz) VALUES (?, ?, ?)")) {
                    ps.setString(1, island);
                    ps.setInt(2, offset.dx());
                    ps.setInt(3, offset.dz());
                    ps.executeUpdate();
                    return;
                }
            } catch (SQLException e) {
                String state = e.getSQLState();
                boolean raced = (state != null && state.startsWith("23")) || (state == null && e.getErrorCode() == 19);
                if (!raced || attempt == 1) {
                    throw new IllegalStateException("Could not move the box of island " + islandId, e);
                }
            }
        }
    }
}
