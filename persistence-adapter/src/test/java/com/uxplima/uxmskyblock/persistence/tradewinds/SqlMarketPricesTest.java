package com.uxplima.uxmskyblock.persistence.tradewinds;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The push trade left on each TradeWinds price is kept on SQLite. */
class SqlMarketPricesTest {

    @Test
    @DisplayName("SQLite: a push reads back, is written over its own version only, and each good stands apart")
    void thePricesAreKept() throws Exception {
        Database database = DatabaseTestFixture.createSqliteInMemory();
        try {
            new MigrationRunner(database).apply(SkyblockMigrations.getMigrations(database.dialect()));
            MarketPricesStorageScenario.run(database);
        } finally {
            database.close();
        }
    }
}
