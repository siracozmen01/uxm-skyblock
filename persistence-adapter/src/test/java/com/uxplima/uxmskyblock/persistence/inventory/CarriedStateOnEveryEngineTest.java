package com.uxplima.uxmskyblock.persistence.inventory;

import com.uxplima.uxmlib.storage.sql.Dialect;
import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;
import com.uxplima.uxmskyblock.persistence.testfixture.EnabledIfMariaDb;
import com.uxplima.uxmskyblock.persistence.testfixture.EnabledIfMySql;
import com.uxplima.uxmskyblock.persistence.testfixture.EnabledIfPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/** A crossing between groups of worlds on every server engine a customer runs; SQLite runs in the fast lane. */
@Tag("database-integration")
@Execution(ExecutionMode.SAME_THREAD)
@SuppressWarnings("NullAway")
class CarriedStateOnEveryEngineTest {

    private static MariaDBContainer<?> mariaDbContainer;
    private static MySQLContainer<?> mySqlContainer;
    private static PostgreSQLContainer<?> postgresContainer;

    @BeforeAll
    static void startEngines() {
        mariaDbContainer = DatabaseTestFixture.startMariaDbIfEnabled();
        mySqlContainer = DatabaseTestFixture.startMySqlIfEnabled();
        postgresContainer = DatabaseTestFixture.startPostgresIfEnabled();
    }

    @AfterAll
    static void stopEngines() {
        if (mariaDbContainer != null) {
            mariaDbContainer.stop();
        }
        if (mySqlContainer != null) {
            mySqlContainer.stop();
        }
        if (postgresContainer != null) {
            postgresContainer.stop();
        }
    }

    @Test
    @EnabledIfMariaDb
    @DisplayName("MariaDB: a crossing keeps what was put down, writes what is picked up, and refuses stale writes")
    void mariaDb() {
        CarriedStateCrossingScene.crossesOn(DatabaseTestFixture.connectToContainer(mariaDbContainer, Dialect.MYSQL));
    }

    @Test
    @EnabledIfMySql
    @DisplayName("MySQL: a crossing keeps what was put down, writes what is picked up, and refuses stale writes")
    void mySql() {
        CarriedStateCrossingScene.crossesOn(DatabaseTestFixture.connectToContainer(mySqlContainer, Dialect.MYSQL));
    }

    @Test
    @EnabledIfPostgres
    @DisplayName("PostgreSQL: a crossing keeps what was put down, writes what is picked up, and refuses stale writes")
    void postgres() {
        CarriedStateCrossingScene.crossesOn(
                DatabaseTestFixture.connectToContainer(postgresContainer, Dialect.POSTGRES));
    }
}
