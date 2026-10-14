package com.uxplima.uxmskyblock.persistence.brix;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The Brix build contests are kept on SQLite. */
class SqlBrixContestsTest {

    @Test
    @DisplayName("SQLite: a contest, its entries, votes and payouts are kept, and go with it")
    void theContestsAreKept() throws Exception {
        Database database = DatabaseTestFixture.createSqliteInMemory();
        try {
            new MigrationRunner(database).apply(SkyblockMigrations.getMigrations(database.dialect()));
            BrixContestsStorageScenario.run(database);
        } finally {
            database.close();
        }
    }
}
