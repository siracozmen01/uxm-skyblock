package com.uxplima.uxmskyblock.persistence.tradewinds;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.application.tradewinds.MarketOrdersPort.HoldWrite;
import com.uxplima.uxmskyblock.core.application.tradewinds.PlunderPort.Outcome;
import com.uxplima.uxmskyblock.core.application.tradewinds.PlunderPort.Plunder;
import com.uxplima.uxmskyblock.core.application.tradewinds.VesselLease;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.island.IslandAuthorityOutcome;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import com.uxplima.uxmskyblock.persistence.island.RootAuthorityAdapter;

/** What every engine must do with the plunders at sea. */
final class PlunderStorageScenario {

    private static final ServerNodeId NODE = ServerNodeId.of("node-a");
    private static final ServerNodeId OTHER = ServerNodeId.of("node-b");
    private static final Instant NOW = Instant.parse("2026-10-13T12:00:00Z");

    private PlunderStorageScenario() {}

    static void run(Database database) throws SQLException {
        IslandId raider = IslandId.of(UUID.randomUUID());
        IslandId target = IslandId.of(UUID.randomUUID());
        IslandId third = IslandId.of(UUID.randomUUID());
        SqlVesselsAdapter vessels = new SqlVesselsAdapter(database.dataSource());
        RootAuthorityAdapter leases = new RootAuthorityAdapter(database);
        long[] epochs = new long[3];
        IslandId[] all = {raider, target, third};
        for (int at = 0; at < all.length; at++) {
            seedIsland(database, all[at]);
            vessels.add(all[at]);
            epochs[at] =
                    ((IslandAuthorityOutcome.Success) leases.acquire(VesselLease.rootOf(all[at]), NODE, 30)).epoch();
        }
        SqlPlunderAdapter plunders = new SqlPlunderAdapter(database);
        Plunder first = new Plunder(raider, target, NOW, 5);
        Instant since = NOW.minusSeconds(1800);

        assertThat(plunders.recent(raider, target, since)).isFalse();
        assertThat(plunders.take(
                        first,
                        new HoldWrite(epochs[0], OTHER, 1, new byte[] {1}),
                        new HoldWrite(epochs[1], NODE, 1, new byte[] {2}),
                        since))
                .describedAs("not under the raider's lease")
                .isEqualTo(Outcome.MOVED);
        assertThat(plunders.take(
                        first,
                        new HoldWrite(epochs[0], NODE, 1, new byte[] {1}),
                        new HoldWrite(epochs[1], OTHER, 1, new byte[] {2}),
                        since))
                .describedAs("nor under the target's")
                .isEqualTo(Outcome.MOVED);
        assertThat(plunders.take(
                        first,
                        new HoldWrite(epochs[0], NODE, 1, new byte[] {1}),
                        new HoldWrite(epochs[1], NODE, 2, new byte[] {2}),
                        since))
                .describedAs("over a target hold that moved")
                .isEqualTo(Outcome.MOVED);
        assertThat(vessels.cargo(raider).orElseThrow().version())
                .describedAs("the raider's hold written first was rolled back with the rest")
                .isEqualTo(1);
        assertThat(rows(database)).isZero();

        assertThat(plunders.take(
                        first,
                        new HoldWrite(epochs[0], NODE, 1, new byte[] {1}),
                        new HoldWrite(epochs[1], NODE, 1, new byte[] {2}),
                        since))
                .isEqualTo(Outcome.TAKEN);
        assertThat(vessels.cargo(raider).orElseThrow().items()).containsExactly(1);
        assertThat(vessels.cargo(target).orElseThrow().items()).containsExactly(2);
        assertThat(rows(database)).isEqualTo(1);

        assertThat(plunders.recent(raider, third, since))
                .describedAs("the raider plundered")
                .isTrue();
        assertThat(plunders.recent(third, target, since))
                .describedAs("the target was plundered")
                .isTrue();
        assertThat(plunders.recent(target, third, since))
                .describedAs("one plundered may not plunder in turn either")
                .isTrue();
        assertThat(plunders.recent(third, third, since)).isFalse();
        assertThat(plunders.recent(raider, target, NOW))
                .describedAs("only after since")
                .isFalse();
        assertThat(plunders.take(
                        new Plunder(third, target, NOW.plusSeconds(60), 1),
                        new HoldWrite(epochs[2], NODE, 1, new byte[] {3}),
                        new HoldWrite(epochs[1], NODE, 2, new byte[] {4}),
                        since))
                .isEqualTo(Outcome.TOO_SOON);
        assertThat(vessels.cargo(third).orElseThrow().version()).isEqualTo(1);
        assertThat(plunders.take(
                        new Plunder(target, raider, NOW.plusSeconds(3600), 2),
                        new HoldWrite(epochs[1], NODE, 2, new byte[] {5}),
                        new HoldWrite(epochs[0], NODE, 2, new byte[] {6}),
                        NOW.plusSeconds(1)))
                .describedAs("past the cooldown, the other way round")
                .isEqualTo(Outcome.TAKEN);
        assertThat(rows(database)).isEqualTo(2);
        assertThat(plunders.recent(third, target, NOW.plusSeconds(2)))
                .describedAs("the target plundered in its turn")
                .isTrue();

        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement("DELETE FROM islands WHERE id = ?")) {
            ps.setString(1, target.value().toString());
            ps.executeUpdate();
        }
        assertThat(rows(database))
                .describedAs("a vessel gone takes its plunders, either side")
                .isZero();
    }

    private static int rows(Database database) throws SQLException {
        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM tradewinds_plunders");
                ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static void seedIsland(Database database, IslandId island) throws SQLException {
        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO islands (id, owner_account_uuid, owner_profile_id, lifecycle, created_at, "
                                + "updated_at) VALUES (?, ?, ?, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)")) {
            ps.setString(1, island.value().toString());
            ps.setString(2, UUID.randomUUID().toString());
            ps.setString(3, UUID.randomUUID().toString());
            ps.executeUpdate();
        }
    }
}
