package com.uxplima.uxmskyblock.persistence.oneblock;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What a OneBlock island has been paid is kept on SQLite. */
class SqlOneBlockRewardTest {

    @Test
    @DisplayName("SQLite: a reward is recorded once in each pass, never for an earlier one, and goes with its island")
    void theRewardsAreKept() throws Exception {
        Database database = DatabaseTestFixture.createSqliteInMemory();
        try {
            new MigrationRunner(database).apply(SkyblockMigrations.getMigrations(database.dialect()));
            OneBlockRewardStorageScenario.run(database);
        } finally {
            database.close();
        }
    }
}
