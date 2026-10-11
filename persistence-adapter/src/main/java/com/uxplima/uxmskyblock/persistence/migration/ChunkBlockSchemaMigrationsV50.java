package com.uxplima.uxmskyblock.persistence.migration;

import static com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations.V50_DESCRIPTION;

import com.uxplima.uxmlib.storage.migration.Migration;
import com.uxplima.uxmlib.storage.sql.Dialect;

/**
 * V50: a ChunkBlock chunk is paid for, a part at a time, and one paid for stays open.
 *
 * <p>A chunk was opened by the island's level alone and closed again when the level fell. A chunk can now cost money,
 * a currency, items and chunk points, paid by anybody of the island a part at a time: what is paid toward each closed
 * chunk is kept line by line until it opens. A claim says whether it was paid for, and one that was never closes.
 * Each island keeps its chunk points. Claims written before this were opened by level, and read so.
 */
final class ChunkBlockSchemaMigrationsV50 {

    private ChunkBlockSchemaMigrationsV50() {}

    static Migration migration(Dialect dialect) {
        return switch (dialect) {
            case SQLITE -> new Migration(50, V50_DESCRIPTION, """
                    ALTER TABLE chunkblock_territory_claims ADD COLUMN bought INTEGER NOT NULL DEFAULT 0;
                    CREATE TABLE chunkblock_chunk_payments (
                        island_id TEXT NOT NULL,
                        chunk_x INTEGER NOT NULL,
                        chunk_z INTEGER NOT NULL,
                        line_key TEXT NOT NULL,
                        paid INTEGER NOT NULL,
                        PRIMARY KEY (island_id, chunk_x, chunk_z, line_key),
                        CONSTRAINT fk_chunkblock_payment_island FOREIGN KEY (island_id)
                            REFERENCES islands (id) ON DELETE CASCADE
                    );
                    CREATE TABLE chunkblock_points (
                        island_id TEXT NOT NULL PRIMARY KEY,
                        points INTEGER NOT NULL,
                        CONSTRAINT fk_chunkblock_points_island FOREIGN KEY (island_id)
                            REFERENCES islands (id) ON DELETE CASCADE
                    );
                    """);
            case MYSQL -> new Migration(50, V50_DESCRIPTION, """
                    ALTER TABLE chunkblock_territory_claims ADD COLUMN bought SMALLINT NOT NULL DEFAULT 0;
                    CREATE TABLE chunkblock_chunk_payments (
                        island_id VARCHAR(36) NOT NULL,
                        chunk_x INT NOT NULL,
                        chunk_z INT NOT NULL,
                        line_key VARCHAR(160) NOT NULL,
                        paid BIGINT NOT NULL,
                        PRIMARY KEY (island_id, chunk_x, chunk_z, line_key),
                        CONSTRAINT fk_chunkblock_payment_island FOREIGN KEY (island_id)
                            REFERENCES islands (id) ON DELETE CASCADE
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                    CREATE TABLE chunkblock_points (
                        island_id VARCHAR(36) NOT NULL PRIMARY KEY,
                        points BIGINT NOT NULL,
                        CONSTRAINT fk_chunkblock_points_island FOREIGN KEY (island_id)
                            REFERENCES islands (id) ON DELETE CASCADE
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                    """);
            case POSTGRES -> new Migration(50, V50_DESCRIPTION, """
                    ALTER TABLE chunkblock_territory_claims ADD COLUMN bought SMALLINT NOT NULL DEFAULT 0;
                    CREATE TABLE chunkblock_chunk_payments (
                        island_id VARCHAR(36) NOT NULL,
                        chunk_x INTEGER NOT NULL,
                        chunk_z INTEGER NOT NULL,
                        line_key VARCHAR(160) NOT NULL,
                        paid BIGINT NOT NULL,
                        PRIMARY KEY (island_id, chunk_x, chunk_z, line_key),
                        CONSTRAINT fk_chunkblock_payment_island FOREIGN KEY (island_id)
                            REFERENCES islands (id) ON DELETE CASCADE
                    );
                    CREATE TABLE chunkblock_points (
                        island_id VARCHAR(36) NOT NULL PRIMARY KEY,
                        points BIGINT NOT NULL,
                        CONSTRAINT fk_chunkblock_points_island FOREIGN KEY (island_id)
                            REFERENCES islands (id) ON DELETE CASCADE
                    );
                    """);
            case H2, GENERIC -> throw new IllegalArgumentException("Unsupported dialect for migration V50: " + dialect);
        };
    }
}
