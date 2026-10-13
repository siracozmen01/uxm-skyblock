package com.uxplima.uxmskyblock.persistence.oneblock;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The blocks each player broke on a OneBlock island are kept on SQLite. */
class SqlOneBlockContributionTest {

    @Test
    @DisplayName("SQLite: a player's breaks add up, are forgotten, and go with their island")
    void theBreaksAreKept() throws Exception {
        Database database = DatabaseTestFixture.createSqliteInMemory();
        try {
            new MigrationRunner(database).apply(SkyblockMigrations.getMigrations(database.dialect()));
            OneBlockContributionStorageScenario.run(database);
        } finally {
            database.close();
        }
    }
}
