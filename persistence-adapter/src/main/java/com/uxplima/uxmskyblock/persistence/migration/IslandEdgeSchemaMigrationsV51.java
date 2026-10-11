package com.uxplima.uxmskyblock.persistence.migration;

import static com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations.V51_DESCRIPTION;

import com.uxplima.uxmlib.storage.migration.Migration;
import com.uxplima.uxmlib.storage.sql.Dialect;

/**
 * V51: an island keeps how its edge is drawn.
 *
 * <p>Every island's edge was the square border a client draws, in the colour each player picked. An island now
 * keeps a style of its own, chosen by its members: the client's border, a wall of a style walls.conf names, or
 * nothing. An island that never chose keeps the server's default, and has no row.
 */
final class IslandEdgeSchemaMigrationsV51 {

    private IslandEdgeSchemaMigrationsV51() {}

    static Migration migration(Dialect dialect) {
        return switch (dialect) {
            case SQLITE -> new Migration(51, V51_DESCRIPTION, """
                    CREATE TABLE island_edges (
                        island_id TEXT NOT NULL PRIMARY KEY,
                        style TEXT NOT NULL,
                        CONSTRAINT fk_island_edge_island FOREIGN KEY (island_id)
                            REFERENCES islands (id) ON DELETE CASCADE
                    );
                    """);
            case MYSQL -> new Migration(51, V51_DESCRIPTION, """
                    CREATE TABLE island_edges (
                        island_id VARCHAR(36) NOT NULL PRIMARY KEY,
                        style VARCHAR(64) NOT NULL,
                        CONSTRAINT fk_island_edge_island FOREIGN KEY (island_id)
                            REFERENCES islands (id) ON DELETE CASCADE
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                    """);
            case POSTGRES -> new Migration(51, V51_DESCRIPTION, """
                    CREATE TABLE island_edges (
                        island_id VARCHAR(36) NOT NULL PRIMARY KEY,
                        style VARCHAR(64) NOT NULL,
                        CONSTRAINT fk_island_edge_island FOREIGN KEY (island_id)
                            REFERENCES islands (id) ON DELETE CASCADE
                    );
                    """);
            case H2, GENERIC -> throw new IllegalArgumentException("Unsupported dialect for migration V51: " + dialect);
        };
    }
}
