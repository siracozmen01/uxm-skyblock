package com.uxplima.uxmskyblock.persistence.boxed;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** How far each Boxed box was moved is kept on SQLite. */
class SqlBoxOffsetsTest {

    @Test
    @DisplayName("SQLite: a moved box is kept, moved again over its row, and goes with its island")
    void theOffsetsAreKept() throws Exception {
        Database database = DatabaseTestFixture.createSqliteInMemory();
        try {
            new MigrationRunner(database).apply(SkyblockMigrations.getMigrations(database.dialect()));
            BoxOffsetsStorageScenario.run(database);
        } finally {
            database.close();
        }
    }
}
