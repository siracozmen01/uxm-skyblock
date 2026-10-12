package com.uxplima.uxmskyblock.persistence.migration;

import static com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations.V54_DESCRIPTION;

import com.uxplima.uxmlib.storage.migration.Migration;
import com.uxplima.uxmlib.storage.sql.Dialect;

/**
 * V54: what each OneBlock island has been paid.
 *
 * <p>A OneBlock island's count is written on a schedule, so a crash rolls it back a little, and an operator may set
 * it back on purpose. Either way the island reached a moment or finished a phase it had already been paid for, and
 * was paid again. One row per reward of a phase keeps the last pass through the phases it was paid in.
 */
final class GameModeSchemaMigrationsV54 {

    private GameModeSchemaMigrationsV54() {}

    static Migration migration(Dialect dialect) {
        return switch (dialect) {
            case SQLITE -> new Migration(54, V54_DESCRIPTION, """
                    CREATE TABLE IF NOT EXISTS oneblock_rewards (
                        island_id TEXT NOT NULL,
                        phase VARCHAR(64) NOT NULL,
                        reward VARCHAR(64) NOT NULL,
                        paid_cycle INTEGER NOT NULL,
                        paid_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (island_id, phase, reward),
                        CONSTRAINT fk_oneblock_rewards_island FOREIGN KEY (island_id)
                            REFERENCES islands (id) ON DELETE CASCADE
                    );
                    """);
            case MYSQL -> new Migration(54, V54_DESCRIPTION, """
                    CREATE TABLE IF NOT EXISTS oneblock_rewards (
                        island_id VARCHAR(36) NOT NULL,
                        phase VARCHAR(64) NOT NULL,
                        reward VARCHAR(64) NOT NULL,
                        paid_cycle BIGINT NOT NULL,
                        paid_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (island_id, phase, reward),
                        CONSTRAINT fk_oneblock_rewards_island FOREIGN KEY (island_id)
                            REFERENCES islands (id) ON DELETE CASCADE
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                    """);
            case POSTGRES -> new Migration(54, V54_DESCRIPTION, """
                    CREATE TABLE IF NOT EXISTS oneblock_rewards (
                        island_id VARCHAR(36) NOT NULL,
                        phase VARCHAR(64) NOT NULL,
                        reward VARCHAR(64) NOT NULL,
                        paid_cycle BIGINT NOT NULL,
                        paid_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (island_id, phase, reward),
                        CONSTRAINT fk_oneblock_rewards_island FOREIGN KEY (island_id)
                            REFERENCES islands (id) ON DELETE CASCADE
                    );
                    """);
            case H2, GENERIC -> throw new IllegalArgumentException("Unsupported dialect for migration V54: " + dialect);
        };
    }
}
