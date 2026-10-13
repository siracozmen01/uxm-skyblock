package com.uxplima.uxmskyblock.persistence.migration;

import static com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations.V65_DESCRIPTION;

import com.uxplima.uxmlib.storage.migration.Migration;
import com.uxplima.uxmlib.storage.sql.Dialect;

/**
 * V65: each runner's best run on each Parkour course, as its ghost replays it.
 *
 * <p>One row for each runner and course with a ghost: how long the run took and its path, encoded. The row hangs off
 * the runner's record on the course, so an operator forgetting the record, or the course going, takes the ghost too.
 */
final class GameModeSchemaMigrationsV65 {

    private GameModeSchemaMigrationsV65() {}

    static Migration migration(Dialect dialect) {
        return switch (dialect) {
            case SQLITE -> new Migration(65, V65_DESCRIPTION, """
                    CREATE TABLE IF NOT EXISTS parkour_ghosts (
                        island_id TEXT NOT NULL,
                        player_uuid TEXT NOT NULL,
                        millis INTEGER NOT NULL,
                        path BLOB NOT NULL,
                        PRIMARY KEY (island_id, player_uuid),
                        CONSTRAINT fk_parkour_ghosts_record FOREIGN KEY (island_id, player_uuid)
                            REFERENCES parkour_records (island_id, player_uuid) ON DELETE CASCADE
                    );
                    CREATE INDEX IF NOT EXISTS idx_parkour_ghosts_fastest ON parkour_ghosts (island_id, millis);
                    """);
            case MYSQL -> new Migration(65, V65_DESCRIPTION, """
                    CREATE TABLE IF NOT EXISTS parkour_ghosts (
                        island_id VARCHAR(36) NOT NULL,
                        player_uuid VARCHAR(36) NOT NULL,
                        millis BIGINT NOT NULL,
                        path MEDIUMBLOB NOT NULL,
                        PRIMARY KEY (island_id, player_uuid),
                        INDEX idx_parkour_ghosts_fastest (island_id, millis),
                        CONSTRAINT fk_parkour_ghosts_record FOREIGN KEY (island_id, player_uuid)
                            REFERENCES parkour_records (island_id, player_uuid) ON DELETE CASCADE
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                    """);
            case POSTGRES -> new Migration(65, V65_DESCRIPTION, """
                    CREATE TABLE IF NOT EXISTS parkour_ghosts (
                        island_id VARCHAR(36) NOT NULL,
                        player_uuid VARCHAR(36) NOT NULL,
                        millis BIGINT NOT NULL,
                        path BYTEA NOT NULL,
                        PRIMARY KEY (island_id, player_uuid),
                        CONSTRAINT fk_parkour_ghosts_record FOREIGN KEY (island_id, player_uuid)
                            REFERENCES parkour_records (island_id, player_uuid) ON DELETE CASCADE
                    );
                    CREATE INDEX IF NOT EXISTS idx_parkour_ghosts_fastest ON parkour_ghosts (island_id, millis);
                    """);
            case H2, GENERIC -> throw new IllegalArgumentException("Unsupported dialect for migration V65: " + dialect);
        };
    }
}
