package com.uxplima.uxmskyblock.persistence.migration;

import static com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations.V61_DESCRIPTION;

import com.uxplima.uxmlib.storage.migration.Migration;
import com.uxplima.uxmlib.storage.sql.Dialect;

/**
 * V61: every TradeWinds plunder at sea.
 *
 * <p>One row for each plunder: the vessel whose crew took, the vessel they took from, when, and how many items
 * changed holds. The rows are the log an operator reads and the cooldown each vessel waits out. A vessel that is
 * gone takes its rows with it.
 */
final class GameModeSchemaMigrationsV61 {

    private GameModeSchemaMigrationsV61() {}

    static Migration migration(Dialect dialect) {
        return switch (dialect) {
            case SQLITE -> new Migration(61, V61_DESCRIPTION, """
                    CREATE TABLE IF NOT EXISTS tradewinds_plunders (
                        raider_id TEXT NOT NULL,
                        target_id TEXT NOT NULL,
                        at_millis BIGINT NOT NULL,
                        items INTEGER NOT NULL,
                        PRIMARY KEY (raider_id, at_millis),
                        CONSTRAINT fk_tradewinds_plunders_raider FOREIGN KEY (raider_id)
                            REFERENCES tradewinds_vessels (island_id) ON DELETE CASCADE,
                        CONSTRAINT fk_tradewinds_plunders_target FOREIGN KEY (target_id)
                            REFERENCES tradewinds_vessels (island_id) ON DELETE CASCADE
                    );
                    CREATE INDEX IF NOT EXISTS idx_tradewinds_plunders_target ON tradewinds_plunders (target_id, at_millis);
                    """);
            case MYSQL -> new Migration(61, V61_DESCRIPTION, """
                    CREATE TABLE IF NOT EXISTS tradewinds_plunders (
                        raider_id VARCHAR(36) NOT NULL,
                        target_id VARCHAR(36) NOT NULL,
                        at_millis BIGINT NOT NULL,
                        items INTEGER NOT NULL,
                        PRIMARY KEY (raider_id, at_millis),
                        CONSTRAINT fk_tradewinds_plunders_raider FOREIGN KEY (raider_id)
                            REFERENCES tradewinds_vessels (island_id) ON DELETE CASCADE,
                        CONSTRAINT fk_tradewinds_plunders_target FOREIGN KEY (target_id)
                            REFERENCES tradewinds_vessels (island_id) ON DELETE CASCADE
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                    CREATE INDEX idx_tradewinds_plunders_target ON tradewinds_plunders (target_id, at_millis);
                    """);
            case POSTGRES -> new Migration(61, V61_DESCRIPTION, """
                    CREATE TABLE IF NOT EXISTS tradewinds_plunders (
                        raider_id VARCHAR(36) NOT NULL,
                        target_id VARCHAR(36) NOT NULL,
                        at_millis BIGINT NOT NULL,
                        items INTEGER NOT NULL,
                        PRIMARY KEY (raider_id, at_millis),
                        CONSTRAINT fk_tradewinds_plunders_raider FOREIGN KEY (raider_id)
                            REFERENCES tradewinds_vessels (island_id) ON DELETE CASCADE,
                        CONSTRAINT fk_tradewinds_plunders_target FOREIGN KEY (target_id)
                            REFERENCES tradewinds_vessels (island_id) ON DELETE CASCADE
                    );
                    CREATE INDEX IF NOT EXISTS idx_tradewinds_plunders_target ON tradewinds_plunders (target_id, at_millis);
                    """);
            case H2, GENERIC -> throw new IllegalArgumentException("Unsupported dialect for migration V61: " + dialect);
        };
    }
}
