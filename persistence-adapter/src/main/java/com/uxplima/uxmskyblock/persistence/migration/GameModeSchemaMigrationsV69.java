package com.uxplima.uxmskyblock.persistence.migration;

import static com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations.V69_DESCRIPTION;

import com.uxplima.uxmlib.storage.migration.Migration;
import com.uxplima.uxmlib.storage.sql.Dialect;

/**
 * V69: each runner's best time on each Parkour course in each season.
 *
 * <p>One row per course, season and runner, beside the best of all time in parkour_records. The rows go with the
 * course.
 */
final class GameModeSchemaMigrationsV69 {

    private GameModeSchemaMigrationsV69() {}

    static Migration migration(Dialect dialect) {
        return switch (dialect) {
            case SQLITE -> new Migration(69, V69_DESCRIPTION, """
                    CREATE TABLE IF NOT EXISTS parkour_season_times (
                        island_id TEXT NOT NULL,
                        season_number INTEGER NOT NULL,
                        player_uuid TEXT NOT NULL,
                        best_millis INTEGER NOT NULL,
                        runs INTEGER NOT NULL,
                        PRIMARY KEY (island_id, season_number, player_uuid),
                        CONSTRAINT fk_parkour_season_times_course FOREIGN KEY (island_id)
                            REFERENCES parkour_courses (island_id) ON DELETE CASCADE
                    );
                    """);
            case MYSQL -> new Migration(69, V69_DESCRIPTION, """
                    CREATE TABLE IF NOT EXISTS parkour_season_times (
                        island_id VARCHAR(36) NOT NULL,
                        season_number INT NOT NULL,
                        player_uuid VARCHAR(36) NOT NULL,
                        best_millis BIGINT NOT NULL,
                        runs INT NOT NULL,
                        PRIMARY KEY (island_id, season_number, player_uuid),
                        CONSTRAINT fk_parkour_season_times_course FOREIGN KEY (island_id)
                            REFERENCES parkour_courses (island_id) ON DELETE CASCADE
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                    """);
            case POSTGRES -> new Migration(69, V69_DESCRIPTION, """
                    CREATE TABLE IF NOT EXISTS parkour_season_times (
                        island_id VARCHAR(36) NOT NULL,
                        season_number INT NOT NULL,
                        player_uuid VARCHAR(36) NOT NULL,
                        best_millis BIGINT NOT NULL,
                        runs INT NOT NULL,
                        PRIMARY KEY (island_id, season_number, player_uuid),
                        CONSTRAINT fk_parkour_season_times_course FOREIGN KEY (island_id)
                            REFERENCES parkour_courses (island_id) ON DELETE CASCADE
                    );
                    """);
            case H2, GENERIC -> throw new IllegalArgumentException("Unsupported dialect for migration V69: " + dialect);
        };
    }
}
