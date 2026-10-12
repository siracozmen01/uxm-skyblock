package com.uxplima.uxmskyblock.persistence.oneblock;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The OneBlock islands waiting before a phase are kept on SQLite. */
class SqlOneBlockWaitTest {

    @Test
    @DisplayName("SQLite: a wait reads back as written, is written over and ended, and goes with its island")
    void theWaitsAreKept() throws Exception {
        Database database = DatabaseTestFixture.createSqliteInMemory();
        try {
            new MigrationRunner(database).apply(SkyblockMigrations.getMigrations(database.dialect()));
            OneBlockWaitStorageScenario.run(database);
        } finally {
            database.close();
        }
    }
}
