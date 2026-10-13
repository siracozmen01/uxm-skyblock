package com.uxplima.uxmskyblock.persistence.session;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The players on the network are counted on SQLite. */
class SqlNetworkPresenceTest {

    @TempDir
    java.nio.file.Path dir;

    @Test
    @DisplayName("SQLite: the players on the network are those with a live session, on its way or settled")
    void thePlayersAreCounted() throws Exception {
        // A file every connection shares, as the session tests use: the session writer takes a second connection.
        Database database = DatabaseTestFixture.createSqliteFile(dir.resolve("presence.db"));
        try {
            new MigrationRunner(database).apply(SkyblockMigrations.getMigrations(database.dialect()));
            NetworkPresenceScenario.run(database);
        } finally {
            database.close();
        }
    }
}
