package com.uxplima.uxmskyblock.persistence.oneblock;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.application.oneblock.OneBlockRewardPort.Paid;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/** What every engine must do with what a OneBlock island has been paid. */
final class OneBlockRewardStorageScenario {

    private OneBlockRewardStorageScenario() {}

    static void run(Database database) throws SQLException {
        IslandId island = IslandId.of(UUID.randomUUID());
        IslandId other = IslandId.of(UUID.randomUUID());
        seedIsland(database, island);
        seedIsland(database, other);
        SqlOneBlockRewardAdapter rewards = new SqlOneBlockRewardAdapter(database.dataSource());

        assertThat(rewards.pay(island, "plains", "moment:100", 0)).isTrue();
        assertThat(rewards.pay(island, "plains", "moment:100", 0))
                .describedAs("the same pass again")
                .isFalse();
        assertThat(rewards.pay(island, "plains", "finished", 0)).isTrue();
        assertThat(rewards.pay(other, "plains", "moment:100", 0)).isTrue();
        assertThat(rewards.pay(island, "plains", "moment:100", 2))
                .describedAs("a later pass")
                .isTrue();
        assertThat(rewards.pay(island, "plains", "moment:100", 1))
                .describedAs("a pass before the last one paid")
                .isFalse();

        assertThat(rewards.find(island))
                .containsExactlyInAnyOrder(
                        new Paid(island, "plains", "moment:100", 2), new Paid(island, "plains", "finished", 0));
        assertThat(rewards.findAll()).hasSize(3).contains(new Paid(other, "plains", "moment:100", 0));

        rewards.clear(island);
        assertThat(rewards.find(island)).isEmpty();
        assertThat(rewards.find(other)).hasSize(1);
        assertThat(rewards.pay(island, "plains", "moment:100", 0)).isTrue();

        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement("DELETE FROM islands WHERE id = ?")) {
            ps.setString(1, other.value().toString());
            ps.executeUpdate();
        }
        assertThat(rewards.find(other))
                .describedAs("a deleted island's rewards go with it")
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
