package com.uxplima.uxmskyblock.persistence.migration;

import static com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations.V72_DESCRIPTION;

import com.uxplima.uxmlib.storage.migration.Migration;
import com.uxplima.uxmlib.storage.sql.Dialect;

/**
 * V72: the chunks a ChunkBlock island opens in its Nether and its End, each dimension a web of its own, and what is
 * paid toward a closed chunk there. The overworld keeps {@code chunkblock_territory_claims} and
 * {@code chunkblock_chunk_payments}; these two hold the same, with the dimension in the key.
 */
final class GameModeSchemaMigrationsV72 {

    private GameModeSchemaMigrationsV72() {}

    static Migration migration(Dialect dialect) {
        return switch (dialect) {
            case SQLITE -> new Migration(72, V72_DESCRIPTION, """
                    CREATE TABLE IF NOT EXISTS chunkblock_dimension_claims (
                        island_id TEXT NOT NULL,
                        dimension TEXT NOT NULL,
                        chunk_x INTEGER NOT NULL,
                        chunk_z INTEGER NOT NULL,
                        unlock_order INTEGER NOT NULL,
                        bought INTEGER NOT NULL DEFAULT 0,
                        unlocked_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (island_id, dimension, chunk_x, chunk_z),
                        CONSTRAINT uq_chunkblock_dimension_order UNIQUE (island_id, dimension, unlock_order),
                        CONSTRAINT fk_chunkblock_dimension_claim_island FOREIGN KEY (island_id)
                            REFERENCES islands (id) ON DELETE CASCADE
                    );
                    CREATE TABLE IF NOT EXISTS chunkblock_dimension_payments (
                        island_id TEXT NOT NULL,
                        dimension TEXT NOT NULL,
                        chunk_x INTEGER NOT NULL,
                        chunk_z INTEGER NOT NULL,
                        line_key TEXT NOT NULL,
                        paid INTEGER NOT NULL,
                        PRIMARY KEY (island_id, dimension, chunk_x, chunk_z, line_key),
                        CONSTRAINT fk_chunkblock_dimension_payment_island FOREIGN KEY (island_id)
                            REFERENCES islands (id) ON DELETE CASCADE
                    );
                    """);
            case MYSQL -> new Migration(72, V72_DESCRIPTION, """
                    CREATE TABLE IF NOT EXISTS chunkblock_dimension_claims (
                        island_id VARCHAR(36) NOT NULL,
                        dimension VARCHAR(32) NOT NULL,
                        chunk_x INT NOT NULL,
                        chunk_z INT NOT NULL,
                        unlock_order INT NOT NULL,
                        bought SMALLINT NOT NULL DEFAULT 0,
                        unlocked_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (island_id, dimension, chunk_x, chunk_z),
                        CONSTRAINT uq_chunkblock_dimension_order UNIQUE (island_id, dimension, unlock_order),
                        CONSTRAINT fk_chunkblock_dimension_claim_island FOREIGN KEY (island_id)
                            REFERENCES islands (id) ON DELETE CASCADE
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                    CREATE TABLE IF NOT EXISTS chunkblock_dimension_payments (
                        island_id VARCHAR(36) NOT NULL,
                        dimension VARCHAR(32) NOT NULL,
                        chunk_x INT NOT NULL,
                        chunk_z INT NOT NULL,
                        line_key VARCHAR(160) NOT NULL,
                        paid BIGINT NOT NULL,
                        PRIMARY KEY (island_id, dimension, chunk_x, chunk_z, line_key),
                        CONSTRAINT fk_chunkblock_dimension_payment_island FOREIGN KEY (island_id)
                            REFERENCES islands (id) ON DELETE CASCADE
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                    """);
            case POSTGRES -> new Migration(72, V72_DESCRIPTION, """
                    CREATE TABLE IF NOT EXISTS chunkblock_dimension_claims (
                        island_id VARCHAR(36) NOT NULL,
                        dimension VARCHAR(32) NOT NULL,
                        chunk_x INTEGER NOT NULL,
                        chunk_z INTEGER NOT NULL,
                        unlock_order INTEGER NOT NULL,
                        bought SMALLINT NOT NULL DEFAULT 0,
                        unlocked_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (island_id, dimension, chunk_x, chunk_z),
                        CONSTRAINT uq_chunkblock_dimension_order UNIQUE (island_id, dimension, unlock_order),
                        CONSTRAINT fk_chunkblock_dimension_claim_island FOREIGN KEY (island_id)
                            REFERENCES islands (id) ON DELETE CASCADE
                    );
                    CREATE TABLE IF NOT EXISTS chunkblock_dimension_payments (
                        island_id VARCHAR(36) NOT NULL,
                        dimension VARCHAR(32) NOT NULL,
                        chunk_x INTEGER NOT NULL,
                        chunk_z INTEGER NOT NULL,
                        line_key VARCHAR(160) NOT NULL,
                        paid BIGINT NOT NULL,
                        PRIMARY KEY (island_id, dimension, chunk_x, chunk_z, line_key),
                        CONSTRAINT fk_chunkblock_dimension_payment_island FOREIGN KEY (island_id)
                            REFERENCES islands (id) ON DELETE CASCADE
                    );
                    """);
            case H2, GENERIC -> throw new IllegalArgumentException("Unsupported dialect for migration V72: " + dialect);
        };
    }
}
