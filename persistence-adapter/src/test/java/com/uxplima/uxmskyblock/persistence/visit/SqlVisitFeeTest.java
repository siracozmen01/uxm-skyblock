package com.uxplima.uxmskyblock.persistence.visit;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The visit fees are kept on SQLite. */
class SqlVisitFeeTest {

    @Test
    @DisplayName("SQLite: each island keeps its own visit fee, set again, forgotten at zero and with the island")
    void kept() throws Exception {
        Database database = DatabaseTestFixture.createSqliteInMemory();
        try {
            new MigrationRunner(database).apply(SkyblockMigrations.getMigrations(database.dialect()));
            VisitFeeStorageScenario.run(database);
        } finally {
            database.close();
        }
    }
}
