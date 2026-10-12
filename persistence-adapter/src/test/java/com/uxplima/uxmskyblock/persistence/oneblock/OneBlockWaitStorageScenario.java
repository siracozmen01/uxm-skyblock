package com.uxplima.uxmskyblock.persistence.oneblock;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.application.oneblock.OneBlockWaitPort.Waiting;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/** What every engine must do with the OneBlock islands that wait before a phase. */
final class OneBlockWaitStorageScenario {

    private static final Instant UNTIL = Instant.parse("2026-10-13T12:34:56.789Z");

    private OneBlockWaitStorageScenario() {}

    static void run(Database database) throws SQLException {
        IslandId island = IslandId.of(UUID.randomUUID());
        IslandId other = IslandId.of(UUID.randomUUID());
        seedIsland(database, island);
        seedIsland(database, other);
        SqlOneBlockWaitAdapter waits = new SqlOneBlockWaitAdapter(database.dataSource());

        assertThat(waits.find(island)).isEmpty();
        waits.set(new Waiting(island, "cave", UNTIL));
        assertThat(waits.find(island)).describedAs("to the millisecond").contains(new Waiting(island, "cave", UNTIL));
        waits.set(new Waiting(island, "nether", null));
        assertThat(waits.find(island))
                .describedAs("written over, waiting for requirements now")
                .contains(new Waiting(island, "nether", null));
        waits.set(new Waiting(other, "cave", UNTIL));
        assertThat(waits.findAll())
                .containsExactlyInAnyOrder(new Waiting(island, "nether", null), new Waiting(other, "cave", UNTIL));

        waits.clear(island);
        assertThat(waits.find(island)).isEmpty();
        assertThat(waits.find(other)).isPresent();

        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement("DELETE FROM islands WHERE id = ?")) {
            ps.setString(1, other.value().toString());
            ps.executeUpdate();
        }
        assertThat(waits.findAll())
                .describedAs("a deleted island's wait goes with it")
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
