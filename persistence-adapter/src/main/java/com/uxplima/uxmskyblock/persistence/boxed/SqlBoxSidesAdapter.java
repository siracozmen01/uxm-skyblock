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

import com.uxplima.uxmskyblock.core.application.boxed.BoxSidesPort;
import com.uxplima.uxmskyblock.core.domain.boxed.BoxSide;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/** {@link BoxSidesPort} over the {@code boxed_sides} table, on every supported database. */
public final class SqlBoxSidesAdapter implements BoxSidesPort {

    private final DataSource dataSource;

    public SqlBoxSidesAdapter(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
    }

    @Override
    public Map<IslandId, BoxSide> findAll() {
        Map<IslandId, BoxSide> all = new HashMap<>();
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement("SELECT island_id, side FROM boxed_sides");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                IslandId island = IslandId.of(UUID.fromString(rs.getString(1)));
                // A side no release knows is read as none, rather than stopping the others being read.
                BoxSide.named(rs.getString(2)).ifPresent(side -> all.put(island, side));
            }
            return all;
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the sides of the Boxed boxes", e);
        }
    }

    @Override
    public void choose(IslandId islandId, BoxSide side) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(side, "side must not be null");
        String island = islandId.value().toString();
        // Two tries: a choice that finds no row inserts one, and one that lost that race writes over the row it lost
        // to.
        for (int attempt = 0; attempt < 2; attempt++) {
            try (Connection conn = dataSource.getConnection()) {
                try (PreparedStatement ps =
                        conn.prepareStatement("UPDATE boxed_sides SET side = ? WHERE island_id = ?")) {
                    ps.setString(1, side.word());
                    ps.setString(2, island);
                    if (ps.executeUpdate() == 1) {
                        return;
                    }
                }
                try (PreparedStatement ps =
                        conn.prepareStatement("INSERT INTO boxed_sides (island_id, side) VALUES (?, ?)")) {
                    ps.setString(1, island);
                    ps.setString(2, side.word());
                    ps.executeUpdate();
                    return;
                }
            } catch (SQLException e) {
                String state = e.getSQLState();
                boolean raced = (state != null && state.startsWith("23")) || (state == null && e.getErrorCode() == 19);
                if (!raced || attempt == 1) {
                    throw new IllegalStateException("Could not keep the side of the box of island " + islandId, e);
                }
            }
        }
    }
}
