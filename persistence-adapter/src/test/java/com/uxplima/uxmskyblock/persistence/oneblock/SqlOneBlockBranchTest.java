package com.uxplima.uxmskyblock.persistence.oneblock;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The phases OneBlock islands picked are kept on SQLite. */
class SqlOneBlockBranchTest {

    @Test
    @DisplayName("SQLite: a pick reads back, is written over, forgotten, and goes with its island")
    void thePicksAreKept() throws Exception {
        Database database = DatabaseTestFixture.createSqliteInMemory();
        try {
            new MigrationRunner(database).apply(SkyblockMigrations.getMigrations(database.dialect()));
            OneBlockBranchStorageScenario.run(database);
        } finally {
            database.close();
        }
    }
}
