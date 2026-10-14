package com.uxplima.uxmskyblock.persistence.visit;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/** What every engine must keep of the fee each island charges a visitor. */
final class VisitFeeStorageScenario {

    private VisitFeeStorageScenario() {}

    static void run(Database database) throws SQLException {
        SqlVisitFeeAdapter fees = new SqlVisitFeeAdapter(database.dataSource());
        IslandId charging = island(database);
        IslandId other = island(database);

        assertThat(fees.feeOf(charging)).describedAs("no row charges nothing").isZero();
        fees.setFee(charging, 2_500);
        fees.setFee(other, 99);
        assertThat(fees.feeOf(charging)).isEqualTo(2_500);
        fees.setFee(charging, 7_000_000_000L);
        assertThat(fees.feeOf(charging))
                .describedAs("a fee set again replaces the one before")
                .isEqualTo(7_000_000_000L);
        assertThat(fees.feeOf(other)).describedAs("each island its own").isEqualTo(99);

        fees.setFee(charging, 0);
        assertThat(fees.feeOf(charging)).isZero();
        assertThat(rows(database, charging))
                .describedAs("a fee of nothing keeps no row")
                .isZero();
        fees.setFee(charging, 0);
        assertThat(fees.feeOf(other)).isEqualTo(99);

        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement("DELETE FROM islands WHERE id = ?")) {
            ps.setString(1, other.value().toString());
            ps.executeUpdate();
        }
        assertThat(rows(database, other))
                .describedAs("the fee goes with the island")
                .isZero();
    }

    private static int rows(Database database, IslandId island) throws SQLException {
        try (Connection conn = database.connection();
                PreparedStatement ps =
                        conn.prepareStatement("SELECT COUNT(*) FROM island_visit_fees WHERE island_id = ?")) {
            ps.setString(1, island.value().toString());
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static IslandId island(Database database) throws SQLException {
        IslandId island = IslandId.of(UUID.randomUUID());
        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO islands (id, owner_account_uuid, owner_profile_id, lifecycle, custom_name,"
                                + " created_at, updated_at) VALUES (?, ?, ?, 'ACTIVE', NULL, CURRENT_TIMESTAMP,"
                                + " CURRENT_TIMESTAMP)")) {
            ps.setString(1, island.value().toString());
            ps.setString(2, UUID.randomUUID().toString());
            ps.setString(3, UUID.randomUUID().toString());
            ps.executeUpdate();
        }
        return island;
    }
}
