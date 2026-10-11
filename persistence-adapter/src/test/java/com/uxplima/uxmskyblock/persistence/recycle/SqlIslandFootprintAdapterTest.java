package com.uxplima.uxmskyblock.persistence.recycle;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Where each island wrote past its border is kept on SQLite until the island is cleared. */
class SqlIslandFootprintAdapterTest {

    @Test
    @DisplayName("SQLite: a square widens, never narrows, and is forgotten with its island only")
    void theSquaresAreKept() {
        Database database = DatabaseTestFixture.createSqliteInMemory();
        try {
            new MigrationRunner(database).apply(SkyblockMigrations.getMigrations(database.dialect()));
            IslandFootprintScenario.run(database);
        } finally {
            database.close();
        }
    }
}
