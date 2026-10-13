package com.uxplima.uxmskyblock.persistence.tradewinds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.application.tradewinds.MarketOrdersPort.HoldWrite;
import com.uxplima.uxmskyblock.core.application.tradewinds.VesselLease;
import com.uxplima.uxmskyblock.core.application.tradewinds.VoyageEventsPort.Hazard;
import com.uxplima.uxmskyblock.core.application.tradewinds.VoyageEventsPort.Planned;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.island.IslandAuthorityOutcome;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import com.uxplima.uxmskyblock.persistence.island.RootAuthorityAdapter;

/** What every engine must do with the events each TradeWinds voyage holds. */
final class VoyageEventsStorageScenario {

    private static final ServerNodeId NODE = ServerNodeId.of("node-a");
    private static final ServerNodeId OTHER = ServerNodeId.of("node-b");
    private static final Instant NOW = Instant.parse("2026-10-13T12:00:00Z");

    private VoyageEventsStorageScenario() {}

    static void run(Database database) throws SQLException {
        IslandId vessel = IslandId.of(UUID.randomUUID());
        IslandId other = IslandId.of(UUID.randomUUID());
        seedIsland(database, vessel);
        seedIsland(database, other);
        SqlVesselsAdapter vessels = new SqlVesselsAdapter(database.dataSource());
        vessels.add(vessel);
        vessels.add(other);
        long epoch = ((IslandAuthorityOutcome.Success)
                        new RootAuthorityAdapter(database).acquire(VesselLease.rootOf(vessel), NODE, 30))
                .epoch();
        SqlVoyageEventsAdapter events = new SqlVoyageEventsAdapter(database);

        Planned storm = new Planned(vessel, Hazard.STORM, "cove", NOW.plusSeconds(10));
        Planned pirates = new Planned(vessel, Hazard.PIRATES, "cove", NOW.plusSeconds(20));
        Planned find = new Planned(vessel, Hazard.FIND, "cove", NOW.plusSeconds(30));
        Planned theirs = new Planned(other, Hazard.STORM, "bay", NOW.plusSeconds(5));
        events.plan(vessel, List.of(storm, pirates, find));
        events.plan(other, List.of(theirs));
        assertThat(events.due(NOW.plusSeconds(4))).isEmpty();
        assertThat(events.due(NOW.plusSeconds(20)))
                .describedAs("the soonest first, every vessel's")
                .containsExactly(theirs, storm, pirates);
        assertThatThrownBy(() -> events.plan(vessel, List.of(theirs))).isInstanceOf(IllegalArgumentException.class);
        assertThat(events.due(NOW.plusSeconds(60)))
                .describedAs("a plan refused keeps the one before")
                .containsExactly(theirs, storm, pirates, find);

        assertThat(events.claim(new Planned(vessel, Hazard.STORM, "cove", NOW.plusSeconds(11))))
                .describedAs("an event of another moment is not this one")
                .isFalse();
        assertThat(events.claim(new Planned(vessel, Hazard.STORM, "cove", NOW.plusSeconds(9))))
                .describedAs("nor of an earlier one")
                .isFalse();
        assertThat(events.claim(storm)).isTrue();
        assertThat(events.claim(storm)).describedAs("once").isFalse();

        assertThat(events.stow(find, new HoldWrite(epoch, OTHER, 1, new byte[] {9})))
                .describedAs("not under the lease")
                .isFalse();
        assertThat(events.stow(find, new HoldWrite(epoch, NODE, 2, new byte[] {9})))
                .describedAs("over a hold that moved")
                .isFalse();
        assertThat(events.stow(find, new HoldWrite(epoch, NODE, 0, new byte[] {9})))
                .describedAs("over a hold read before it last moved")
                .isFalse();
        assertThat(events.due(NOW.plusSeconds(60))).contains(find);
        assertThat(vessels.cargo(vessel).orElseThrow().version()).isEqualTo(1);
        assertThat(events.stow(find, new HoldWrite(epoch, NODE, 1, new byte[] {9})))
                .isTrue();
        assertThat(vessels.cargo(vessel).orElseThrow().items()).containsExactly(9);
        assertThat(events.stow(find, new HoldWrite(epoch, NODE, 2, new byte[] {8})))
                .describedAs("a find is stowed once")
                .isFalse();
        assertThat(vessels.cargo(vessel).orElseThrow().items()).containsExactly(9);

        events.plan(vessel, List.of());
        assertThat(events.due(NOW.plusSeconds(60)))
                .describedAs("a new voyage with nothing in it clears the last")
                .containsExactly(theirs);

        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement("DELETE FROM islands WHERE id = ?")) {
            ps.setString(1, other.value().toString());
            ps.executeUpdate();
        }
        assertThat(events.due(NOW.plusSeconds(60)))
                .describedAs("a vessel gone takes its events")
                .isEmpty();
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
