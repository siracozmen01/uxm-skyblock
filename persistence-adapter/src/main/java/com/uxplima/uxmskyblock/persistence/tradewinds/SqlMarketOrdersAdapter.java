package com.uxplima.uxmskyblock.persistence.tradewinds;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmlib.storage.sql.Dialect;
import com.uxplima.uxmskyblock.core.application.tradewinds.MarketOrdersPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;

/**
 * The {@code tradewinds_orders} table, with the hold, the trade and the standing an order moves.
 *
 * <p>A hold is written only under the vessel's lease and over the version it was read at, in the same
 * transaction as the order it belongs to.
 */
public final class SqlMarketOrdersAdapter implements MarketOrdersPort {

    private static final String COLUMNS =
            "order_id, island_id, port_id, kind, item, item_count, amount, actor_uuid, state, attempts";
    private static final String OPEN = "('OWED', 'PAYING', 'REFUNDING')";

    private final Database database;
    private final VesselTransaction transaction;
    private final VesselLeaseGate lease;
    private final String lockOrder;

    public SqlMarketOrdersAdapter(Database database) {
        this.database = Objects.requireNonNull(database, "database");
        this.transaction = new VesselTransaction(database);
        this.lease = new VesselLeaseGate(database.dialect());
        this.lockOrder = "SELECT " + COLUMNS + " FROM tradewinds_orders WHERE order_id = ?"
                + (database.dialect() == Dialect.SQLITE ? "" : " FOR UPDATE");
    }

    @Override
    public boolean recordSale(Order order, HoldWrite hold) {
        Objects.requireNonNull(order, "order");
        Objects.requireNonNull(hold, "hold");
        return transaction.run("record sale " + order.id(), conn -> {
            if (!lease.holds(conn, order.vessel(), hold.node(), hold.leaseEpoch()) || !writeHold(conn, order, hold)) {
                return false;
            }
            insert(conn, order);
            grow(conn, order);
            return true;
        });
    }

    @Override
    public void recordPurchase(Order order) {
        Objects.requireNonNull(order, "order");
        transaction.run("record purchase " + order.id(), conn -> {
            insert(conn, order);
            return true;
        });
    }

    @Override
    public boolean deliver(UUID orderId, HoldWrite hold) {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(hold, "hold");
        return transaction.run("deliver order " + orderId, conn -> {
            Optional<Order> found = read(conn, lockOrder, orderId);
            if (found.isEmpty() || found.get().state() != State.PAYING) {
                return false;
            }
            Order order = found.get();
            if (!lease.holds(conn, order.vessel(), hold.node(), hold.leaseEpoch()) || !writeHold(conn, order, hold)) {
                return false;
            }
            try (PreparedStatement ps = conn.prepareStatement("UPDATE tradewinds_orders SET state = 'DONE', "
                    + "updated_at = CURRENT_TIMESTAMP WHERE order_id = ? AND state = 'PAYING'")) {
                ps.setString(1, orderId.toString());
                if (ps.executeUpdate() != 1) {
                    return false;
                }
            }
            grow(conn, order);
            return true;
        });
    }

    @Override
    public void attempt(UUID orderId, int attempt) {
        update(
                "UPDATE tradewinds_orders SET attempts = ?, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE order_id = ? AND attempts < ?",
                ps -> {
                    ps.setInt(1, attempt);
                    ps.setString(2, orderId.toString());
                    ps.setInt(3, attempt);
                });
    }

    @Override
    public boolean settle(UUID orderId, State from, State to) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        // A refund asks the bank under keys of its own, counted from the first.
        String attempts = to == State.REFUNDING ? "attempts = 0, " : "";
        return update(
                        "UPDATE tradewinds_orders SET state = ?, " + attempts
                                + "updated_at = CURRENT_TIMESTAMP WHERE order_id = ? AND state = ?",
                        ps -> {
                            ps.setString(1, to.name());
                            ps.setString(2, orderId.toString());
                            ps.setString(3, from.name());
                        })
                == 1;
    }

    @Override
    public Optional<Order> find(UUID orderId) {
        Objects.requireNonNull(orderId, "orderId");
        try (Connection conn = database.connection()) {
            return read(conn, "SELECT " + COLUMNS + " FROM tradewinds_orders WHERE order_id = ?", orderId);
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read order " + orderId, e);
        }
    }

    @Override
    public List<Order> open(IslandId vessel) {
        Objects.requireNonNull(vessel, "vessel");
        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement("SELECT " + COLUMNS + " FROM tradewinds_orders "
                        + "WHERE island_id = ? AND state IN " + OPEN + " ORDER BY created_at, order_id")) {
            ps.setString(1, vessel.value().toString());
            List<Order> open = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    open.add(order(rs));
                }
            }
            return List.copyOf(open);
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the open orders of vessel " + vessel, e);
        }
    }

    @Override
    public List<IslandId> vesselsWithOpenOrders() {
        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement(
                        "SELECT DISTINCT island_id FROM tradewinds_orders WHERE state IN " + OPEN);
                ResultSet rs = ps.executeQuery()) {
            List<IslandId> vessels = new ArrayList<>();
            while (rs.next()) {
                vessels.add(IslandId.of(UUID.fromString(rs.getString(1))));
            }
            return List.copyOf(vessels);
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the vessels with open orders", e);
        }
    }

    @Override
    public long standing(IslandId vessel, String portId) {
        return number(
                "SELECT standing FROM tradewinds_standing WHERE island_id = ? AND port_id = ?",
                vessel.value().toString(),
                portId);
    }

    @Override
    public long tradeVolume(IslandId vessel) {
        return number(
                "SELECT trade_volume FROM tradewinds_vessels WHERE island_id = ?",
                vessel.value().toString());
    }

    @Override
    public boolean setTradeVolume(IslandId vessel, long volume) {
        try (Connection conn = database.connection();
                PreparedStatement ps =
                        conn.prepareStatement("UPDATE tradewinds_vessels SET trade_volume = ? WHERE island_id = ?")) {
            ps.setLong(1, volume);
            ps.setString(2, vessel.value().toString());
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("Could not set the trade of vessel " + vessel, e);
        }
    }

    @Override
    public void setStanding(IslandId vessel, String portId, long standing) {
        String island = vessel.value().toString();
        // Two tries: a write that finds no row inserts one, and one that lost that race sets the row it lost to.
        for (int attempt = 0; attempt < 2; attempt++) {
            try (Connection conn = database.connection()) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE tradewinds_standing SET standing = ? WHERE island_id = ? AND port_id = ?")) {
                    ps.setLong(1, standing);
                    ps.setString(2, island);
                    ps.setString(3, portId);
                    if (ps.executeUpdate() == 1) {
                        return;
                    }
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO tradewinds_standing (island_id, port_id, standing) VALUES (?, ?, ?)")) {
                    ps.setString(1, island);
                    ps.setString(2, portId);
                    ps.setLong(3, standing);
                    ps.executeUpdate();
                    return;
                }
            } catch (SQLException e) {
                String state = e.getSQLState();
                boolean raced = (state != null && state.startsWith("23")) || (state == null && e.getErrorCode() == 19);
                if (!raced || attempt == 1) {
                    throw new IllegalStateException("Could not set the standing of vessel " + vessel, e);
                }
            }
        }
    }

    @Override
    public List<Traded> mostTraded(int limit) {
        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement("SELECT v.island_id, i.custom_name, v.trade_volume"
                        + " FROM tradewinds_vessels v JOIN islands i ON i.id = v.island_id"
                        + " WHERE v.trade_volume > 0 ORDER BY v.trade_volume DESC, v.island_id")) {
            ps.setMaxRows(Math.max(0, limit));
            List<Traded> most = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next() && most.size() < limit) {
                    String id = rs.getString(1);
                    String name = rs.getString(2);
                    // A vessel nobody named is shown by the start of its id, which reads the same in every language.
                    most.add(new Traded(
                            IslandId.of(UUID.fromString(id)),
                            name == null || name.isBlank() ? id.substring(0, 8) : name,
                            rs.getLong(3)));
                }
            }
            return List.copyOf(most);
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the vessels that traded most", e);
        }
    }

    /** Writes the hold over the version it was read at; says whether the version still stood. */
    private static boolean writeHold(Connection conn, Order order, HoldWrite hold) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE tradewinds_vessels SET cargo = ?, "
                + "cargo_version = cargo_version + 1, trade_volume = trade_volume + ? "
                + "WHERE island_id = ? AND cargo_version = ?")) {
            ps.setBytes(1, hold.cargoAfter());
            ps.setLong(2, order.amount());
            ps.setString(3, order.vessel().value().toString());
            ps.setLong(4, hold.cargoVersion());
            return ps.executeUpdate() == 1;
        }
    }

    private static void insert(Connection conn, Order order) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO tradewinds_orders (" + COLUMNS
                + ", created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, "
                + "CURRENT_TIMESTAMP)")) {
            ps.setString(1, order.id().toString());
            ps.setString(2, order.vessel().value().toString());
            ps.setString(3, order.portId());
            ps.setString(4, order.kind().name());
            ps.setString(5, order.item());
            ps.setInt(6, order.count());
            ps.setLong(7, order.amount());
            ps.setString(8, order.actor().value().toString());
            ps.setString(9, order.state().name());
            ps.setInt(10, order.attempts());
            ps.executeUpdate();
        }
    }

    /** Adds what {@code order} moved to the vessel's standing in its port. */
    private static void grow(Connection conn, Order order) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE tradewinds_standing SET standing = standing + ? " + "WHERE island_id = ? AND port_id = ?")) {
            ps.setLong(1, order.amount());
            ps.setString(2, order.vessel().value().toString());
            ps.setString(3, order.portId());
            if (ps.executeUpdate() == 1) {
                return;
            }
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO tradewinds_standing (island_id, port_id, standing) VALUES (?, ?, ?)")) {
            ps.setString(1, order.vessel().value().toString());
            ps.setString(2, order.portId());
            ps.setLong(3, order.amount());
            ps.executeUpdate();
        }
    }

    private static Optional<Order> read(Connection conn, String sql, UUID orderId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, orderId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(order(rs)) : Optional.empty();
            }
        }
    }

    private static Order order(ResultSet rs) throws SQLException {
        return new Order(
                UUID.fromString(rs.getString("order_id")),
                IslandId.of(UUID.fromString(rs.getString("island_id"))),
                rs.getString("port_id"),
                Kind.valueOf(rs.getString("kind")),
                rs.getString("item"),
                rs.getInt("item_count"),
                rs.getLong("amount"),
                PlayerUuid.of(UUID.fromString(rs.getString("actor_uuid"))),
                State.valueOf(rs.getString("state")),
                rs.getInt("attempts"));
    }

    @FunctionalInterface
    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private int update(String sql, Binder binder) {
        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            binder.bind(ps);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not update an order", e);
        }
    }

    private long number(String sql, String... keys) {
        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < keys.length; i++) {
                ps.setString(i + 1, keys[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read a vessel's trade", e);
        }
    }
}
