package com.uxplima.uxmskyblock.persistence.migration;

import static com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations.V59_DESCRIPTION;

import com.uxplima.uxmlib.storage.migration.Migration;
import com.uxplima.uxmlib.storage.sql.Dialect;

/**
 * V59: the contracts each TradeWinds vessel took.
 *
 * <p>One row for each contract of each vessel, the last time it took it: the terms as they were when taken, who took
 * it, when it falls due and where it stands. A vessel that is gone takes its rows with it.
 */
final class GameModeSchemaMigrationsV59 {

    private GameModeSchemaMigrationsV59() {}

    static Migration migration(Dialect dialect) {
        return switch (dialect) {
            case SQLITE -> new Migration(59, V59_DESCRIPTION, """
                    CREATE TABLE IF NOT EXISTS tradewinds_contracts (
                        island_id TEXT NOT NULL,
                        contract_id VARCHAR(64) NOT NULL,
                        to_port VARCHAR(64) NOT NULL,
                        item VARCHAR(128) NOT NULL,
                        item_count INTEGER NOT NULL,
                        taker_uuid VARCHAR(36) NOT NULL,
                        due_millis BIGINT NOT NULL,
                        state VARCHAR(16) NOT NULL,
                        PRIMARY KEY (island_id, contract_id),
                        CONSTRAINT fk_tradewinds_contracts_vessel FOREIGN KEY (island_id)
                            REFERENCES tradewinds_vessels (island_id) ON DELETE CASCADE
                    );
                    CREATE INDEX IF NOT EXISTS idx_tradewinds_contracts_due ON tradewinds_contracts (state, due_millis);
                    """);
            case MYSQL -> new Migration(59, V59_DESCRIPTION, """
                    CREATE TABLE IF NOT EXISTS tradewinds_contracts (
                        island_id VARCHAR(36) NOT NULL,
                        contract_id VARCHAR(64) NOT NULL,
                        to_port VARCHAR(64) NOT NULL,
                        item VARCHAR(128) NOT NULL,
                        item_count INTEGER NOT NULL,
                        taker_uuid VARCHAR(36) NOT NULL,
                        due_millis BIGINT NOT NULL,
                        state VARCHAR(16) NOT NULL,
                        PRIMARY KEY (island_id, contract_id),
                        CONSTRAINT fk_tradewinds_contracts_vessel FOREIGN KEY (island_id)
                            REFERENCES tradewinds_vessels (island_id) ON DELETE CASCADE
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                    CREATE INDEX idx_tradewinds_contracts_due ON tradewinds_contracts (state, due_millis);
                    """);
            case POSTGRES -> new Migration(59, V59_DESCRIPTION, """
                    CREATE TABLE IF NOT EXISTS tradewinds_contracts (
                        island_id VARCHAR(36) NOT NULL,
                        contract_id VARCHAR(64) NOT NULL,
                        to_port VARCHAR(64) NOT NULL,
                        item VARCHAR(128) NOT NULL,
                        item_count INTEGER NOT NULL,
                        taker_uuid VARCHAR(36) NOT NULL,
                        due_millis BIGINT NOT NULL,
                        state VARCHAR(16) NOT NULL,
                        PRIMARY KEY (island_id, contract_id),
                        CONSTRAINT fk_tradewinds_contracts_vessel FOREIGN KEY (island_id)
                            REFERENCES tradewinds_vessels (island_id) ON DELETE CASCADE
                    );
                    CREATE INDEX IF NOT EXISTS idx_tradewinds_contracts_due ON tradewinds_contracts (state, due_millis);
                    """);
            case H2, GENERIC -> throw new IllegalArgumentException("Unsupported dialect for migration V59: " + dialect);
        };
    }
}
