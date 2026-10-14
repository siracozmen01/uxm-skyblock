package com.uxplima.uxmskyblock.persistence.visit;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;

import javax.sql.DataSource;

import com.uxplima.uxmskyblock.core.application.visit.VisitFeeStoragePort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.persistence.sql.UniqueViolations;

/** The visit fees in island_visit_fees, one row for each island that charges one. */
public final class SqlVisitFeeAdapter implements VisitFeeStoragePort {

    private final DataSource dataSource;

    public SqlVisitFeeAdapter(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
    }

    @Override
    public long feeOf(IslandId island) {
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps =
                        conn.prepareStatement("SELECT fee_minor FROM island_visit_fees WHERE island_id = ?")) {
            ps.setString(1, island.value().toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the visit fee of island " + island, e);
        }
    }

    @Override
    public void setFee(IslandId island, long minorUnits) {
        String id = island.value().toString();
        try (Connection conn = dataSource.getConnection()) {
            if (minorUnits <= 0) {
                try (PreparedStatement ps =
                        conn.prepareStatement("DELETE FROM island_visit_fees WHERE island_id = ?")) {
                    ps.setString(1, id);
                    ps.executeUpdate();
                }
                return;
            }
            // Two owners setting a fee at once both find no row; the one that loses the insert updates instead.
            if (update(conn, id, minorUnits) == 0) {
                try (PreparedStatement ps =
                        conn.prepareStatement("INSERT INTO island_visit_fees (island_id, fee_minor) VALUES (?, ?)")) {
                    ps.setString(1, id);
                    ps.setLong(2, minorUnits);
                    ps.executeUpdate();
                } catch (SQLException collided) {
                    if (!UniqueViolations.isUniqueViolation(collided)) {
                        throw collided;
                    }
                    update(conn, id, minorUnits);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not keep the visit fee of island " + island, e);
        }
    }

    private static int update(Connection conn, String island, long minorUnits) throws SQLException {
        try (PreparedStatement ps =
                conn.prepareStatement("UPDATE island_visit_fees SET fee_minor = ? WHERE island_id = ?")) {
            ps.setLong(1, minorUnits);
            ps.setString(2, island);
            return ps.executeUpdate();
        }
    }
}
