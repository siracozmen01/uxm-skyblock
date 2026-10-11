package com.uxplima.uxmskyblock.persistence.chunkblock;

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
import com.uxplima.uxmlib.storage.sql.Dialect;
import com.uxplima.uxmskyblock.core.application.chunkblock.ChunkIslandCharge;
import com.uxplima.uxmskyblock.core.application.chunkblock.ChunkPaymentPort;
import com.uxplima.uxmskyblock.core.domain.bank.BankTransactionOutcome;
import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkPayments;
import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkPos;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.persistence.bank.PlayerIslandBankAdapter;

/**
 * What has been paid toward each closed chunk, in {@code chunkblock_chunk_payments}, and each island's chunk points,
 * in {@code chunkblock_points}.
 *
 * <p>A payment adds to the row in the database, never to a total read first, so two members paying at once both
 * count. A row that is not there yet is inserted, and an insert another payment won is added to instead. A payment
 * out of the island's bank is written in the charge's own transaction.
 */
public final class SqlChunkPaymentAdapter implements ChunkPaymentPort {

    private final DataSource dataSource;
    private final Dialect dialect;
    private final PlayerIslandBankAdapter bank;

    public SqlChunkPaymentAdapter(Database database) {
        Objects.requireNonNull(database, "database must not be null");
        this.dataSource = database.dataSource();
        this.dialect = database.dialect();
        this.bank = new PlayerIslandBankAdapter(database);
    }

    @Override
    public Optional<BankTransactionOutcome> chargeIslandAndRecord(ChunkIslandCharge charge) {
        Objects.requireNonNull(charge, "charge must not be null");
        BankTransactionOutcome outcome = bank.executeTransactionWith(
                charge.islandId(),
                charge.actorUuid(),
                charge.currencyColumn(),
                charge.scale(),
                -charge.amount(),
                charge.reason(),
                charge.currentNode(),
                charge.expectedEpoch(),
                charge.expectedVersion(),
                charge.operationId(),
                charge.idempotencyKey(),
                "ISLAND_BANK",
                null,
                connection -> record(connection, charge));
        return Optional.ofNullable(outcome);
    }

    /**
     * Adds the charge to what was paid toward the chunk, on the charge's own connection. One statement that inserts
     * or adds, never an insert caught and retried: on PostgreSQL a failed statement ends the transaction, and this
     * one carries the bank charge.
     */
    private boolean record(Connection connection, ChunkIslandCharge charge) throws SQLException {
        String upsert =
                switch (dialect) {
                    case SQLITE, POSTGRES -> """
                    INSERT INTO chunkblock_chunk_payments (island_id, chunk_x, chunk_z, line_key, paid)
                    VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT (island_id, chunk_x, chunk_z, line_key)
                    DO UPDATE SET paid = chunkblock_chunk_payments.paid + excluded.paid
                    """;
                    case MYSQL -> """
                    INSERT INTO chunkblock_chunk_payments (island_id, chunk_x, chunk_z, line_key, paid)
                    VALUES (?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE paid = paid + VALUES(paid)
                    """;
                    case H2, GENERIC -> throw new UnsupportedOperationException("Unsupported dialect: " + dialect);
                };
        try (PreparedStatement ps = connection.prepareStatement(upsert)) {
            ps.setString(1, charge.islandId().value().toString());
            ps.setInt(2, charge.chunk().x());
            ps.setInt(3, charge.chunk().z());
            ps.setString(4, charge.lineKey());
            ps.setLong(5, charge.amount());
            ps.executeUpdate();
        }
        return true;
    }

    @Override
    public ChunkPayments find(IslandId islandId, ChunkPos chunk) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(chunk, "chunk must not be null");
        Map<String, Long> paid = new HashMap<>();
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement("SELECT line_key, paid FROM chunkblock_chunk_payments "
                        + "WHERE island_id = ? AND chunk_x = ? AND chunk_z = ?")) {
            ps.setString(1, islandId.value().toString());
            ps.setInt(2, chunk.x());
            ps.setInt(3, chunk.z());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    paid.put(rs.getString(1), rs.getLong(2));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read what was paid toward a chunk of island " + islandId, e);
        }
        return new ChunkPayments(paid);
    }

    @Override
    public void add(IslandId islandId, ChunkPos chunk, String key, long amount) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(chunk, "chunk must not be null");
        Objects.requireNonNull(key, "key must not be null");
        if (amount < 0) {
            throw new IllegalArgumentException("a payment is not below zero: " + amount);
        }
        String island = islandId.value().toString();
        String update = "UPDATE chunkblock_chunk_payments SET paid = paid + ? "
                + "WHERE island_id = ? AND chunk_x = ? AND chunk_z = ? AND line_key = ?";
        String insert = "INSERT INTO chunkblock_chunk_payments (island_id, chunk_x, chunk_z, line_key, paid) "
                + "VALUES (?, ?, ?, ?, ?)";
        try (Connection conn = dataSource.getConnection()) {
            for (int attempt = 0; attempt < 2; attempt++) {
                try (PreparedStatement ps = conn.prepareStatement(update)) {
                    ps.setLong(1, amount);
                    ps.setString(2, island);
                    ps.setInt(3, chunk.x());
                    ps.setInt(4, chunk.z());
                    ps.setString(5, key);
                    if (ps.executeUpdate() > 0) {
                        return;
                    }
                }
                try (PreparedStatement ps = conn.prepareStatement(insert)) {
                    ps.setString(1, island);
                    ps.setInt(2, chunk.x());
                    ps.setInt(3, chunk.z());
                    ps.setString(4, key);
                    ps.setLong(5, amount);
                    ps.executeUpdate();
                    return;
                } catch (SQLException taken) {
                    if (!SqlConstraint.violated(taken)) {
                        throw taken;
                    }
                    // Another payment inserted the row first: add to it.
                }
            }
            throw new IllegalStateException("A payment toward a chunk of island " + islandId + " found no row twice");
        } catch (SQLException e) {
            throw new IllegalStateException("Could not record a payment toward a chunk of island " + islandId, e);
        }
    }

    @Override
    public void clear(IslandId islandId, ChunkPos chunk) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(chunk, "chunk must not be null");
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement("DELETE FROM chunkblock_chunk_payments "
                        + "WHERE island_id = ? AND chunk_x = ? AND chunk_z = ?")) {
            ps.setString(1, islandId.value().toString());
            ps.setInt(2, chunk.x());
            ps.setInt(3, chunk.z());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not forget the payments toward a chunk of island " + islandId, e);
        }
    }

    @Override
    public long points(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps =
                        conn.prepareStatement("SELECT points FROM chunkblock_points WHERE island_id = ?")) {
            ps.setString(1, islandId.value().toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the chunk points of island " + islandId, e);
        }
    }

    @Override
    public void addPoints(IslandId islandId, long amount) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        if (amount < 0) {
            throw new IllegalArgumentException("points given are not below zero: " + amount);
        }
        String island = islandId.value().toString();
        try (Connection conn = dataSource.getConnection()) {
            for (int attempt = 0; attempt < 2; attempt++) {
                try (PreparedStatement ps =
                        conn.prepareStatement("UPDATE chunkblock_points SET points = points + ? WHERE island_id = ?")) {
                    ps.setLong(1, amount);
                    ps.setString(2, island);
                    if (ps.executeUpdate() > 0) {
                        return;
                    }
                }
                try (PreparedStatement ps =
                        conn.prepareStatement("INSERT INTO chunkblock_points (island_id, points) VALUES (?, ?)")) {
                    ps.setString(1, island);
                    ps.setLong(2, amount);
                    ps.executeUpdate();
                    return;
                } catch (SQLException taken) {
                    if (!SqlConstraint.violated(taken)) {
                        throw taken;
                    }
                }
            }
            throw new IllegalStateException("Chunk points for island " + islandId + " found no row twice");
        } catch (SQLException e) {
            throw new IllegalStateException("Could not give chunk points to island " + islandId, e);
        }
    }

    @Override
    public boolean spendPoints(IslandId islandId, long amount) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        if (amount < 0) {
            throw new IllegalArgumentException("points spent are not below zero: " + amount);
        }
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(
                        "UPDATE chunkblock_points SET points = points - ? WHERE island_id = ? AND points >= ?")) {
            ps.setLong(1, amount);
            ps.setString(2, islandId.value().toString());
            ps.setLong(3, amount);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Could not spend the chunk points of island " + islandId, e);
        }
    }
}
