package com.uxplima.uxmskyblock.persistence.parkour;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.application.parkour.ParkourTagsPort.Rating;
import com.uxplima.uxmskyblock.core.application.parkour.ParkourTagsPort.Tags;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;

/** What every engine must do with each course's tags and its runners' stars. */
final class ParkourTagsStorageScenario {

    private ParkourTagsStorageScenario() {}

    static void run(Database database) throws SQLException {
        IslandId course = IslandId.of(UUID.randomUUID());
        IslandId other = IslandId.of(UUID.randomUUID());
        SqlParkourAdapter parkour = new SqlParkourAdapter(database.dataSource());
        SqlParkourTagsAdapter tags = new SqlParkourTagsAdapter(database.dataSource());
        for (IslandId each : new IslandId[] {course, other}) {
            try (Connection conn = database.connection();
                    PreparedStatement ps = conn.prepareStatement(
                            "INSERT INTO islands (id, owner_account_uuid, owner_profile_id, lifecycle, created_at, "
                                    + "updated_at) VALUES (?, ?, ?, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)")) {
                ps.setString(1, each.value().toString());
                ps.setString(2, UUID.randomUUID().toString());
                ps.setString(3, UUID.randomUUID().toString());
                ps.executeUpdate();
            }
            parkour.add(each);
        }

        assertThat(tags.tags(course)).isEqualTo(Tags.NONE);
        assertThat(tags.rating(course)).isEqualTo(Rating.NONE);
        tags.tag(course, new Tags("hard", null));
        assertThat(tags.tags(course)).isEqualTo(new Tags("hard", null));
        tags.tag(course, new Tags("hard", "speed"));
        tags.tag(other, new Tags(null, "maze"));
        assertThat(tags.tags(course)).isEqualTo(new Tags("hard", "speed"));
        tags.tag(course, new Tags(null, "speed"));
        assertThat(tags.tags(course)).describedAs("a tag taken off").isEqualTo(new Tags(null, "speed"));
        assertThat(tags.tags(other)).isEqualTo(new Tags(null, "maze"));

        PlayerUuid a = PlayerUuid.of(UUID.randomUUID());
        PlayerUuid b = PlayerUuid.of(UUID.randomUUID());
        tags.rate(course, a, 5);
        tags.rate(course, b, 2);
        tags.rate(course, a, 4);
        tags.rate(other, a, 1);
        assertThat(tags.rating(course).count())
                .describedAs("a runner rates once")
                .isEqualTo(2);
        assertThat(tags.rating(course).average()).isCloseTo(3.0, within(1e-9));
        assertThat(tags.rating(other)).isEqualTo(new Rating(1.0, 1));

        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement("DELETE FROM islands WHERE id = ?")) {
            ps.setString(1, course.value().toString());
            ps.executeUpdate();
        }
        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement("SELECT (SELECT COUNT(*) FROM parkour_course_tags"
                        + " WHERE island_id = ?) + (SELECT COUNT(*) FROM parkour_ratings WHERE island_id = ?)")) {
            ps.setString(1, course.value().toString());
            ps.setString(2, course.value().toString());
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1))
                        .describedAs("the course gone takes its tags and stars")
                        .isZero();
            }
        }
        assertThat(tags.tags(other)).isEqualTo(new Tags(null, "maze"));
    }
}
