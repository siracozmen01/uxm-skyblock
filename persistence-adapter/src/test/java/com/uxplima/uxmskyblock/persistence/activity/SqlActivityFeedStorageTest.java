package com.uxplima.uxmskyblock.persistence.activity;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** An island's feed is paged, narrowed and added up on SQLite. */
class SqlActivityFeedStorageTest {

    @Test
    @DisplayName(
            "SQLite: a feed is read a page at a time, narrowed by member, kind and time, and its bank lines add up")
    void theFeedIsReadAndAddedUp() throws Exception {
        Database database = DatabaseTestFixture.createSqliteInMemory();
        try {
            new MigrationRunner(database).apply(SkyblockMigrations.getMigrations(database.dialect()));
            ActivityFeedStorageScenario.run(database);
        } finally {
            database.close();
        }
    }
}
