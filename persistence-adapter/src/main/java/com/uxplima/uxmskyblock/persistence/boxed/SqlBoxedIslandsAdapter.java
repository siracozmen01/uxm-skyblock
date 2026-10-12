package com.uxplima.uxmskyblock.persistence.boxed;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.UUID;

import javax.sql.DataSource;

import com.uxplima.uxmskyblock.core.application.boxed.BoxedIslandsPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/** The {@code boxed_instance_state} and {@code boxed_advancements} tables. */
public final class SqlBoxedIslandsAdapter implements BoxedIslandsPort {

    private static final String EARNED = "SELECT s.island_id, COALESCE(SUM(a.blocks), 0) FROM boxed_instance_state s"
            + " LEFT JOIN boxed_advancements a ON a.island_id = s.island_id";

    private final DataSource dataSource;

    public SqlBoxedIslandsAdapter(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
    }

    @Override
    public Map<IslandId, Long> findAll() {
        Map<IslandId, Long> all = new HashMap<>();
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(EARNED + " GROUP BY s.island_id");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                all.put(IslandId.of(UUID.fromString(rs.getString(1))), rs.getLong(2));
            }
            return all;
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the Boxed islands", e);
        }
    }

    @Override
    public OptionalLong find(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(EARNED + " WHERE s.island_id = ? GROUP BY s.island_id")) {
            ps.setString(1, islandId.value().toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? OptionalLong.of(rs.getLong(2)) : OptionalLong.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read island " + islandId + " as a Boxed island", e);
        }
    }

    @Override
    public void add(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        insert("INSERT INTO boxed_instance_state (island_id) VALUES (?)", islandId, null, 0);
    }

    @Override
    public boolean earn(IslandId islandId, String advancement, int blocks) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(advancement, "advancement must not be null");
        return insert(
                "INSERT INTO boxed_advancements (island_id, advancement, blocks) VALUES (?, ?, ?)",
                islandId,
                advancement,
                blocks);
    }

    @Override
    public void grant(IslandId islandId, long blocks) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        String island = islandId.value().toString();
        // Two tries: a grant that finds no row inserts one, and one that lost that race adds to the row it lost to.
        for (int attempt = 0; attempt < 2; attempt++) {
            try (Connection conn = dataSource.getConnection()) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE boxed_advancements SET blocks = blocks + ? WHERE island_id = ? AND advancement = ?")) {
                    ps.setLong(1, blocks);
                    ps.setString(2, island);
                    ps.setString(3, OPERATOR);
                    if (ps.executeUpdate() == 1) {
                        return;
                    }
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO boxed_advancements (island_id, advancement, blocks) VALUES (?, ?, ?)")) {
                    ps.setString(1, island);
                    ps.setString(2, OPERATOR);
                    ps.setLong(3, blocks);
                    ps.executeUpdate();
                    return;
                }
            } catch (SQLException e) {
                String state = e.getSQLState();
                boolean raced = (state != null && state.startsWith("23")) || (state == null && e.getErrorCode() == 19);
                if (!raced || attempt == 1) {
                    throw new IllegalStateException("Could not change the box of island " + islandId, e);
                }
            }
        }
    }

    /** Inserts a row, and says false when it was there already. */
    private boolean insert(
            String sql, IslandId islandId, @org.jspecify.annotations.Nullable String advancement, int blocks) {
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, islandId.value().toString());
            if (advancement != null) {
                ps.setString(2, advancement);
                ps.setInt(3, blocks);
            }
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            // Already there. Class 23, or SQLite's code 19.
            String state = e.getSQLState();
            if ((state != null && state.startsWith("23")) || (state == null && e.getErrorCode() == 19)) {
                return false;
            }
            throw new IllegalStateException("Could not record island " + islandId + " as a Boxed island", e);
        }
    }
}
