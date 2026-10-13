package com.uxplima.uxmskyblock.persistence.boxed;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** How far each Boxed island's template land is copied is kept on SQLite. */
class SqlBoxedLandTest {

    @Test
    @DisplayName(
            "SQLite: an island on the template is marked once, its rings move only from what is kept, and go with it")
    void theLandIsKept() throws Exception {
        Database database = DatabaseTestFixture.createSqliteInMemory();
        try {
            new MigrationRunner(database).apply(SkyblockMigrations.getMigrations(database.dialect()));
            BoxedLandStorageScenario.run(database);
        } finally {
            database.close();
        }
    }
}
