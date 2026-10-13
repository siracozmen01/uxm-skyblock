package com.uxplima.uxmskyblock.persistence.oneblock;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import javax.sql.DataSource;

import com.uxplima.uxmskyblock.core.application.oneblock.OneBlockWaitPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.persistence.chunkblock.SqlConstraint;

/** {@link OneBlockWaitPort} over the {@code oneblock_waits} table, on every supported database. */
public final class SqlOneBlockWaitAdapter implements OneBlockWaitPort {

    private final DataSource dataSource;

    public SqlOneBlockWaitAdapter(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
    }

    @Override
    public List<Waiting> findAll() {
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps =
                        conn.prepareStatement("SELECT island_id, phase, until_millis FROM oneblock_waits");
                ResultSet rs = ps.executeQuery()) {
            List<Waiting> all = new ArrayList<>();
            while (rs.next()) {
                all.add(read(rs));
            }
            return all;
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the waiting OneBlock islands", e);
        }
    }

    @Override
    public Optional<Waiting> find(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(
                        "SELECT island_id, phase, until_millis FROM oneblock_waits WHERE island_id = ?")) {
            ps.setString(1, islandId.value().toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(read(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the wait of OneBlock island " + islandId, e);
        }
    }

    private static Waiting read(ResultSet rs) throws SQLException {
        long millis = rs.getLong(3);
        Instant until = rs.wasNull() ? null : Instant.ofEpochMilli(millis);
        return new Waiting(IslandId.fromString(rs.getString(1)), rs.getString(2), until);
    }

    /** Changes the island's row, or writes it when there is none; of two writers at once the second changes it. */
    @Override
    public void set(Waiting waiting) {
        Objects.requireNonNull(waiting, "waiting must not be null");
        String island = waiting.islandId().value().toString();
        try (Connection conn = dataSource.getConnection()) {
            for (int attempt = 0; attempt < 2; attempt++) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE oneblock_waits SET phase = ?, until_millis = ? WHERE island_id = ?")) {
                    fill(ps, waiting, 1);
                    ps.setString(3, island);
                    if (ps.executeUpdate() == 1) {
                        return;
                    }
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO oneblock_waits (phase, until_millis, island_id) VALUES (?, ?, ?)")) {
                    fill(ps, waiting, 1);
                    ps.setString(3, island);
                    ps.executeUpdate();
                    return;
                } catch (SQLException taken) {
                    if (!SqlConstraint.violated(taken)) {
                        throw taken;
                    }
                    // Written between the two statements, or the island is gone: change it once more, then give up.
                }
            }
            throw new IllegalStateException(
                    "The wait of OneBlock island " + waiting.islandId() + " found no row twice");
        } catch (SQLException e) {
            throw new IllegalStateException("Could not record the wait of OneBlock island " + waiting.islandId(), e);
        }
    }

    private static void fill(PreparedStatement ps, Waiting waiting, int from) throws SQLException {
        ps.setString(from, waiting.phase());
        Instant until = waiting.until();
        if (until == null) {
            ps.setNull(from + 1, Types.BIGINT);
        } else {
            ps.setLong(from + 1, until.toEpochMilli());
        }
    }

    @Override
    public void clear(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement("DELETE FROM oneblock_waits WHERE island_id = ?")) {
            ps.setString(1, islandId.value().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not end the wait of OneBlock island " + islandId, e);
        }
    }
}
