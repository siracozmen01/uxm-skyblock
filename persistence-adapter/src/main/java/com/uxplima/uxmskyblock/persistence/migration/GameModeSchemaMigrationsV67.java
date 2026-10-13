package com.uxplima.uxmskyblock.persistence.migration;

import static com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations.V67_DESCRIPTION;

import com.uxplima.uxmlib.storage.migration.Migration;
import com.uxplima.uxmlib.storage.sql.Dialect;

/**
 * V67: when each runner was last paid each reward list of each Parkour course.
 *
 * <p>One row for each course, runner and list, holding the moment it was paid in epoch milliseconds. A claim updates
 * the row only when its period has passed, or inserts it, so two finishes at once pay one. The row hangs off the
 * course, not the runner's record: an operator forgetting the runs does not open the lists again.
 */
final class GameModeSchemaMigrationsV67 {

    private GameModeSchemaMigrationsV67() {}

    static Migration migration(Dialect dialect) {
        return switch (dialect) {
            case SQLITE -> new Migration(67, V67_DESCRIPTION, """
                    CREATE TABLE IF NOT EXISTS parkour_rewards_paid (
                        island_id TEXT NOT NULL,
                        player_uuid TEXT NOT NULL,
                        list VARCHAR(32) NOT NULL,
                        paid_at INTEGER NOT NULL,
                        PRIMARY KEY (island_id, player_uuid, list),
                        CONSTRAINT fk_parkour_rewards_paid_course FOREIGN KEY (island_id)
                            REFERENCES parkour_courses (island_id) ON DELETE CASCADE
                    );
                    """);
            case MYSQL -> new Migration(67, V67_DESCRIPTION, """
                    CREATE TABLE IF NOT EXISTS parkour_rewards_paid (
                        island_id VARCHAR(36) NOT NULL,
                        player_uuid VARCHAR(36) NOT NULL,
                        list VARCHAR(32) NOT NULL,
                        paid_at BIGINT NOT NULL,
                        PRIMARY KEY (island_id, player_uuid, list),
                        CONSTRAINT fk_parkour_rewards_paid_course FOREIGN KEY (island_id)
                            REFERENCES parkour_courses (island_id) ON DELETE CASCADE
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                    """);
            case POSTGRES -> new Migration(67, V67_DESCRIPTION, """
                    CREATE TABLE IF NOT EXISTS parkour_rewards_paid (
                        island_id VARCHAR(36) NOT NULL,
                        player_uuid VARCHAR(36) NOT NULL,
                        list VARCHAR(32) NOT NULL,
                        paid_at BIGINT NOT NULL,
                        PRIMARY KEY (island_id, player_uuid, list),
                        CONSTRAINT fk_parkour_rewards_paid_course FOREIGN KEY (island_id)
                            REFERENCES parkour_courses (island_id) ON DELETE CASCADE
                    );
                    """);
            case H2, GENERIC -> throw new IllegalArgumentException("Unsupported dialect for migration V67: " + dialect);
        };
    }
}
