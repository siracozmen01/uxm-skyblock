package com.uxplima.uxmskyblock.persistence.parkour;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.application.parkour.ParkourPort.Best;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;

/** What every engine must keep of each season's times on a Parkour course. */
final class ParkourSeasonsStorageScenario {

    private ParkourSeasonsStorageScenario() {}

    static void run(Database database) throws SQLException {
        SqlParkourAdapter parkour = new SqlParkourAdapter(database.dataSource());
        SqlParkourSeasonsAdapter seasons = new SqlParkourSeasonsAdapter(database.dataSource());
        IslandId course = island(database);
        IslandId other = island(database);
        parkour.add(course);
        parkour.add(other);
        PlayerUuid a = PlayerUuid.of(UUID.fromString("00000000-0000-0000-0000-00000000000a"));
        PlayerUuid b = PlayerUuid.of(UUID.fromString("00000000-0000-0000-0000-00000000000b"));

        seasons.finish(course, 1, a, 30_000);
        seasons.finish(course, 1, a, 35_000);
        seasons.finish(course, 1, a, 25_000);
        seasons.finish(course, 1, b, 28_000);
        seasons.finish(course, 2, b, 40_000);
        seasons.finish(other, 2, a, 10_000);

        assertThat(seasons.top(course, 1, 10))
                .describedAs("the faster time kept, every run counted, best first")
                .containsExactly(new Best(a, 25_000, 3), new Best(b, 28_000, 1));
        assertThat(seasons.top(course, 2, 10))
                .describedAs("each season its own")
                .containsExactly(new Best(b, 40_000, 1));
        assertThat(seasons.top(course, 3, 10)).isEmpty();
        assertThat(seasons.top(course, 1, 1)).containsExactly(new Best(a, 25_000, 3));

        assertThat(seasons.clear(course, Optional.of(b))).isEqualTo(2);
        assertThat(seasons.top(course, 1, 10)).containsExactly(new Best(a, 25_000, 3));
        assertThat(seasons.clear(course, Optional.empty())).isEqualTo(1);
        assertThat(seasons.top(course, 1, 10)).isEmpty();
        assertThat(seasons.top(other, 2, 10))
                .describedAs("another course untouched")
                .hasSize(1);

        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement("DELETE FROM parkour_courses WHERE island_id = ?")) {
            ps.setString(1, other.value().toString());
            ps.executeUpdate();
        }
        assertThat(seasons.top(other, 2, 10))
                .describedAs("the times go with the course")
                .isEmpty();
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
