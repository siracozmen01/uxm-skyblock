package com.uxplima.uxmskyblock.persistence.tradewinds;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.application.tradewinds.ContractsPort;
import com.uxplima.uxmskyblock.core.application.tradewinds.MarketOrdersPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.persistence.chunkblock.SqlConstraint;

/**
 * {@link ContractsPort} over the {@code tradewinds_contracts} table, on every supported database.
 *
 * <p>A delivery writes the hold only under the vessel's lease and over the version it was read at, in the same
 * transaction that marks the contract delivered.
 */
public final class SqlContractsAdapter implements ContractsPort {

    private static final String COLUMNS =
            "island_id, contract_id, to_port, item, item_count, taker_uuid, due_millis, state";

    private final Database database;
    private final VesselTransaction transaction;
    private final VesselLeaseGate lease;

    public SqlContractsAdapter(Database database) {
        this.database = Objects.requireNonNull(database, "database");
        this.transaction = new VesselTransaction(database);
        this.lease = new VesselLeaseGate(database.dialect());
    }

    @Override
    public List<Held> of(IslandId vessel) {
        Objects.requireNonNull(vessel, "vessel");
        return select(
                "SELECT " + COLUMNS + " FROM tradewinds_contracts WHERE island_id = ? ORDER BY contract_id",
                ps -> ps.setString(1, vessel.value().toString()),
                "the contracts of " + vessel);
    }

    @Override
    public List<Held> overdue(Instant now) {
        Objects.requireNonNull(now, "now");
        return select(
                "SELECT " + COLUMNS + " FROM tradewinds_contracts WHERE state = 'TAKEN' AND due_millis <= ?",
                ps -> ps.setLong(1, now.toEpochMilli()),
                "the contracts overdue");
    }

    /**
     * Turns a row that is no longer taken into this one, or adds a new row; a row still taken stands. Neither runs
     * in a transaction, so a refused insert leaves nothing to roll back.
     */
    @Override
    public boolean take(Held held) {
        Objects.requireNonNull(held, "held");
        try (Connection conn = database.connection()) {
            try (PreparedStatement ps = conn.prepareStatement("UPDATE tradewinds_contracts SET to_port = ?, "
                    + "item = ?, item_count = ?, taker_uuid = ?, due_millis = ?, state = 'TAKEN' "
                    + "WHERE island_id = ? AND contract_id = ? AND state <> 'TAKEN'")) {
                ps.setString(1, held.to());
                ps.setString(2, held.item());
                ps.setInt(3, held.count());
                ps.setString(4, held.taker().value().toString());
                ps.setLong(5, held.due().toEpochMilli());
                ps.setString(6, held.vessel().value().toString());
                ps.setString(7, held.contractId());
                if (ps.executeUpdate() == 1) {
                    return true;
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO tradewinds_contracts (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, 'TAKEN')")) {
                ps.setString(1, held.vessel().value().toString());
                ps.setString(2, held.contractId());
                ps.setString(3, held.to());
                ps.setString(4, held.item());
                ps.setInt(5, held.count());
                ps.setString(6, held.taker().value().toString());
                ps.setLong(7, held.due().toEpochMilli());
                ps.executeUpdate();
                return true;
            } catch (SQLException refused) {
                // Taken already, by a call that came first, or a vessel that is gone: once, never again.
                if (SqlConstraint.violated(refused)) {
                    return false;
                }
                throw refused;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not take contract " + held.contractId(), e);
        }
    }

    @Override
    public boolean deliver(IslandId vessel, String contractId, Instant now, MarketOrdersPort.HoldWrite hold) {
        Objects.requireNonNull(vessel, "vessel");
        Objects.requireNonNull(contractId, "contractId");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(hold, "hold");
        return transaction.run("deliver contract " + contractId, conn -> {
            if (!lease.holds(conn, vessel, hold.node(), hold.leaseEpoch())) {
                return false;
            }
            try (PreparedStatement ps = conn.prepareStatement("UPDATE tradewinds_contracts SET state = 'DELIVERED' "
                    + "WHERE island_id = ? AND contract_id = ? AND state = 'TAKEN' AND due_millis > ?")) {
                ps.setString(1, vessel.value().toString());
                ps.setString(2, contractId);
                ps.setLong(3, now.toEpochMilli());
                if (ps.executeUpdate() != 1) {
                    return false;
                }
            }
            try (PreparedStatement ps = conn.prepareStatement("UPDATE tradewinds_vessels SET cargo = ?, "
                    + "cargo_version = cargo_version + 1 WHERE island_id = ? AND cargo_version = ?")) {
                ps.setBytes(1, hold.cargoAfter());
                ps.setString(2, vessel.value().toString());
                ps.setLong(3, hold.cargoVersion());
                return ps.executeUpdate() == 1;
            }
        });
    }

    @Override
    public boolean fail(IslandId vessel, String contractId) {
        Objects.requireNonNull(vessel, "vessel");
        Objects.requireNonNull(contractId, "contractId");
        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement("UPDATE tradewinds_contracts SET state = 'FAILED' "
                        + "WHERE island_id = ? AND contract_id = ? AND state = 'TAKEN'")) {
            ps.setString(1, vessel.value().toString());
            ps.setString(2, contractId);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("Could not fail contract " + contractId, e);
        }
    }

    @FunctionalInterface
    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private List<Held> select(String sql, Binder binder, String what) {
        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                List<Held> found = new ArrayList<>();
                while (rs.next()) {
                    found.add(new Held(
                            IslandId.of(UUID.fromString(rs.getString(1))),
                            rs.getString(2),
                            rs.getString(3),
                            rs.getString(4),
                            rs.getInt(5),
                            PlayerUuid.of(UUID.fromString(rs.getString(6))),
                            Instant.ofEpochMilli(rs.getLong(7)),
                            State.valueOf(rs.getString(8))));
                }
                return List.copyOf(found);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read " + what, e);
        }
    }
}
