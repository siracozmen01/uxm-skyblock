package com.uxplima.uxmskyblock.persistence.tradewinds;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import javax.sql.DataSource;

import com.uxplima.uxmskyblock.core.application.tradewinds.MarketPricesPort;
import com.uxplima.uxmskyblock.persistence.chunkblock.SqlConstraint;

/** {@link MarketPricesPort} over the {@code tradewinds_prices} table, on every supported database. */
public final class SqlMarketPricesAdapter implements MarketPricesPort {

    private static final String COLUMNS = "port_id, item, pressure_milli, at_millis, version";

    private final DataSource dataSource;

    public SqlMarketPricesAdapter(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
    }

    @Override
    public Optional<Pressure> find(String portId, String item) {
        Objects.requireNonNull(portId, "portId must not be null");
        Objects.requireNonNull(item, "item must not be null");
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(
                        "SELECT " + COLUMNS + " FROM tradewinds_prices WHERE port_id = ? AND item = ?")) {
            ps.setString(1, portId);
            ps.setString(2, item);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(read(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the price of " + item + " at " + portId, e);
        }
    }

    @Override
    public List<Pressure> findAll() {
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement("SELECT " + COLUMNS + " FROM tradewinds_prices");
                ResultSet rs = ps.executeQuery()) {
            List<Pressure> all = new ArrayList<>();
            while (rs.next()) {
                all.add(read(rs));
            }
            return all;
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the TradeWinds prices", e);
        }
    }

    private static Pressure read(ResultSet rs) throws SQLException {
        return new Pressure(
                rs.getString(1),
                rs.getString(2),
                rs.getLong(3) / 1000.0,
                Instant.ofEpochMilli(rs.getLong(4)),
                rs.getLong(5));
    }

    /** A new row at version 1, or the row at the version before, changed; neither when another write came first. */
    @Override
    public boolean keep(Pressure next) {
        Objects.requireNonNull(next, "next must not be null");
        long pressure = Math.round(next.pressure() * 1000);
        try (Connection conn = dataSource.getConnection()) {
            if (next.version() == 1) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO tradewinds_prices (" + COLUMNS + ") VALUES (?, ?, ?, ?, 1)")) {
                    ps.setString(1, next.portId());
                    ps.setString(2, next.item());
                    ps.setLong(3, pressure);
                    ps.setLong(4, next.at().toEpochMilli());
                    ps.executeUpdate();
                    return true;
                } catch (SQLException taken) {
                    if (SqlConstraint.violated(taken)) {
                        return false;
                    }
                    throw taken;
                }
            }
            try (PreparedStatement ps = conn.prepareStatement("UPDATE tradewinds_prices SET pressure_milli = ?, "
                    + "at_millis = ?, version = ? WHERE port_id = ? AND item = ? AND version = ?")) {
                ps.setLong(1, pressure);
                ps.setLong(2, next.at().toEpochMilli());
                ps.setLong(3, next.version());
                ps.setString(4, next.portId());
                ps.setString(5, next.item());
                ps.setLong(6, next.version() - 1);
                return ps.executeUpdate() == 1;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not write the price of " + next.item() + " at " + next.portId(), e);
        }
    }
}
