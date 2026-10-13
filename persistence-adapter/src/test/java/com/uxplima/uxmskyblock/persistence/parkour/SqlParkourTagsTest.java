package com.uxplima.uxmskyblock.persistence.parkour;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Each Parkour course's tags and its runners' stars are kept on SQLite. */
class SqlParkourTagsTest {

    @Test
    @DisplayName("SQLite: tags are replaced, a runner rates once, and both go with the course")
    void theTagsAndStarsAreKept() throws Exception {
        Database database = DatabaseTestFixture.createSqliteInMemory();
        try {
            new MigrationRunner(database).apply(SkyblockMigrations.getMigrations(database.dialect()));
            ParkourTagsStorageScenario.run(database);
        } finally {
            database.close();
        }
    }
}
