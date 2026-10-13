package com.uxplima.uxmskyblock.persistence.migration;

import static com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations.V63_DESCRIPTION;

import com.uxplima.uxmlib.storage.migration.Migration;
import com.uxplima.uxmlib.storage.sql.Dialect;

/**
 * V63: the Boxed boxes an ender pearl moved.
 *
 * <p>One row for each moved box: how far its middle lies from the island's centre along x and z. A box never moved
 * has no row. A Boxed island that is gone takes its row with it.
 */
final class GameModeSchemaMigrationsV63 {

    private GameModeSchemaMigrationsV63() {}

    static Migration migration(Dialect dialect) {
        return switch (dialect) {
            case SQLITE -> new Migration(63, V63_DESCRIPTION, """
                    CREATE TABLE IF NOT EXISTS boxed_offsets (
                        island_id TEXT NOT NULL PRIMARY KEY,
                        dx INTEGER NOT NULL,
                        dz INTEGER NOT NULL,
                        CONSTRAINT fk_boxed_offsets_state FOREIGN KEY (island_id)
                            REFERENCES boxed_instance_state (island_id) ON DELETE CASCADE
                    );
                    """);
            case MYSQL -> new Migration(63, V63_DESCRIPTION, """
                    CREATE TABLE IF NOT EXISTS boxed_offsets (
                        island_id VARCHAR(36) NOT NULL PRIMARY KEY,
                        dx INTEGER NOT NULL,
                        dz INTEGER NOT NULL,
                        CONSTRAINT fk_boxed_offsets_state FOREIGN KEY (island_id)
                            REFERENCES boxed_instance_state (island_id) ON DELETE CASCADE
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                    """);
            case POSTGRES -> new Migration(63, V63_DESCRIPTION, """
                    CREATE TABLE IF NOT EXISTS boxed_offsets (
                        island_id VARCHAR(36) NOT NULL PRIMARY KEY,
                        dx INTEGER NOT NULL,
                        dz INTEGER NOT NULL,
                        CONSTRAINT fk_boxed_offsets_state FOREIGN KEY (island_id)
                            REFERENCES boxed_instance_state (island_id) ON DELETE CASCADE
                    );
                    """);
            case H2, GENERIC -> throw new IllegalArgumentException("Unsupported dialect for migration V63: " + dialect);
        };
    }
}
