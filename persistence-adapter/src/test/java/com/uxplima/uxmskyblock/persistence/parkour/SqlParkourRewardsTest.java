package com.uxplima.uxmskyblock.persistence.parkour;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Each Parkour reward list is paid once in its period on SQLite. */
class SqlParkourRewardsTest {

    @Test
    @DisplayName("SQLite: a list is paid once in its period, to each runner apart, and goes with the course")
    void theListsArePaidOnce() throws Exception {
        Database database = DatabaseTestFixture.createSqliteInMemory();
        try {
            new MigrationRunner(database).apply(SkyblockMigrations.getMigrations(database.dialect()));
            ParkourRewardsStorageScenario.run(database);
        } finally {
            database.close();
        }
    }
}
