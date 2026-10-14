package com.uxplima.uxmskyblock.persistence.migration;

import static com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations.V73_DESCRIPTION;

import com.uxplima.uxmlib.storage.migration.Migration;
import com.uxplima.uxmlib.storage.sql.Dialect;

/** V73: the fee each island charges a visitor, in minor units. An island that charges none has no row. */
final class VisitorSchemaMigrationsV73 {

    private VisitorSchemaMigrationsV73() {}

    static Migration migration(Dialect dialect) {
        return switch (dialect) {
            case SQLITE -> new Migration(73, V73_DESCRIPTION, """
                    CREATE TABLE IF NOT EXISTS island_visit_fees (
                        island_id TEXT NOT NULL PRIMARY KEY,
                        fee_minor INTEGER NOT NULL,
                        CONSTRAINT fk_island_visit_fee_island FOREIGN KEY (island_id)
                            REFERENCES islands (id) ON DELETE CASCADE
                    );
                    """);
            case MYSQL -> new Migration(73, V73_DESCRIPTION, """
                    CREATE TABLE IF NOT EXISTS island_visit_fees (
                        island_id VARCHAR(36) NOT NULL PRIMARY KEY,
                        fee_minor BIGINT NOT NULL,
                        CONSTRAINT fk_island_visit_fee_island FOREIGN KEY (island_id)
                            REFERENCES islands (id) ON DELETE CASCADE
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                    """);
            case POSTGRES -> new Migration(73, V73_DESCRIPTION, """
                    CREATE TABLE IF NOT EXISTS island_visit_fees (
                        island_id VARCHAR(36) NOT NULL PRIMARY KEY,
                        fee_minor BIGINT NOT NULL,
                        CONSTRAINT fk_island_visit_fee_island FOREIGN KEY (island_id)
                            REFERENCES islands (id) ON DELETE CASCADE
                    );
                    """);
            case H2, GENERIC -> throw new IllegalArgumentException("Unsupported dialect for migration V73: " + dialect);
        };
    }
}
