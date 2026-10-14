package com.uxplima.uxmskyblock.persistence.migration;

import static com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations.V70_DESCRIPTION;

import com.uxplima.uxmlib.storage.migration.Migration;
import com.uxplima.uxmlib.storage.sql.Dialect;

/**
 * V70: the time and weather each Brix plot's team set for everyone standing on it.
 *
 * <p>One row per plot whose sky is not its world's: the time in ticks, or -1 for the world's, and the weather. The
 * row goes with the plot.
 */
final class GameModeSchemaMigrationsV70 {

    private GameModeSchemaMigrationsV70() {}

    static Migration migration(Dialect dialect) {
        return switch (dialect) {
            case SQLITE -> new Migration(70, V70_DESCRIPTION, """
                    CREATE TABLE IF NOT EXISTS brix_plot_skies (
                        island_id TEXT NOT NULL,
                        plot_time INTEGER NOT NULL,
                        plot_weather TEXT NOT NULL,
                        PRIMARY KEY (island_id),
                        CONSTRAINT fk_brix_plot_skies_plot FOREIGN KEY (island_id)
                            REFERENCES brix_plots (island_id) ON DELETE CASCADE
                    );
                    """);
            case MYSQL -> new Migration(70, V70_DESCRIPTION, """
                    CREATE TABLE IF NOT EXISTS brix_plot_skies (
                        island_id VARCHAR(36) NOT NULL,
                        plot_time INT NOT NULL,
                        plot_weather VARCHAR(8) NOT NULL,
                        PRIMARY KEY (island_id),
                        CONSTRAINT fk_brix_plot_skies_plot FOREIGN KEY (island_id)
                            REFERENCES brix_plots (island_id) ON DELETE CASCADE
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                    """);
            case POSTGRES -> new Migration(70, V70_DESCRIPTION, """
                    CREATE TABLE IF NOT EXISTS brix_plot_skies (
                        island_id VARCHAR(36) NOT NULL,
                        plot_time INT NOT NULL,
                        plot_weather VARCHAR(8) NOT NULL,
                        PRIMARY KEY (island_id),
                        CONSTRAINT fk_brix_plot_skies_plot FOREIGN KEY (island_id)
                            REFERENCES brix_plots (island_id) ON DELETE CASCADE
                    );
                    """);
            case H2, GENERIC -> throw new IllegalArgumentException("Unsupported dialect for migration V70: " + dialect);
        };
    }
}
