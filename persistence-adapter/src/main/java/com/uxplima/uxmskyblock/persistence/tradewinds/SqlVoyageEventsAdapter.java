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
import com.uxplima.uxmskyblock.core.application.tradewinds.MarketOrdersPort;
import com.uxplima.uxmskyblock.core.application.tradewinds.VoyageEventsPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/**
 * {@link VoyageEventsPort} over the {@code tradewinds_voyage_events} table, on every supported database.
 *
 * <p>An event is taken away by deleting its row as it was planned, so only one call ever takes it. A find writes the
 * hold under the vessel's lease and over the version it was read at, in the same transaction that takes it.
 */
public final class SqlVoyageEventsAdapter implements VoyageEventsPort {

    private static final String TAKE =
            "DELETE FROM tradewinds_voyage_events WHERE island_id = ? AND hazard = ? AND at_millis = ?";

    private final Database database;
    private final VesselTransaction transaction;
    private final VesselLeaseGate lease;

    public SqlVoyageEventsAdapter(Database database) {
        this.database = Objects.requireNonNull(database, "database");
        this.transaction = new VesselTransaction(database);
        this.lease = new VesselLeaseGate(database.dialect());
    }

    @Override
    public void plan(IslandId vessel, List<Planned> events) {
        Objects.requireNonNull(vessel, "vessel");
        List<Planned> planned = List.copyOf(events);
        transaction.run("plan the voyage of " + vessel, conn -> {
            try (PreparedStatement ps =
                    conn.prepareStatement("DELETE FROM tradewinds_voyage_events WHERE island_id = ?")) {
                ps.setString(1, vessel.value().toString());
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO tradewinds_voyage_events (island_id, hazard, port_id, at_millis) VALUES (?, ?, ?, ?)")) {
                for (Planned event : planned) {
                    if (!event.vessel().equals(vessel)) {
                        throw new IllegalArgumentException("an event of another vessel: " + event);
                    }
                    ps.setString(1, vessel.value().toString());
                    ps.setString(2, event.hazard().name());
                    ps.setString(3, event.portId());
                    ps.setLong(4, event.at().toEpochMilli());
                    ps.executeUpdate();
                }
            }
            return true;
        });
    }

    @Override
    public List<Planned> due(Instant now) {
        Objects.requireNonNull(now, "now");
        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement("SELECT island_id, hazard, port_id, at_millis "
                        + "FROM tradewinds_voyage_events WHERE at_millis <= ? ORDER BY at_millis")) {
            ps.setLong(1, now.toEpochMilli());
            try (ResultSet rs = ps.executeQuery()) {
                List<Planned> due = new ArrayList<>();
                while (rs.next()) {
                    due.add(new Planned(
                            IslandId.of(UUID.fromString(rs.getString(1))),
                            Hazard.valueOf(rs.getString(2)),
                            rs.getString(3),
                            Instant.ofEpochMilli(rs.getLong(4))));
                }
                return List.copyOf(due);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the voyage events due", e);
        }
    }

    @Override
    public boolean claim(Planned event) {
        Objects.requireNonNull(event, "event");
        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement(TAKE)) {
            bind(ps, event);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("Could not take the voyage event " + event, e);
        }
    }

    @Override
    public boolean stow(Planned find, MarketOrdersPort.HoldWrite hold) {
        Objects.requireNonNull(find, "find");
        Objects.requireNonNull(hold, "hold");
        return transaction.run("stow a find of " + find.vessel(), conn -> {
            if (!lease.holds(conn, find.vessel(), hold.node(), hold.leaseEpoch())) {
                return false;
            }
            try (PreparedStatement ps = conn.prepareStatement(TAKE)) {
                bind(ps, find);
                if (ps.executeUpdate() != 1) {
                    return false;
                }
            }
            try (PreparedStatement ps = conn.prepareStatement("UPDATE tradewinds_vessels SET cargo = ?, "
                    + "cargo_version = cargo_version + 1 WHERE island_id = ? AND cargo_version = ?")) {
                ps.setBytes(1, hold.cargoAfter());
                ps.setString(2, find.vessel().value().toString());
                ps.setLong(3, hold.cargoVersion());
                return ps.executeUpdate() == 1;
            }
        });
    }

    private static void bind(PreparedStatement ps, Planned event) throws SQLException {
        ps.setString(1, event.vessel().value().toString());
        ps.setString(2, event.hazard().name());
        ps.setLong(3, event.at().toEpochMilli());
    }
}
