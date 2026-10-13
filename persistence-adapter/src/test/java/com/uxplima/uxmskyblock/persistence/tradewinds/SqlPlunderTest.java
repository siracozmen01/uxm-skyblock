package com.uxplima.uxmskyblock.persistence.tradewinds;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The plunders at sea are written down on SQLite. */
class SqlPlunderTest {

    @Test
    @DisplayName(
            "SQLite: a plunder writes both holds and its row together, waits out the cooldown, and goes with either vessel")
    void thePlundersAreKept() throws Exception {
        Database database = DatabaseTestFixture.createSqliteInMemory();
        try {
            new MigrationRunner(database).apply(SkyblockMigrations.getMigrations(database.dialect()));
            PlunderStorageScenario.run(database);
        } finally {
            database.close();
        }
    }
}
