package com.uxplima.uxmskyblock.persistence.migration;

import static com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations.V52_DESCRIPTION;

import com.uxplima.uxmlib.storage.migration.Migration;
import com.uxplima.uxmlib.storage.sql.Dialect;

/**
 * V52: a feed line keeps who did it and what it moved.
 *
 * <p>A bank line kept only its sentence, with the player's name written into it. The feed could not be
 * narrowed to one member, and nobody could ask who put in and who took out how much. A line now keeps
 * the player beside the sentence, and a bank line the currency and the amount it moved. A line written
 * before this keeps neither, and is read as it always was.
 */
final class ActivityFeedSchemaMigrationsV52 {

    private ActivityFeedSchemaMigrationsV52() {}

    static Migration migration(Dialect dialect) {
        return switch (dialect) {
            case SQLITE -> new Migration(52, V52_DESCRIPTION, """
                    ALTER TABLE activity_events ADD COLUMN actor_player VARCHAR(36);
                    ALTER TABLE activity_events ADD COLUMN amount_currency VARCHAR(64);
                    ALTER TABLE activity_events ADD COLUMN amount_minor BIGINT;
                    CREATE INDEX IF NOT EXISTS idx_activity_events_player
                        ON activity_events (instance_id, actor_player, created_at);
                    """);
            case MYSQL -> new Migration(52, V52_DESCRIPTION, """
                    ALTER TABLE activity_events
                        ADD COLUMN actor_player VARCHAR(36) NULL,
                        ADD COLUMN amount_currency VARCHAR(64) NULL,
                        ADD COLUMN amount_minor BIGINT NULL,
                        ADD INDEX idx_activity_events_player (instance_id, actor_player, created_at);
                    """);
            case POSTGRES -> new Migration(52, V52_DESCRIPTION, """
                    ALTER TABLE activity_events
                        ADD COLUMN actor_player VARCHAR(36),
                        ADD COLUMN amount_currency VARCHAR(64),
                        ADD COLUMN amount_minor BIGINT;
                    CREATE INDEX IF NOT EXISTS idx_activity_events_player
                        ON activity_events (instance_id, actor_player, created_at);
                    """);
            case H2, GENERIC -> throw new IllegalArgumentException("Unsupported dialect for migration V52: " + dialect);
        };
    }
}
