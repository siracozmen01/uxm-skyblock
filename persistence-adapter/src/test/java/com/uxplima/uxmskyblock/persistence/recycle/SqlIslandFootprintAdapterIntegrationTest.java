package com.uxplima.uxmskyblock.persistence.recycle;

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

/** Where each island wrote past its border is kept on every server database until the island is cleared. */
@Tag("database-integration")
@Execution(ExecutionMode.SAME_THREAD)
@SuppressWarnings("NullAway")
class SqlIslandFootprintAdapterIntegrationTest {

    private static void on(JdbcDatabaseContainer<?> container, Dialect dialect) {
        try {
            Database database = DatabaseTestFixture.connectToContainer(container, dialect);
            try {
                new MigrationRunner(database).apply(SkyblockMigrations.getMigrations(database.dialect()));
                IslandFootprintScenario.run(database);
            } finally {
                database.close();
            }
        } finally {
            container.stop();
        }
    }

    @Test
    @com.uxplima.uxmskyblock.persistence.testfixture.EnabledIfMariaDb
    @DisplayName("MariaDB: a square widens, never narrows, and is forgotten with its island only")
    void mariaDb() {
        on(DatabaseTestFixture.startMariaDbIfEnabled(), Dialect.MYSQL);
    }

    @Test
    @com.uxplima.uxmskyblock.persistence.testfixture.EnabledIfMySql
    @DisplayName("MySQL: a square widens, never narrows, and is forgotten with its island only")
    void mySql() {
        on(DatabaseTestFixture.startMySqlIfEnabled(), Dialect.MYSQL);
    }

    @Test
    @com.uxplima.uxmskyblock.persistence.testfixture.EnabledIfPostgres
    @DisplayName("PostgreSQL: a square widens, never narrows, and is forgotten with its island only")
    void postgres() {
        on(DatabaseTestFixture.startPostgresIfEnabled(), Dialect.POSTGRES);
    }
}
