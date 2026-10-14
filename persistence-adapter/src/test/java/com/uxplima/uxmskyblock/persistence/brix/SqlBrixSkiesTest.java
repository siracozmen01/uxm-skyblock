package com.uxplima.uxmskyblock.persistence.brix;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The Brix plots' skies are kept on SQLite. */
class SqlBrixSkiesTest {

    @Test
    @DisplayName("SQLite: a plot's time and weather are kept, cleared to the world's, and go with the plot")
    void theSkiesAreKept() throws Exception {
        Database database = DatabaseTestFixture.createSqliteInMemory();
        try {
            new MigrationRunner(database).apply(SkyblockMigrations.getMigrations(database.dialect()));
            BrixSkiesStorageScenario.run(database);
        } finally {
            database.close();
        }
    }
}
