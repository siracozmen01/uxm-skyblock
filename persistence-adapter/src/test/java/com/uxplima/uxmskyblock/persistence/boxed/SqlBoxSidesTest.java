package com.uxplima.uxmskyblock.persistence.boxed;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The side each Boxed box grows toward is kept on SQLite. */
class SqlBoxSidesTest {

    @Test
    @DisplayName(
            "SQLite: a side is kept and chosen again over its row, purchases are counted, and both go with the island")
    void theSidesAreKept() throws Exception {
        Database database = DatabaseTestFixture.createSqliteInMemory();
        try {
            new MigrationRunner(database).apply(SkyblockMigrations.getMigrations(database.dialect()));
            BoxSidesStorageScenario.run(database);
        } finally {
            database.close();
        }
    }
}
