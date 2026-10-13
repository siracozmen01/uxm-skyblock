package com.uxplima.uxmskyblock.persistence.tradewinds;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The events each TradeWinds voyage holds are kept on SQLite. */
class SqlVoyageEventsTest {

    @Test
    @DisplayName("SQLite: an event is planned, taken once, a find stowed under the lease, and goes with its vessel")
    void theEventsAreKept() throws Exception {
        Database database = DatabaseTestFixture.createSqliteInMemory();
        try {
            new MigrationRunner(database).apply(SkyblockMigrations.getMigrations(database.dialect()));
            VoyageEventsStorageScenario.run(database);
        } finally {
            database.close();
        }
    }
}
