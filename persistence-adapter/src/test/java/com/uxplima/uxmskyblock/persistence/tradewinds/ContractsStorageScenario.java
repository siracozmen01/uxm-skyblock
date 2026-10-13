package com.uxplima.uxmskyblock.persistence.tradewinds;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.application.tradewinds.ContractsPort.Held;
import com.uxplima.uxmskyblock.core.application.tradewinds.ContractsPort.State;
import com.uxplima.uxmskyblock.core.application.tradewinds.MarketOrdersPort.HoldWrite;
import com.uxplima.uxmskyblock.core.application.tradewinds.VesselLease;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.island.IslandAuthorityOutcome;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import com.uxplima.uxmskyblock.persistence.island.RootAuthorityAdapter;

/** What every engine must do with the contracts each TradeWinds vessel took. */
final class ContractsStorageScenario {

    private static final ServerNodeId NODE = ServerNodeId.of("node-a");
    private static final ServerNodeId OTHER = ServerNodeId.of("node-b");
    private static final Instant NOW = Instant.parse("2026-10-13T12:00:00Z");

    private ContractsStorageScenario() {}

    static void run(Database database) throws SQLException {
        IslandId vessel = IslandId.of(UUID.randomUUID());
        IslandId other = IslandId.of(UUID.randomUUID());
        PlayerUuid ada = PlayerUuid.of(UUID.randomUUID());
        seedIsland(database, vessel);
        seedIsland(database, other);
        SqlVesselsAdapter vessels = new SqlVesselsAdapter(database.dataSource());
        vessels.add(vessel);
        vessels.add(other);
        long epoch = ((IslandAuthorityOutcome.Success)
                        new RootAuthorityAdapter(database).acquire(VesselLease.rootOf(vessel), NODE, 30))
                .epoch();
        SqlContractsAdapter contracts = new SqlContractsAdapter(database);

        Held grain = new Held(vessel, "grain", "cove", "WHEAT", 32, ada, NOW.plusSeconds(600), State.TAKEN);
        Held salt = new Held(vessel, "salt", "cove", "SUGAR", 8, ada, NOW.plusSeconds(60), State.TAKEN);
        Held iron = new Held(other, "iron", "bay", "IRON_INGOT", 4, ada, NOW.plusSeconds(30), State.TAKEN);
        assertThat(contracts.of(vessel)).isEmpty();
        assertThat(contracts.take(grain)).isTrue();
        assertThat(contracts.take(grain))
                .describedAs("a contract still taken is not taken twice")
                .isFalse();
        assertThat(contracts.take(salt)).isTrue();
        assertThat(contracts.take(iron)).isTrue();
        assertThat(contracts.of(vessel)).containsExactly(grain, salt);
        assertThat(contracts.overdue(NOW.plusSeconds(29))).isEmpty();
        assertThat(contracts.overdue(NOW.plusSeconds(60))).containsExactlyInAnyOrder(salt, iron);

        assertThat(contracts.deliver(vessel, "grain", NOW, new HoldWrite(epoch, OTHER, 1, new byte[] {7})))
                .describedAs("not under the lease")
                .isFalse();
        assertThat(contracts.deliver(vessel, "grain", NOW, new HoldWrite(epoch, NODE, 2, new byte[] {7})))
                .describedAs("over a hold that moved")
                .isFalse();
        assertThat(contracts.deliver(vessel, "grain", NOW, new HoldWrite(epoch, NODE, 0, new byte[] {7})))
                .describedAs("over a hold read before it last moved")
                .isFalse();
        assertThat(contracts.deliver(
                        vessel, "grain", NOW.plusSeconds(600), new HoldWrite(epoch, NODE, 1, new byte[] {7})))
                .describedAs("late")
                .isFalse();
        assertThat(contracts.of(vessel)).containsExactly(grain, salt);
        assertThat(vessels.cargo(vessel).orElseThrow().version()).isEqualTo(1);

        assertThat(contracts.deliver(vessel, "grain", NOW, new HoldWrite(epoch, NODE, 1, new byte[] {7})))
                .isTrue();
        assertThat(vessels.cargo(vessel).orElseThrow().items()).containsExactly(7);
        assertThat(vessels.cargo(vessel).orElseThrow().version()).isEqualTo(2);
        assertThat(contracts.deliver(vessel, "grain", NOW, new HoldWrite(epoch, NODE, 2, new byte[] {8})))
                .describedAs("delivered once")
                .isFalse();
        assertThat(vessels.cargo(vessel).orElseThrow().items()).containsExactly(7);

        assertThat(contracts.fail(vessel, "salt")).isTrue();
        assertThat(contracts.fail(vessel, "salt")).describedAs("failed once").isFalse();
        assertThat(contracts.fail(vessel, "grain"))
                .describedAs("a delivered one stands")
                .isFalse();
        assertThat(contracts.of(vessel)).extracting(Held::state).containsExactly(State.DELIVERED, State.FAILED);
        assertThat(contracts.overdue(NOW.plusSeconds(3600))).containsExactly(iron);

        Held again = new Held(vessel, "salt", "bay", "SUGAR", 16, ada, NOW.plusSeconds(900), State.TAKEN);
        assertThat(contracts.take(again))
                .describedAs("a failed contract is taken again, on its new terms")
                .isTrue();
        assertThat(contracts.of(vessel)).contains(again);

        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement("DELETE FROM islands WHERE id = ?")) {
            ps.setString(1, other.value().toString());
            ps.executeUpdate();
        }
        assertThat(contracts.of(other))
                .describedAs("a vessel that is gone takes its contracts")
                .isEmpty();
        assertThat(contracts.take(iron))
                .describedAs("a contract of a vessel gone meanwhile is refused, not tried for ever")
                .isFalse();
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
