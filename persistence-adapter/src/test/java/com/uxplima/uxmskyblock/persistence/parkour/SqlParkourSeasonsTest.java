package com.uxplima.uxmskyblock.persistence.parkour;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The Parkour season times are kept on SQLite. */
class SqlParkourSeasonsTest {

    @Test
    @DisplayName("SQLite: each season keeps its own best times, and a reset forgets them")
    void theCoursesAreListed() throws Exception {
        Database database = DatabaseTestFixture.createSqliteInMemory();
        try {
            new MigrationRunner(database).apply(SkyblockMigrations.getMigrations(database.dialect()));
            ParkourSeasonsStorageScenario.run(database);
        } finally {
            database.close();
        }
    }
}
