package com.uxplima.uxmskyblock.persistence.boxed;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.OptionalInt;
import java.util.UUID;

import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/** What every engine must do with how far each Boxed island's template land is copied. */
final class BoxedLandStorageScenario {

    private BoxedLandStorageScenario() {}

    static void run(Database database) throws SQLException {
        IslandId island = IslandId.of(UUID.randomUUID());
        IslandId other = IslandId.of(UUID.randomUUID());
        SqlBoxedIslandsAdapter boxed = new SqlBoxedIslandsAdapter(database.dataSource());
        SqlBoxedLandAdapter land = new SqlBoxedLandAdapter(database.dataSource());
        for (IslandId each : new IslandId[] {island, other}) {
            seedIsland(database, each);
            boxed.add(each);
        }

        assertThat(land.rings(island)).describedAs("the world's own land").isEmpty();
        assertThat(land.copied(island, -1, 0)).describedAs("no row to move").isFalse();
        land.begin(island);
        land.begin(island);
        assertThat(land.rings(island)).isEqualTo(OptionalInt.of(-1));
        assertThat(land.copied(island, 0, 1))
                .describedAs("not from what is kept")
                .isFalse();
        assertThat(land.copied(island, -1, 0)).isTrue();
        assertThat(land.copied(island, 0, 3)).isTrue();
        assertThat(land.rings(island)).isEqualTo(OptionalInt.of(3));
        assertThat(land.copied(island, 1, 2)).describedAs("nor from behind it").isFalse();
        assertThat(land.rings(island)).isEqualTo(OptionalInt.of(3));
        assertThat(land.rings(other))
                .describedAs("one island's land is its own")
                .isEmpty();
        land.begin(other);
        assertThat(land.copied(other, -1, 1)).isTrue();
        assertThat(land.rings(island)).isEqualTo(OptionalInt.of(3));

        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement("DELETE FROM islands WHERE id = ?")) {
            ps.setString(1, island.value().toString());
            ps.executeUpdate();
        }
        assertThat(land.rings(island))
                .describedAs("an island gone takes its land")
                .isEmpty();
        assertThat(land.rings(other)).isEqualTo(OptionalInt.of(1));
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
