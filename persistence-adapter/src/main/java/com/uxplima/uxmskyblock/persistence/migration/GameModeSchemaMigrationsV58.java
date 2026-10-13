package com.uxplima.uxmskyblock.persistence.migration;

import static com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations.V58_DESCRIPTION;

import com.uxplima.uxmlib.storage.migration.Migration;
import com.uxplima.uxmlib.storage.sql.Dialect;

/**
 * V58: the push past trade left on each good of each TradeWinds port.
 *
 * <p>One row for each good of each port that was traded, holding items sold there less items bought, in
 * thousandths, as of when it was written, and how many times it was written, so two nodes never write over each
 * other unseen.
 */
final class GameModeSchemaMigrationsV58 {

    private GameModeSchemaMigrationsV58() {}

    static Migration migration(Dialect dialect) {
        return switch (dialect) {
            case SQLITE -> new Migration(58, V58_DESCRIPTION, """
                    CREATE TABLE IF NOT EXISTS tradewinds_prices (
                        port_id VARCHAR(64) NOT NULL,
                        item VARCHAR(128) NOT NULL,
                        pressure_milli BIGINT NOT NULL,
                        at_millis BIGINT NOT NULL,
                        version BIGINT NOT NULL,
                        PRIMARY KEY (port_id, item)
                    );
                    """);
            case MYSQL -> new Migration(58, V58_DESCRIPTION, """
                    CREATE TABLE IF NOT EXISTS tradewinds_prices (
                        port_id VARCHAR(64) NOT NULL,
                        item VARCHAR(128) NOT NULL,
                        pressure_milli BIGINT NOT NULL,
                        at_millis BIGINT NOT NULL,
                        version BIGINT NOT NULL,
                        PRIMARY KEY (port_id, item)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                    """);
            case POSTGRES -> new Migration(58, V58_DESCRIPTION, """
                    CREATE TABLE IF NOT EXISTS tradewinds_prices (
                        port_id VARCHAR(64) NOT NULL,
                        item VARCHAR(128) NOT NULL,
                        pressure_milli BIGINT NOT NULL,
                        at_millis BIGINT NOT NULL,
                        version BIGINT NOT NULL,
                        PRIMARY KEY (port_id, item)
                    );
                    """);
            case H2, GENERIC -> throw new IllegalArgumentException("Unsupported dialect for migration V58: " + dialect);
        };
    }
}
