package com.uxplima.uxmskyblock.persistence.migration;

import static com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations.V71_DESCRIPTION;

import com.uxplima.uxmlib.storage.migration.Migration;
import com.uxplima.uxmlib.storage.sql.Dialect;

/**
 * V71: Brix build contests: each contest with its theme and its windows, the plots entered, the stars each voter gave
 * each plot, and what each member of a placed plot is owed, kept until they are paid.
 *
 * <p>An entry goes with its contest and with its plot; a vote goes with its entry; a payout goes with its contest.
 */
final class GameModeSchemaMigrationsV71 {

    private GameModeSchemaMigrationsV71() {}

    static Migration migration(Dialect dialect) {
        return switch (dialect) {
            case SQLITE -> new Migration(71, V71_DESCRIPTION, """
                    CREATE TABLE IF NOT EXISTS brix_contests (
                        contest_number INTEGER NOT NULL,
                        theme TEXT NOT NULL,
                        opens_at INTEGER NOT NULL,
                        builds_until INTEGER NOT NULL,
                        votes_until INTEGER NOT NULL,
                        settled INTEGER NOT NULL,
                        PRIMARY KEY (contest_number)
                    );
                    CREATE TABLE IF NOT EXISTS brix_contest_entries (
                        contest_number INTEGER NOT NULL,
                        island_id TEXT NOT NULL,
                        PRIMARY KEY (contest_number, island_id),
                        CONSTRAINT fk_brix_contest_entries_contest FOREIGN KEY (contest_number)
                            REFERENCES brix_contests (contest_number) ON DELETE CASCADE,
                        CONSTRAINT fk_brix_contest_entries_plot FOREIGN KEY (island_id)
                            REFERENCES brix_plots (island_id) ON DELETE CASCADE
                    );
                    CREATE TABLE IF NOT EXISTS brix_contest_votes (
                        contest_number INTEGER NOT NULL,
                        island_id TEXT NOT NULL,
                        voter_uuid TEXT NOT NULL,
                        stars INTEGER NOT NULL,
                        PRIMARY KEY (contest_number, island_id, voter_uuid),
                        CONSTRAINT fk_brix_contest_votes_entry FOREIGN KEY (contest_number, island_id)
                            REFERENCES brix_contest_entries (contest_number, island_id) ON DELETE CASCADE
                    );
                    CREATE TABLE IF NOT EXISTS brix_contest_payouts (
                        contest_number INTEGER NOT NULL,
                        island_id TEXT NOT NULL,
                        player_uuid TEXT NOT NULL,
                        place INTEGER NOT NULL,
                        paid INTEGER NOT NULL,
                        PRIMARY KEY (contest_number, island_id, player_uuid, place),
                        CONSTRAINT fk_brix_contest_payouts_contest FOREIGN KEY (contest_number)
                            REFERENCES brix_contests (contest_number) ON DELETE CASCADE
                    );
                    """);
            case MYSQL -> new Migration(71, V71_DESCRIPTION, """
                    CREATE TABLE IF NOT EXISTS brix_contests (
                        contest_number INT NOT NULL,
                        theme VARCHAR(128) NOT NULL,
                        opens_at BIGINT NOT NULL,
                        builds_until BIGINT NOT NULL,
                        votes_until BIGINT NOT NULL,
                        settled INT NOT NULL,
                        PRIMARY KEY (contest_number)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                    CREATE TABLE IF NOT EXISTS brix_contest_entries (
                        contest_number INT NOT NULL,
                        island_id VARCHAR(36) NOT NULL,
                        PRIMARY KEY (contest_number, island_id),
                        CONSTRAINT fk_brix_contest_entries_contest FOREIGN KEY (contest_number)
                            REFERENCES brix_contests (contest_number) ON DELETE CASCADE,
                        CONSTRAINT fk_brix_contest_entries_plot FOREIGN KEY (island_id)
                            REFERENCES brix_plots (island_id) ON DELETE CASCADE
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                    CREATE TABLE IF NOT EXISTS brix_contest_votes (
                        contest_number INT NOT NULL,
                        island_id VARCHAR(36) NOT NULL,
                        voter_uuid VARCHAR(36) NOT NULL,
                        stars INT NOT NULL,
                        PRIMARY KEY (contest_number, island_id, voter_uuid),
                        CONSTRAINT fk_brix_contest_votes_entry FOREIGN KEY (contest_number, island_id)
                            REFERENCES brix_contest_entries (contest_number, island_id) ON DELETE CASCADE
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                    CREATE TABLE IF NOT EXISTS brix_contest_payouts (
                        contest_number INT NOT NULL,
                        island_id VARCHAR(36) NOT NULL,
                        player_uuid VARCHAR(36) NOT NULL,
                        place INT NOT NULL,
                        paid INT NOT NULL,
                        PRIMARY KEY (contest_number, island_id, player_uuid, place),
                        CONSTRAINT fk_brix_contest_payouts_contest FOREIGN KEY (contest_number)
                            REFERENCES brix_contests (contest_number) ON DELETE CASCADE
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                    """);
            case POSTGRES -> new Migration(71, V71_DESCRIPTION, """
                    CREATE TABLE IF NOT EXISTS brix_contests (
                        contest_number INT NOT NULL,
                        theme VARCHAR(128) NOT NULL,
                        opens_at BIGINT NOT NULL,
                        builds_until BIGINT NOT NULL,
                        votes_until BIGINT NOT NULL,
                        settled INT NOT NULL,
                        PRIMARY KEY (contest_number)
                    );
                    CREATE TABLE IF NOT EXISTS brix_contest_entries (
                        contest_number INT NOT NULL,
                        island_id VARCHAR(36) NOT NULL,
                        PRIMARY KEY (contest_number, island_id),
                        CONSTRAINT fk_brix_contest_entries_contest FOREIGN KEY (contest_number)
                            REFERENCES brix_contests (contest_number) ON DELETE CASCADE,
                        CONSTRAINT fk_brix_contest_entries_plot FOREIGN KEY (island_id)
                            REFERENCES brix_plots (island_id) ON DELETE CASCADE
                    );
                    CREATE TABLE IF NOT EXISTS brix_contest_votes (
                        contest_number INT NOT NULL,
                        island_id VARCHAR(36) NOT NULL,
                        voter_uuid VARCHAR(36) NOT NULL,
                        stars INT NOT NULL,
                        PRIMARY KEY (contest_number, island_id, voter_uuid),
                        CONSTRAINT fk_brix_contest_votes_entry FOREIGN KEY (contest_number, island_id)
                            REFERENCES brix_contest_entries (contest_number, island_id) ON DELETE CASCADE
                    );
                    CREATE TABLE IF NOT EXISTS brix_contest_payouts (
                        contest_number INT NOT NULL,
                        island_id VARCHAR(36) NOT NULL,
                        player_uuid VARCHAR(36) NOT NULL,
                        place INT NOT NULL,
                        paid INT NOT NULL,
                        PRIMARY KEY (contest_number, island_id, player_uuid, place),
                        CONSTRAINT fk_brix_contest_payouts_contest FOREIGN KEY (contest_number)
                            REFERENCES brix_contests (contest_number) ON DELETE CASCADE
                    );
                    """);
            case H2, GENERIC -> throw new IllegalArgumentException("Unsupported dialect for migration V71: " + dialect);
        };
    }
}
