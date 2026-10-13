package com.uxplima.uxmskyblock.persistence.parkour;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.application.parkour.GhostPath;
import com.uxplima.uxmskyblock.core.application.parkour.ParkourGhostsPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;

/** What every engine must do with each runner's best run kept as a ghost. */
final class ParkourGhostsStorageScenario {

    private ParkourGhostsStorageScenario() {}

    static void run(Database database) throws SQLException {
        IslandId course = IslandId.of(UUID.randomUUID());
        IslandId other = IslandId.of(UUID.randomUUID());
        PlayerUuid runner = PlayerUuid.of(UUID.randomUUID());
        PlayerUuid second = PlayerUuid.of(UUID.randomUUID());
        SqlParkourAdapter parkour = new SqlParkourAdapter(database.dataSource());
        SqlParkourGhostsAdapter ghosts = new SqlParkourGhostsAdapter(database.dataSource());
        for (IslandId each : new IslandId[] {course, other}) {
            seedIsland(database, each);
            parkour.add(each);
        }
        assertThat(ghosts.fastest(course)).describedAs("no ghost yet").isEmpty();

        parkour.finish(course, runner, 30_000);
        ghosts.keep(course, runner, 30_000, path(30_000, 1));
        parkour.finish(course, runner, 40_000);
        ghosts.keep(course, runner, 40_000, path(40_000, 2));
        assertThat(ghosts.of(course, runner))
                .describedAs("a slower run leaves the ghost")
                .get()
                .satisfies(ghost -> {
                    assertThat(ghost.millis()).isEqualTo(30_000);
                    assertThat(ghost.path().poses()).isEqualTo(path(30_000, 1).poses());
                });
        parkour.finish(course, runner, 20_000);
        ghosts.keep(course, runner, 20_000, path(20_000, 3));
        assertThat(ghosts.of(course, runner)).get().satisfies(ghost -> {
            assertThat(ghost.millis()).describedAs("a faster run replaces it").isEqualTo(20_000);
            assertThat(ghost.path().poses()).isEqualTo(path(20_000, 3).poses());
        });

        parkour.finish(course, second, 25_000);
        ghosts.keep(course, second, 25_000, path(25_000, 4));
        parkour.finish(other, second, 5_000);
        ghosts.keep(other, second, 5_000, path(5_000, 5));
        assertThat(ghosts.fastest(course).map(ParkourGhostsPort.Ghost::runner)).hasValue(runner);
        assertThat(ghosts.fastest(other).map(ParkourGhostsPort.Ghost::runner)).hasValue(second);
        assertThat(ghosts.of(other, runner)).isEmpty();

        parkour.clear(course, Optional.of(runner));
        assertThat(ghosts.of(course, runner))
                .describedAs("the record forgotten takes its ghost")
                .isEmpty();
        assertThat(ghosts.fastest(course).map(ParkourGhostsPort.Ghost::runner)).hasValue(second);

        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement("DELETE FROM islands WHERE id = ?")) {
            ps.setString(1, other.value().toString());
            ps.executeUpdate();
        }
        try (Connection conn = database.connection();
                PreparedStatement ps =
                        conn.prepareStatement("SELECT COUNT(*) FROM parkour_ghosts WHERE island_id = ?")) {
            ps.setString(1, other.value().toString());
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1))
                        .describedAs("the course gone takes its ghosts")
                        .isZero();
            }
        }
    }

    /** A run of {@code millis} whose samples carry {@code mark}, so two paths tell apart. */
    private static GhostPath path(long millis, int mark) {
        GhostPath.Recorder recorder = new GhostPath.Recorder(1, 1000);
        recorder.at(0, 0.5, 64, 0.5, 0);
        recorder.at(millis / 2, mark, 66.25, -mark, 90);
        return recorder.until(millis, mark * 2, 70, 3, -45);
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
