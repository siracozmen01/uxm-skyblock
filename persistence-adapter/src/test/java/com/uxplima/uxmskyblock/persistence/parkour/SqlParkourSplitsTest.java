package com.uxplima.uxmskyblock.persistence.parkour;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The split times of each runner's best Parkour run are kept on SQLite. */
class SqlParkourSplitsTest {

    @Test
    @DisplayName("SQLite: a new best's splits replace the old ones, and they go with the record")
    void theSplitsAreKept() throws Exception {
        Database database = DatabaseTestFixture.createSqliteInMemory();
        try {
            new MigrationRunner(database).apply(SkyblockMigrations.getMigrations(database.dialect()));
            ParkourSplitsStorageScenario.run(database);
        } finally {
            database.close();
        }
    }
}
