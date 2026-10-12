package com.uxplima.uxmskyblock.persistence.activity;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmlib.storage.sql.Dialect;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.testcontainers.containers.JdbcDatabaseContainer;

/** An island's feed is paged, narrowed and added up on every server database. */
@Tag("database-integration")
@Execution(ExecutionMode.SAME_THREAD)
@SuppressWarnings("NullAway")
class SqlActivityFeedStorageIntegrationTest {

    private static void on(JdbcDatabaseContainer<?> container, Dialect dialect) throws Exception {
        try {
            Database database = DatabaseTestFixture.connectToContainer(container, dialect);
            try {
                new MigrationRunner(database).apply(SkyblockMigrations.getMigrations(database.dialect()));
                ActivityFeedStorageScenario.run(database);
            } finally {
                database.close();
            }
        } finally {
            container.stop();
        }
    }

    @Test
    @com.uxplima.uxmskyblock.persistence.testfixture.EnabledIfMariaDb
    @DisplayName(
            "MariaDB: a feed is read a page at a time, narrowed by member, kind and time, and its bank lines add up")
    void mariaDb() throws Exception {
        on(DatabaseTestFixture.startMariaDbIfEnabled(), Dialect.MYSQL);
    }

    @Test
    @com.uxplima.uxmskyblock.persistence.testfixture.EnabledIfMySql
    @DisplayName("MySQL: a feed is read a page at a time, narrowed by member, kind and time, and its bank lines add up")
    void mySql() throws Exception {
        on(DatabaseTestFixture.startMySqlIfEnabled(), Dialect.MYSQL);
    }

    @Test
    @com.uxplima.uxmskyblock.persistence.testfixture.EnabledIfPostgres
    @DisplayName(
            "PostgreSQL: a feed is read a page at a time, narrowed by member, kind and time, and its bank lines add up")
    void postgres() throws Exception {
        on(DatabaseTestFixture.startPostgresIfEnabled(), Dialect.POSTGRES);
    }
}
