package com.uxplima.uxmskyblock.persistence.migration;

import static com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations.V66_DESCRIPTION;

import com.uxplima.uxmlib.storage.migration.Migration;
import com.uxplima.uxmlib.storage.sql.Dialect;

/**
 * V66: the split times of each runner's best run on each Parkour course.
 *
 * <p>One row for each checkpoint the best run reached: the block its plate stands on, and how long the run took to it.
 * The rows hang off the runner's record on the course, so an operator forgetting the record, or the course going,
 * takes them too.
 */
final class GameModeSchemaMigrationsV66 {

    private GameModeSchemaMigrationsV66() {}

    static Migration migration(Dialect dialect) {
        return switch (dialect) {
            case SQLITE -> new Migration(66, V66_DESCRIPTION, """
                    CREATE TABLE IF NOT EXISTS parkour_splits (
                        island_id TEXT NOT NULL,
                        player_uuid TEXT NOT NULL,
                        x INTEGER NOT NULL,
                        y INTEGER NOT NULL,
                        z INTEGER NOT NULL,
                        millis INTEGER NOT NULL,
                        PRIMARY KEY (island_id, player_uuid, x, y, z),
                        CONSTRAINT fk_parkour_splits_record FOREIGN KEY (island_id, player_uuid)
                            REFERENCES parkour_records (island_id, player_uuid) ON DELETE CASCADE
                    );
                    """);
            case MYSQL -> new Migration(66, V66_DESCRIPTION, """
                    CREATE TABLE IF NOT EXISTS parkour_splits (
                        island_id VARCHAR(36) NOT NULL,
                        player_uuid VARCHAR(36) NOT NULL,
                        x INT NOT NULL,
                        y INT NOT NULL,
                        z INT NOT NULL,
                        millis BIGINT NOT NULL,
                        PRIMARY KEY (island_id, player_uuid, x, y, z),
                        CONSTRAINT fk_parkour_splits_record FOREIGN KEY (island_id, player_uuid)
                            REFERENCES parkour_records (island_id, player_uuid) ON DELETE CASCADE
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                    """);
            case POSTGRES -> new Migration(66, V66_DESCRIPTION, """
                    CREATE TABLE IF NOT EXISTS parkour_splits (
                        island_id VARCHAR(36) NOT NULL,
                        player_uuid VARCHAR(36) NOT NULL,
                        x INT NOT NULL,
                        y INT NOT NULL,
                        z INT NOT NULL,
                        millis BIGINT NOT NULL,
                        PRIMARY KEY (island_id, player_uuid, x, y, z),
                        CONSTRAINT fk_parkour_splits_record FOREIGN KEY (island_id, player_uuid)
                            REFERENCES parkour_records (island_id, player_uuid) ON DELETE CASCADE
                    );
                    """);
            case H2, GENERIC -> throw new IllegalArgumentException("Unsupported dialect for migration V66: " + dialect);
        };
    }
}
