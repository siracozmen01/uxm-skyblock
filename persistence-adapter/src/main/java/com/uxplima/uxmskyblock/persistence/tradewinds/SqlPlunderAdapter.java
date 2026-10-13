package com.uxplima.uxmskyblock.persistence.tradewinds;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;

import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.application.tradewinds.MarketOrdersPort;
import com.uxplima.uxmskyblock.core.application.tradewinds.PlunderPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/**
 * {@link PlunderPort} over the {@code tradewinds_plunders} table, on every supported database.
 *
 * <p>A plunder writes both holds under both leases and over the versions they were read at, and its row, in one
 * transaction. The two vessels' rows are written in the order of their keys, so two crews plundering each other at
 * once wait for one another rather than lock each other out.
 */
public final class SqlPlunderAdapter implements PlunderPort {

    private static final String RECENT = "SELECT 1 FROM tradewinds_plunders WHERE at_millis > ? "
            + "AND (raider_id = ? OR target_id = ? OR raider_id = ? OR target_id = ?)";

    private final Database database;
    private final VesselTransaction transaction;
    private final VesselLeaseGate lease;

    public SqlPlunderAdapter(Database database) {
        this.database = Objects.requireNonNull(database, "database");
        this.transaction = new VesselTransaction(database);
        this.lease = new VesselLeaseGate(database.dialect());
    }

    @Override
    public boolean recent(IslandId raider, IslandId target, Instant since) {
        try (Connection conn = database.connection()) {
            return recent(conn, raider, target, since);
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the plunders of " + raider + " and " + target, e);
        }
    }

    /** Whether either vessel took part in a plunder after {@code since}, on either side. */
    private static boolean recent(Connection conn, IslandId raider, IslandId target, Instant since)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(RECENT)) {
            ps.setLong(1, since.toEpochMilli());
            ps.setString(2, raider.value().toString());
            ps.setString(3, raider.value().toString());
            ps.setString(4, target.value().toString());
            ps.setString(5, target.value().toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    @Override
    public Outcome take(
            Plunder plunder,
            MarketOrdersPort.HoldWrite raiderHold,
            MarketOrdersPort.HoldWrite targetHold,
            Instant since) {
        Objects.requireNonNull(plunder, "plunder");
        Objects.requireNonNull(raiderHold, "raiderHold");
        Objects.requireNonNull(targetHold, "targetHold");
        Objects.requireNonNull(since, "since");
        Outcome[] outcome = {Outcome.MOVED};
        transaction.run("plunder " + plunder.target() + " for " + plunder.raider(), conn -> {
            boolean raiderFirst = plunder.raider()
                            .value()
                            .toString()
                            .compareTo(plunder.target().value().toString())
                    < 0;
            IslandId first = raiderFirst ? plunder.raider() : plunder.target();
            IslandId second = raiderFirst ? plunder.target() : plunder.raider();
            MarketOrdersPort.HoldWrite firstHold = raiderFirst ? raiderHold : targetHold;
            MarketOrdersPort.HoldWrite secondHold = raiderFirst ? targetHold : raiderHold;
            if (!lease.holds(conn, first, firstHold.node(), firstHold.leaseEpoch())
                    || !lease.holds(conn, second, secondHold.node(), secondHold.leaseEpoch())) {
                return false;
            }
            if (recent(conn, plunder.raider(), plunder.target(), since)) {
                outcome[0] = Outcome.TOO_SOON;
                return false;
            }
            if (!writeHold(conn, first, firstHold) || !writeHold(conn, second, secondHold)) {
                return false;
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO tradewinds_plunders (raider_id, target_id, at_millis, items) VALUES (?, ?, ?, ?)")) {
                ps.setString(1, plunder.raider().value().toString());
                ps.setString(2, plunder.target().value().toString());
                ps.setLong(3, plunder.at().toEpochMilli());
                ps.setInt(4, plunder.items());
                ps.executeUpdate();
            }
            outcome[0] = Outcome.TAKEN;
            return true;
        });
        return outcome[0];
    }

    private static boolean writeHold(Connection conn, IslandId vessel, MarketOrdersPort.HoldWrite hold)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE tradewinds_vessels SET cargo = ?, "
                + "cargo_version = cargo_version + 1 WHERE island_id = ? AND cargo_version = ?")) {
            ps.setBytes(1, hold.cargoAfter());
            ps.setString(2, vessel.value().toString());
            ps.setLong(3, hold.cargoVersion());
            return ps.executeUpdate() == 1;
        }
    }
}
