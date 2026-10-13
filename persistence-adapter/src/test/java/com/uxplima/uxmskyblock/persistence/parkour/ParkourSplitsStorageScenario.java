package com.uxplima.uxmskyblock.persistence.parkour;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.application.parkour.ParkourSplitsPort.Checkpoint;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;

/** What every engine must do with the split times of each runner's best run. */
final class ParkourSplitsStorageScenario {

    private ParkourSplitsStorageScenario() {}

    static void run(Database database) throws SQLException {
        IslandId course = IslandId.of(UUID.randomUUID());
        PlayerUuid runner = PlayerUuid.of(UUID.randomUUID());
        PlayerUuid second = PlayerUuid.of(UUID.randomUUID());
        SqlParkourAdapter parkour = new SqlParkourAdapter(database.dataSource());
        SqlParkourSplitsAdapter splits = new SqlParkourSplitsAdapter(database.dataSource());
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
        assertThat(splits.best(course, runner))
                .describedAs("none before a finish")
                .isEmpty();

        parkour.finish(course, runner, 30_000);
        splits.keep(course, runner, Map.of(new Checkpoint(1, 64, -3), 4_000L, new Checkpoint(-9, 70, 12), 15_000L));
        parkour.finish(course, second, 40_000);
        splits.keep(course, second, Map.of(new Checkpoint(1, 64, -3), 6_000L));
        splits.keep(course, runner, Map.of(new Checkpoint(1, 64, -3), 3_500L, new Checkpoint(5, 66, 0), 9_000L));
        assertThat(splits.best(course, runner))
                .describedAs("a new best's splits take the place of the old ones")
                .isEqualTo(Map.of(new Checkpoint(1, 64, -3), 3_500L, new Checkpoint(5, 66, 0), 9_000L));
        assertThat(splits.best(course, second)).isEqualTo(Map.of(new Checkpoint(1, 64, -3), 6_000L));

        PlayerUuid stranger = PlayerUuid.of(UUID.randomUUID());
        assertThatThrownBy(() -> splits.keep(course, stranger, Map.of(new Checkpoint(0, 0, 0), 1L)))
                .describedAs("splits need a record")
                .isInstanceOf(IllegalStateException.class);
        splits.keep(course, runner, Map.of());
        assertThat(splits.best(course, runner))
                .describedAs("a best with no checkpoint keeps none")
                .isEmpty();
        splits.keep(course, runner, Map.of(new Checkpoint(2, 2, 2), 2L));

        parkour.clear(course, Optional.of(runner));
        assertThat(splits.best(course, runner))
                .describedAs("the record forgotten takes its splits")
                .isEmpty();
        assertThat(splits.best(course, second)).hasSize(1);
    }
}
