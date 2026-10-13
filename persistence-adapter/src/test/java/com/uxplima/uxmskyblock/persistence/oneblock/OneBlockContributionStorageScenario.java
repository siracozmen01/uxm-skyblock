package com.uxplima.uxmskyblock.persistence.oneblock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.application.oneblock.OneBlockContributionPort.Contribution;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;

/** What every engine must do with the blocks each player broke on a OneBlock island. */
final class OneBlockContributionStorageScenario {

    private OneBlockContributionStorageScenario() {}

    static void run(Database database) throws SQLException {
        IslandId island = IslandId.of(UUID.randomUUID());
        IslandId other = IslandId.of(UUID.randomUUID());
        PlayerUuid ada = PlayerUuid.of(UUID.randomUUID());
        PlayerUuid bo = PlayerUuid.of(UUID.randomUUID());
        seedIsland(database, island);
        seedIsland(database, other);
        SqlOneBlockContributionAdapter breaks = new SqlOneBlockContributionAdapter(database.dataSource());

        assertThat(breaks.findAll()).isEmpty();
        breaks.add(island, ada, 3);
        breaks.add(island, ada, 4);
        breaks.add(island, bo, 1);
        breaks.add(other, ada, 2);
        assertThat(breaks.findAll())
                .describedAs("a player's breaks add up, each island and player apart")
                .containsExactlyInAnyOrder(
                        new Contribution(island, ada, 7),
                        new Contribution(island, bo, 1),
                        new Contribution(other, ada, 2));
        assertThatThrownBy(() -> breaks.add(island, ada, 0)).isInstanceOf(IllegalArgumentException.class);

        breaks.clear(island);
        assertThat(breaks.findAll()).containsExactly(new Contribution(other, ada, 2));

        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement("DELETE FROM islands WHERE id = ?")) {
            ps.setString(1, other.value().toString());
            ps.executeUpdate();
        }
        assertThat(breaks.findAll())
                .describedAs("a deleted island's breaks go with it")
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
