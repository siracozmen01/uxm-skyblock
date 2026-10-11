package com.uxplima.uxmskyblock.persistence.boundary;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** How each island draws its edge is kept on SQLite. */
class SqlIslandEdgeStorageTest {

    @Test
    @DisplayName("SQLite: an island's edge style is kept, replaced, read all at once, and goes with its island")
    void theEdgeIsKept() throws Exception {
        Database database = DatabaseTestFixture.createSqliteInMemory();
        try {
            new MigrationRunner(database).apply(SkyblockMigrations.getMigrations(database.dialect()));
            IslandEdgeStorageScenario.run(database);
        } finally {
            database.close();
        }
    }
}
