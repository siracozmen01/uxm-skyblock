package com.uxplima.uxmskyblock.persistence.parkour;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The Parkour courses are listed on SQLite. */
class SqlParkourDirectoryTest {

    @Test
    @DisplayName("SQLite: the most run first, with their name, owner, tags, rating, record and runs")
    void theCoursesAreListed() throws Exception {
        Database database = DatabaseTestFixture.createSqliteInMemory();
        try {
            new MigrationRunner(database).apply(SkyblockMigrations.getMigrations(database.dialect()));
            ParkourDirectoryStorageScenario.run(database);
        } finally {
            database.close();
        }
    }
}
