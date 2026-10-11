package com.uxplima.uxmskyblock.persistence.boundary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/** What every engine must do with how each island draws its edge. */
final class IslandEdgeStorageScenario {

    private IslandEdgeStorageScenario() {}

    static void run(Database database) throws SQLException {
        IslandId island = IslandId.of(UUID.randomUUID());
        IslandId other = IslandId.of(UUID.randomUUID());
        seedIsland(database, island);
        seedIsland(database, other);
        SqlIslandEdgeAdapter edges = new SqlIslandEdgeAdapter(database);

        assertThat(edges.find(island))
                .describedAs("an island that never chose has no row")
                .isEmpty();
        assertThat(edges.findAll()).isEmpty();

        edges.keep(island, "mist");
        assertThat(edges.find(island)).contains("mist");
        edges.keep(island, "glass");
        assertThat(edges.find(island))
                .describedAs("a new choice replaces the old")
                .contains("glass");
        edges.keep(other, "border");
        assertThat(edges.findAll()).containsOnlyKeys(island, other).containsEntry(island, "glass");

        assertThatThrownBy(() -> edges.keep(IslandId.of(UUID.randomUUID()), "mist"))
                .describedAs("no island, no choice")
                .isInstanceOf(IllegalStateException.class);

        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement("DELETE FROM islands WHERE id = ?")) {
            ps.setString(1, island.value().toString());
            ps.executeUpdate();
        }
        assertThat(edges.find(island)).describedAs("it goes with its island").isEmpty();
        assertThat(edges.find(other)).contains("border");
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
