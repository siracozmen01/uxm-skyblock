package com.uxplima.uxmskyblock.persistence.tradewinds;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The contracts each TradeWinds vessel took are kept on SQLite. */
class SqlContractsTest {

    @Test
    @DisplayName(
            "SQLite: a contract is taken once, delivered with its hold under the lease, fails once, and goes with its vessel")
    void theContractsAreKept() throws Exception {
        Database database = DatabaseTestFixture.createSqliteInMemory();
        try {
            new MigrationRunner(database).apply(SkyblockMigrations.getMigrations(database.dialect()));
            ContractsStorageScenario.run(database);
        } finally {
            database.close();
        }
    }
}
