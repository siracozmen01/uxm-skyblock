package com.uxplima.uxmskyblock.persistence.parkour;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Each runner's best Parkour run is kept as a ghost on SQLite. */
class SqlParkourGhostsTest {

    @Test
    @DisplayName("SQLite: a faster run replaces the ghost, a slower one leaves it, and it goes with the record")
    void theGhostsAreKept() throws Exception {
        Database database = DatabaseTestFixture.createSqliteInMemory();
        try {
            new MigrationRunner(database).apply(SkyblockMigrations.getMigrations(database.dialect()));
            ParkourGhostsStorageScenario.run(database);
        } finally {
            database.close();
        }
    }
}
