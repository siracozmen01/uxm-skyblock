package com.uxplima.uxmskyblock.persistence.migration;

import static com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations.V49_DESCRIPTION;

import com.uxplima.uxmlib.storage.migration.Migration;
import com.uxplima.uxmlib.storage.sql.Dialect;

/**
 * V49: where each island wrote blocks past its own border.
 *
 * <p>An island was cleared over its border, in its own world, when it was deleted. What its game mode wrote further
 * out stayed: a SkyGrid's grid past the border of the island a player starts with, an Upside Down in the Nether. The
 * next island laid in the same place found it. Each square an island writes in is kept here until the island is
 * cleared over it. The rows outlive the island's own row on purpose, since clearing finishes after it is deleted.
 */
final class RecycleSchemaMigrationsV49 {

    private RecycleSchemaMigrationsV49() {}

    static Migration migration(Dialect dialect) {
        return switch (dialect) {
            case SQLITE, MYSQL, POSTGRES -> new Migration(49, V49_DESCRIPTION, """
                    CREATE TABLE island_footprints (
                        island_id VARCHAR(36) NOT NULL,
                        world_name VARCHAR(64) NOT NULL,
                        center_x INT NOT NULL,
                        center_z INT NOT NULL,
                        radius INT NOT NULL,
                        PRIMARY KEY (island_id, world_name, center_x, center_z)
                    );
                    """);
            case H2, GENERIC -> throw new IllegalArgumentException("Unsupported dialect for migration V49: " + dialect);
        };
    }
}
