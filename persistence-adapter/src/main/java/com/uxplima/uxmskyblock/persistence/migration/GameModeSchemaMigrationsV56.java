package com.uxplima.uxmskyblock.persistence.migration;

import static com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations.V56_DESCRIPTION;

import com.uxplima.uxmlib.storage.migration.Migration;
import com.uxplima.uxmlib.storage.sql.Dialect;

/**
 * V56: the phase each OneBlock island picked to follow one that branches.
 *
 * <p>A phase may name the phases that can follow it. An island's way through the phases is read from its picks,
 * one row for each phase it picked after, so a restart keeps it on the way it chose.
 */
final class GameModeSchemaMigrationsV56 {

    private GameModeSchemaMigrationsV56() {}

    static Migration migration(Dialect dialect) {
        return switch (dialect) {
            case SQLITE -> new Migration(56, V56_DESCRIPTION, """
                    CREATE TABLE IF NOT EXISTS oneblock_branches (
                        island_id TEXT NOT NULL,
                        phase VARCHAR(64) NOT NULL,
                        chosen VARCHAR(64) NOT NULL,
                        PRIMARY KEY (island_id, phase),
                        CONSTRAINT fk_oneblock_branches_island FOREIGN KEY (island_id)
                            REFERENCES islands (id) ON DELETE CASCADE
                    );
                    """);
            case MYSQL -> new Migration(56, V56_DESCRIPTION, """
                    CREATE TABLE IF NOT EXISTS oneblock_branches (
                        island_id VARCHAR(36) NOT NULL,
                        phase VARCHAR(64) NOT NULL,
                        chosen VARCHAR(64) NOT NULL,
                        PRIMARY KEY (island_id, phase),
                        CONSTRAINT fk_oneblock_branches_island FOREIGN KEY (island_id)
                            REFERENCES islands (id) ON DELETE CASCADE
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                    """);
            case POSTGRES -> new Migration(56, V56_DESCRIPTION, """
                    CREATE TABLE IF NOT EXISTS oneblock_branches (
                        island_id VARCHAR(36) NOT NULL,
                        phase VARCHAR(64) NOT NULL,
                        chosen VARCHAR(64) NOT NULL,
                        PRIMARY KEY (island_id, phase),
                        CONSTRAINT fk_oneblock_branches_island FOREIGN KEY (island_id)
                            REFERENCES islands (id) ON DELETE CASCADE
                    );
                    """);
            case H2, GENERIC -> throw new IllegalArgumentException("Unsupported dialect for migration V56: " + dialect);
        };
    }
}
