package com.uxplima.uxmskyblock.persistence.migration;

import static com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations.V60_DESCRIPTION;

import com.uxplima.uxmlib.storage.migration.Migration;
import com.uxplima.uxmlib.storage.sql.Dialect;

/**
 * V60: the events each TradeWinds voyage holds.
 *
 * <p>One row for each kind of event of each vessel at sea: the port its voyage is bound for and the moment it
 * happens. A row is taken away as its event happens, and a vessel that is gone takes its rows with it.
 */
final class GameModeSchemaMigrationsV60 {

    private GameModeSchemaMigrationsV60() {}

    static Migration migration(Dialect dialect) {
        return switch (dialect) {
            case SQLITE -> new Migration(60, V60_DESCRIPTION, """
                    CREATE TABLE IF NOT EXISTS tradewinds_voyage_events (
                        island_id TEXT NOT NULL,
                        hazard VARCHAR(16) NOT NULL,
                        port_id VARCHAR(64) NOT NULL,
                        at_millis BIGINT NOT NULL,
                        PRIMARY KEY (island_id, hazard),
                        CONSTRAINT fk_tradewinds_voyage_events_vessel FOREIGN KEY (island_id)
                            REFERENCES tradewinds_vessels (island_id) ON DELETE CASCADE
                    );
                    CREATE INDEX IF NOT EXISTS idx_tradewinds_voyage_events_at ON tradewinds_voyage_events (at_millis);
                    """);
            case MYSQL -> new Migration(60, V60_DESCRIPTION, """
                    CREATE TABLE IF NOT EXISTS tradewinds_voyage_events (
                        island_id VARCHAR(36) NOT NULL,
                        hazard VARCHAR(16) NOT NULL,
                        port_id VARCHAR(64) NOT NULL,
                        at_millis BIGINT NOT NULL,
                        PRIMARY KEY (island_id, hazard),
                        CONSTRAINT fk_tradewinds_voyage_events_vessel FOREIGN KEY (island_id)
                            REFERENCES tradewinds_vessels (island_id) ON DELETE CASCADE
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                    CREATE INDEX idx_tradewinds_voyage_events_at ON tradewinds_voyage_events (at_millis);
                    """);
            case POSTGRES -> new Migration(60, V60_DESCRIPTION, """
                    CREATE TABLE IF NOT EXISTS tradewinds_voyage_events (
                        island_id VARCHAR(36) NOT NULL,
                        hazard VARCHAR(16) NOT NULL,
                        port_id VARCHAR(64) NOT NULL,
                        at_millis BIGINT NOT NULL,
                        PRIMARY KEY (island_id, hazard),
                        CONSTRAINT fk_tradewinds_voyage_events_vessel FOREIGN KEY (island_id)
                            REFERENCES tradewinds_vessels (island_id) ON DELETE CASCADE
                    );
                    CREATE INDEX IF NOT EXISTS idx_tradewinds_voyage_events_at ON tradewinds_voyage_events (at_millis);
                    """);
            case H2, GENERIC -> throw new IllegalArgumentException("Unsupported dialect for migration V60: " + dialect);
        };
    }
}
