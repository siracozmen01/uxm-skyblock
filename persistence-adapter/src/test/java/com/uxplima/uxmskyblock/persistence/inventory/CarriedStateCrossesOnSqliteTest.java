package com.uxplima.uxmskyblock.persistence.inventory;

import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A crossing between groups of worlds on SQLite, the engine a server starts on. */
class CarriedStateCrossesOnSqliteTest {

    @Test
    @DisplayName("SQLite: a crossing keeps what was put down, writes what is picked up, and refuses stale writes")
    void sqlite() {
        CarriedStateCrossingScene.crossesOn(DatabaseTestFixture.createSqliteInMemory());
    }
}
