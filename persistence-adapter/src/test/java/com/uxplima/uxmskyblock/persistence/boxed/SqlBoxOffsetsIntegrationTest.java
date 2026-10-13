package com.uxplima.uxmskyblock.persistence.boxed;

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

/** How far each Boxed box was moved is kept on every server database. */
@Tag("database-integration")
@Execution(ExecutionMode.SAME_THREAD)
@SuppressWarnings("NullAway")
class SqlBoxOffsetsIntegrationTest {

    private static void on(JdbcDatabaseContainer<?> container, Dialect dialect) throws Exception {
        try {
            Database database = DatabaseTestFixture.connectToContainer(container, dialect);
            try {
                new MigrationRunner(database).apply(SkyblockMigrations.getMigrations(database.dialect()));
                BoxOffsetsStorageScenario.run(database);
            } finally {
                database.close();
            }
        } finally {
            container.stop();
        }
    }

    @Test
    @com.uxplima.uxmskyblock.persistence.testfixture.EnabledIfMariaDb
    @DisplayName("MariaDB: a moved box is kept, moved again over its row, and goes with its island")
    void mariaDb() throws Exception {
        on(DatabaseTestFixture.startMariaDbIfEnabled(), Dialect.MYSQL);
    }

    @Test
    @com.uxplima.uxmskyblock.persistence.testfixture.EnabledIfMySql
    @DisplayName("MySQL: a moved box is kept, moved again over its row, and goes with its island")
    void mySql() throws Exception {
        on(DatabaseTestFixture.startMySqlIfEnabled(), Dialect.MYSQL);
    }

    @Test
    @com.uxplima.uxmskyblock.persistence.testfixture.EnabledIfPostgres
    @DisplayName("PostgreSQL: a moved box is kept, moved again over its row, and goes with its island")
    void postgres() throws Exception {
        on(DatabaseTestFixture.startPostgresIfEnabled(), Dialect.POSTGRES);
    }
}
