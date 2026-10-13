package com.uxplima.uxmskyblock.persistence.session;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;

import javax.sql.DataSource;

import com.uxplima.uxmskyblock.core.application.session.NetworkPresencePort;

/**
 * Counts the players on the network from {@code player_sessions}: a session that is active, or on its way from one
 * server to another, whose lease has not run out. A server that stopped without letting go leaves sessions whose
 * leases run out, so they stop counting on their own.
 */
public final class SqlNetworkPresenceAdapter implements NetworkPresencePort {

    private final DataSource dataSource;

    public SqlNetworkPresenceAdapter(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
    }

    @Override
    public int online() {
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM player_sessions"
                        + " WHERE state IN ('ACTIVE', 'DRAINING', 'HANDOFF_READY', 'RECOVERING')"
                        + " AND lease_expires_at >= CURRENT_TIMESTAMP");
                ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Could not count the players on the network", e);
        }
    }
}
