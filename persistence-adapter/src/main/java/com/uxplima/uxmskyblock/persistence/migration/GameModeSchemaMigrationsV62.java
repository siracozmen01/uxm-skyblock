package com.uxplima.uxmskyblock.persistence.migration;

import static com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations.V62_DESCRIPTION;

import com.uxplima.uxmlib.storage.migration.Migration;
import com.uxplima.uxmlib.storage.sql.Dialect;

/**
 * V62: the Boxed islands played on the template's land.
 *
 * <p>One row for each such island: how many rings of chunks around its centre hold the template's land, -1 before
 * the first is copied. An island with no row plays on the land its world made. A Boxed island that is gone takes its
 * row with it.
 */
final class GameModeSchemaMigrationsV62 {

    private GameModeSchemaMigrationsV62() {}

    static Migration migration(Dialect dialect) {
        return switch (dialect) {
            case SQLITE -> new Migration(62, V62_DESCRIPTION, """
                    CREATE TABLE IF NOT EXISTS boxed_land (
                        island_id TEXT NOT NULL PRIMARY KEY,
                        rings INTEGER NOT NULL,
                        CONSTRAINT fk_boxed_land_state FOREIGN KEY (island_id)
                            REFERENCES boxed_instance_state (island_id) ON DELETE CASCADE
                    );
                    """);
            case MYSQL -> new Migration(62, V62_DESCRIPTION, """
                    CREATE TABLE IF NOT EXISTS boxed_land (
                        island_id VARCHAR(36) NOT NULL PRIMARY KEY,
                        rings INTEGER NOT NULL,
                        CONSTRAINT fk_boxed_land_state FOREIGN KEY (island_id)
                            REFERENCES boxed_instance_state (island_id) ON DELETE CASCADE
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                    """);
            case POSTGRES -> new Migration(62, V62_DESCRIPTION, """
                    CREATE TABLE IF NOT EXISTS boxed_land (
                        island_id VARCHAR(36) NOT NULL PRIMARY KEY,
                        rings INTEGER NOT NULL,
                        CONSTRAINT fk_boxed_land_state FOREIGN KEY (island_id)
                            REFERENCES boxed_instance_state (island_id) ON DELETE CASCADE
                    );
                    """);
            case H2, GENERIC -> throw new IllegalArgumentException("Unsupported dialect for migration V62: " + dialect);
        };
    }
}
