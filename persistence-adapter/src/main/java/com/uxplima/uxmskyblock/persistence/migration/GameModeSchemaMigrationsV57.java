package com.uxplima.uxmskyblock.persistence.migration;

import static com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations.V57_DESCRIPTION;

import com.uxplima.uxmlib.storage.migration.Migration;
import com.uxplima.uxmlib.storage.sql.Dialect;

/**
 * V57: how many blocks each player broke on a OneBlock island.
 *
 * <p>One row for each player who broke a block on an island, so the panel and the placeholders can tell each
 * member's share after a restart. The rows go with their island.
 */
final class GameModeSchemaMigrationsV57 {

    private GameModeSchemaMigrationsV57() {}

    static Migration migration(Dialect dialect) {
        return switch (dialect) {
            case SQLITE -> new Migration(57, V57_DESCRIPTION, """
                    CREATE TABLE IF NOT EXISTS oneblock_member_breaks (
                        island_id TEXT NOT NULL,
                        player_uuid VARCHAR(36) NOT NULL,
                        breaks BIGINT NOT NULL,
                        PRIMARY KEY (island_id, player_uuid),
                        CONSTRAINT fk_oneblock_member_breaks_island FOREIGN KEY (island_id)
                            REFERENCES islands (id) ON DELETE CASCADE
                    );
                    """);
            case MYSQL -> new Migration(57, V57_DESCRIPTION, """
                    CREATE TABLE IF NOT EXISTS oneblock_member_breaks (
                        island_id VARCHAR(36) NOT NULL,
                        player_uuid VARCHAR(36) NOT NULL,
                        breaks BIGINT NOT NULL,
                        PRIMARY KEY (island_id, player_uuid),
                        CONSTRAINT fk_oneblock_member_breaks_island FOREIGN KEY (island_id)
                            REFERENCES islands (id) ON DELETE CASCADE
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                    """);
            case POSTGRES -> new Migration(57, V57_DESCRIPTION, """
                    CREATE TABLE IF NOT EXISTS oneblock_member_breaks (
                        island_id VARCHAR(36) NOT NULL,
                        player_uuid VARCHAR(36) NOT NULL,
                        breaks BIGINT NOT NULL,
                        PRIMARY KEY (island_id, player_uuid),
                        CONSTRAINT fk_oneblock_member_breaks_island FOREIGN KEY (island_id)
                            REFERENCES islands (id) ON DELETE CASCADE
                    );
                    """);
            case H2, GENERIC -> throw new IllegalArgumentException("Unsupported dialect for migration V57: " + dialect);
        };
    }
}
