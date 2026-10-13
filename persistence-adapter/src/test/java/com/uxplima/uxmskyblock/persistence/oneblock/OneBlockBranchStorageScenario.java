package com.uxplima.uxmskyblock.persistence.oneblock;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.application.oneblock.OneBlockBranchPort.Pick;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/** What every engine must do with the phases OneBlock islands picked to follow a branch. */
final class OneBlockBranchStorageScenario {

    private OneBlockBranchStorageScenario() {}

    static void run(Database database) throws SQLException {
        IslandId island = IslandId.of(UUID.randomUUID());
        IslandId other = IslandId.of(UUID.randomUUID());
        seedIsland(database, island);
        seedIsland(database, other);
        SqlOneBlockBranchAdapter branches = new SqlOneBlockBranchAdapter(database.dataSource());

        assertThat(branches.find(island)).isEmpty();
        branches.pick(new Pick(island, "plains", "cave"));
        branches.pick(new Pick(island, "plains", "forest"));
        branches.pick(new Pick(island, "nether", "end"));
        branches.pick(new Pick(other, "plains", "cave"));
        assertThat(branches.find(island))
                .describedAs("a pick made again is written over")
                .containsExactlyInAnyOrder(new Pick(island, "plains", "forest"), new Pick(island, "nether", "end"));
        assertThat(branches.findAll()).hasSize(3);

        branches.clear(island);
        assertThat(branches.find(island)).isEmpty();
        assertThat(branches.find(other)).containsExactly(new Pick(other, "plains", "cave"));

        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement("DELETE FROM islands WHERE id = ?")) {
            ps.setString(1, other.value().toString());
            ps.executeUpdate();
        }
        assertThat(branches.findAll())
                .describedAs("a deleted island's picks go with it")
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
