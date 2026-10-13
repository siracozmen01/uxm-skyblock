package com.uxplima.uxmskyblock.persistence.parkour;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.application.parkour.ParkourDirectoryPort.Listing;
import com.uxplima.uxmskyblock.core.application.parkour.ParkourTagsPort.Tags;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;

/** What every engine must list of the Parkour courses. */
final class ParkourDirectoryStorageScenario {

    private ParkourDirectoryStorageScenario() {}

    static void run(Database database) throws SQLException {
        SqlParkourAdapter parkour = new SqlParkourAdapter(database.dataSource());
        SqlParkourTagsAdapter tags = new SqlParkourTagsAdapter(database.dataSource());
        SqlParkourDirectoryAdapter directory = new SqlParkourDirectoryAdapter(database.dataSource());
        UUID popularOwner = UUID.randomUUID();
        IslandId popular = island(database, popularOwner, "Sky Steps");
        IslandId quiet = island(database, UUID.randomUUID(), null);
        IslandId plain = island(database, UUID.randomUUID(), "Not a course");
        parkour.add(popular);
        parkour.add(quiet);
        assertThat(plain).isNotNull();

        PlayerUuid a = PlayerUuid.of(UUID.randomUUID());
        PlayerUuid b = PlayerUuid.of(UUID.randomUUID());
        parkour.finish(popular, a, 30_000);
        parkour.finish(popular, a, 25_000);
        parkour.finish(popular, b, 40_000);
        tags.tag(popular, new Tags("hard", "speed"));
        tags.rate(popular, a, 5);
        tags.rate(popular, b, 2);

        List<Listing> listed = directory.directory(10);
        assertThat(listed)
                .extracting(Listing::course)
                .describedAs("the most run first, courses only")
                .containsExactly(popular, quiet);
        Listing first = listed.getFirst();
        assertThat(first.name()).isEqualTo("Sky Steps");
        assertThat(first.owner()).isEqualTo(popularOwner);
        assertThat(first.tags()).isEqualTo(new Tags("hard", "speed"));
        assertThat(first.stars()).isCloseTo(3.5, within(1e-9));
        assertThat(first.ratings()).isEqualTo(2);
        assertThat(first.record()).isEqualTo(25_000L);
        assertThat(first.runs()).isEqualTo(3);
        Listing second = listed.get(1);
        assertThat(second.name()).isNull();
        assertThat(second.tags()).isEqualTo(Tags.NONE);
        assertThat(second.ratings()).isZero();
        assertThat(second.record()).describedAs("nobody finished it").isNull();
        assertThat(second.runs()).isZero();
        assertThat(directory.directory(1)).hasSize(1);
    }

    private static IslandId island(Database database, UUID owner, @org.jspecify.annotations.Nullable String name)
            throws SQLException {
        IslandId island = IslandId.of(UUID.randomUUID());
        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO islands (id, owner_account_uuid, owner_profile_id, lifecycle, custom_name,"
                                + " created_at, updated_at) VALUES (?, ?, ?, 'ACTIVE', ?, CURRENT_TIMESTAMP,"
                                + " CURRENT_TIMESTAMP)")) {
            ps.setString(1, island.value().toString());
            ps.setString(2, owner.toString());
            ps.setString(3, UUID.randomUUID().toString());
            ps.setString(4, name);
            ps.executeUpdate();
        }
        return island;
    }
}
