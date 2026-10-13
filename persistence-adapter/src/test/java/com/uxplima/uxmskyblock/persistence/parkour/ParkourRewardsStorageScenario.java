package com.uxplima.uxmskyblock.persistence.parkour;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;

/** What every engine must do with the moments each runner was paid each reward list. */
final class ParkourRewardsStorageScenario {

    private ParkourRewardsStorageScenario() {}

    static void run(Database database) throws SQLException {
        IslandId course = IslandId.of(UUID.randomUUID());
        PlayerUuid runner = PlayerUuid.of(UUID.randomUUID());
        PlayerUuid second = PlayerUuid.of(UUID.randomUUID());
        SqlParkourAdapter parkour = new SqlParkourAdapter(database.dataSource());
        SqlParkourRewardsAdapter paid = new SqlParkourRewardsAdapter(database.dataSource());
        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO islands (id, owner_account_uuid, owner_profile_id, lifecycle, created_at, "
                                + "updated_at) VALUES (?, ?, ?, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)")) {
            ps.setString(1, course.value().toString());
            ps.setString(2, UUID.randomUUID().toString());
            ps.setString(3, UUID.randomUUID().toString());
            ps.executeUpdate();
        }
        parkour.add(course);

        assertThat(paid.claim(course, runner, "first-finish", 1_000, Long.MIN_VALUE))
                .isTrue();
        assertThat(paid.claim(course, runner, "first-finish", 9_000_000, Long.MIN_VALUE))
                .describedAs("a list paid once for good is not paid again")
                .isFalse();
        assertThat(paid.claim(course, runner, "personal-best", 1_000, 0)).isTrue();
        assertThat(paid.claim(course, runner, "personal-best", 2_000, 2_000 - 86_400_000L))
                .describedAs("within its period")
                .isFalse();
        assertThat(paid.claim(course, runner, "personal-best", 90_000_000L, 90_000_000L - 86_400_000L))
                .describedAs("once its period has passed")
                .isTrue();
        assertThat(paid.claim(course, runner, "personal-best", 90_000_500L, 89_999_999L))
                .describedAs("paid a moment after the cut")
                .isFalse();
        assertThat(paid.claim(course, runner, "personal-best", 90_001_000L, 90_000_000L))
                .describedAs("paid at the cut")
                .isTrue();
        assertThat(paid.claim(course, second, "personal-best", 2_000, 0))
                .describedAs("each runner apart")
                .isTrue();

        parkour.finish(course, runner, 30_000);
        parkour.clear(course, Optional.of(runner));
        assertThat(paid.claim(course, runner, "first-finish", 10_000_000, Long.MIN_VALUE))
                .describedAs("forgetting the runs does not open the lists again")
                .isFalse();

        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement("DELETE FROM islands WHERE id = ?")) {
            ps.setString(1, course.value().toString());
            ps.executeUpdate();
        }
        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM parkour_rewards_paid")) {
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1))
                        .describedAs("the course gone takes its rows")
                        .isZero();
            }
        }
    }
}
