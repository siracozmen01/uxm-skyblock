package com.uxplima.uxmskyblock.persistence.migration;

import static com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations.V55_DESCRIPTION;

import com.uxplima.uxmlib.storage.migration.Migration;
import com.uxplima.uxmlib.storage.sql.Dialect;

/**
 * V55: which OneBlock islands wait before a phase.
 *
 * <p>A phase may set a wait, or require lines that must hold before it begins. An island waiting is one row: the
 * phase it waits for, and when the wait ends as milliseconds since the epoch, or none while it waits for the
 * phase's requirements. A restart reads it back, so a wait goes on where it stood.
 */
final class GameModeSchemaMigrationsV55 {

    private GameModeSchemaMigrationsV55() {}

    static Migration migration(Dialect dialect) {
        return switch (dialect) {
            case SQLITE -> new Migration(55, V55_DESCRIPTION, """
                    CREATE TABLE IF NOT EXISTS oneblock_waits (
                        island_id TEXT NOT NULL PRIMARY KEY,
                        phase VARCHAR(64) NOT NULL,
                        until_millis INTEGER NULL,
                        CONSTRAINT fk_oneblock_waits_island FOREIGN KEY (island_id)
                            REFERENCES islands (id) ON DELETE CASCADE
                    );
                    """);
            case MYSQL -> new Migration(55, V55_DESCRIPTION, """
                    CREATE TABLE IF NOT EXISTS oneblock_waits (
                        island_id VARCHAR(36) NOT NULL PRIMARY KEY,
                        phase VARCHAR(64) NOT NULL,
                        until_millis BIGINT NULL,
                        CONSTRAINT fk_oneblock_waits_island FOREIGN KEY (island_id)
                            REFERENCES islands (id) ON DELETE CASCADE
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                    """);
            case POSTGRES -> new Migration(55, V55_DESCRIPTION, """
                    CREATE TABLE IF NOT EXISTS oneblock_waits (
                        island_id VARCHAR(36) NOT NULL PRIMARY KEY,
                        phase VARCHAR(64) NOT NULL,
                        until_millis BIGINT NULL,
                        CONSTRAINT fk_oneblock_waits_island FOREIGN KEY (island_id)
                            REFERENCES islands (id) ON DELETE CASCADE
                    );
                    """);
            case H2, GENERIC -> throw new IllegalArgumentException("Unsupported dialect for migration V55: " + dialect);
        };
    }
}
