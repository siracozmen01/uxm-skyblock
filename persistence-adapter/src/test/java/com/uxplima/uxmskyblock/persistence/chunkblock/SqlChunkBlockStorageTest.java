package com.uxplima.uxmskyblock.persistence.chunkblock;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A ChunkBlock island's chunks, what is paid toward them and its chunk points are kept on SQLite. */
class SqlChunkBlockStorageTest {

    @Test
    @DisplayName("SQLite: a chunk paid for reads back so, closing renumbers, payments add up, points never go below")
    void theChunksAndPaymentsAreKept() throws Exception {
        Database database = DatabaseTestFixture.createSqliteInMemory();
        try {
            new MigrationRunner(database).apply(SkyblockMigrations.getMigrations(database.dialect()));
            ChunkBlockStorageScenario.run(database);
        } finally {
            database.close();
        }
    }
}
