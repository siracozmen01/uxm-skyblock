package com.uxplima.uxmskyblock.persistence.migration;

import static com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations.V64_DESCRIPTION;

import com.uxplima.uxmlib.storage.migration.Migration;
import com.uxplima.uxmlib.storage.sql.Dialect;

/**
 * V64: the side each Boxed island's box grows toward.
 *
 * <p>One row for each island that chose a side, by its name, as {@code north_east}. An island that never chose grows
 * every edge alike and has no row. A Boxed island that is gone takes its row with it.
 */
final class GameModeSchemaMigrationsV64 {

    private GameModeSchemaMigrationsV64() {}

    static Migration migration(Dialect dialect) {
        return switch (dialect) {
            case SQLITE -> new Migration(64, V64_DESCRIPTION, """
                    CREATE TABLE IF NOT EXISTS boxed_sides (
                        island_id TEXT NOT NULL PRIMARY KEY,
                        side VARCHAR(16) NOT NULL,
                        CONSTRAINT fk_boxed_sides_state FOREIGN KEY (island_id)
                            REFERENCES boxed_instance_state (island_id) ON DELETE CASCADE
                    );
                    """);
            case MYSQL -> new Migration(64, V64_DESCRIPTION, """
                    CREATE TABLE IF NOT EXISTS boxed_sides (
                        island_id VARCHAR(36) NOT NULL PRIMARY KEY,
                        side VARCHAR(16) NOT NULL,
                        CONSTRAINT fk_boxed_sides_state FOREIGN KEY (island_id)
                            REFERENCES boxed_instance_state (island_id) ON DELETE CASCADE
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                    """);
            case POSTGRES -> new Migration(64, V64_DESCRIPTION, """
                    CREATE TABLE IF NOT EXISTS boxed_sides (
                        island_id VARCHAR(36) NOT NULL PRIMARY KEY,
                        side VARCHAR(16) NOT NULL,
                        CONSTRAINT fk_boxed_sides_state FOREIGN KEY (island_id)
                            REFERENCES boxed_instance_state (island_id) ON DELETE CASCADE
                    );
                    """);
            case H2, GENERIC -> throw new IllegalArgumentException("Unsupported dialect for migration V64: " + dialect);
        };
    }
}
