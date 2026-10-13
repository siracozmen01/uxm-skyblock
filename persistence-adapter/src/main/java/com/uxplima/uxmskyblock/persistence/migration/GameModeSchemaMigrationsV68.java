package com.uxplima.uxmskyblock.persistence.migration;

import static com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations.V68_DESCRIPTION;

import com.uxplima.uxmlib.storage.migration.Migration;
import com.uxplima.uxmlib.storage.sql.Dialect;

/**
 * V68: each Parkour course's difficulty and category, and the stars its runners give it.
 *
 * <p>A course has one row of tags when its team gave any, each by the operator's name for it. Each runner who rated
 * a course has one row of stars there, the last they gave. Both go with the course.
 */
final class GameModeSchemaMigrationsV68 {

    private GameModeSchemaMigrationsV68() {}

    static Migration migration(Dialect dialect) {
        return switch (dialect) {
            case SQLITE -> new Migration(68, V68_DESCRIPTION, """
                    CREATE TABLE IF NOT EXISTS parkour_course_tags (
                        island_id TEXT NOT NULL PRIMARY KEY,
                        difficulty VARCHAR(32) NULL,
                        category VARCHAR(32) NULL,
                        CONSTRAINT fk_parkour_course_tags_course FOREIGN KEY (island_id)
                            REFERENCES parkour_courses (island_id) ON DELETE CASCADE
                    );
                    CREATE TABLE IF NOT EXISTS parkour_ratings (
                        island_id TEXT NOT NULL,
                        player_uuid TEXT NOT NULL,
                        stars INTEGER NOT NULL,
                        PRIMARY KEY (island_id, player_uuid),
                        CONSTRAINT fk_parkour_ratings_course FOREIGN KEY (island_id)
                            REFERENCES parkour_courses (island_id) ON DELETE CASCADE
                    );
                    """);
            case MYSQL -> new Migration(68, V68_DESCRIPTION, """
                    CREATE TABLE IF NOT EXISTS parkour_course_tags (
                        island_id VARCHAR(36) NOT NULL PRIMARY KEY,
                        difficulty VARCHAR(32) NULL,
                        category VARCHAR(32) NULL,
                        CONSTRAINT fk_parkour_course_tags_course FOREIGN KEY (island_id)
                            REFERENCES parkour_courses (island_id) ON DELETE CASCADE
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                    CREATE TABLE IF NOT EXISTS parkour_ratings (
                        island_id VARCHAR(36) NOT NULL,
                        player_uuid VARCHAR(36) NOT NULL,
                        stars INT NOT NULL,
                        PRIMARY KEY (island_id, player_uuid),
                        CONSTRAINT fk_parkour_ratings_course FOREIGN KEY (island_id)
                            REFERENCES parkour_courses (island_id) ON DELETE CASCADE
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                    """);
            case POSTGRES -> new Migration(68, V68_DESCRIPTION, """
                    CREATE TABLE IF NOT EXISTS parkour_course_tags (
                        island_id VARCHAR(36) NOT NULL PRIMARY KEY,
                        difficulty VARCHAR(32) NULL,
                        category VARCHAR(32) NULL,
                        CONSTRAINT fk_parkour_course_tags_course FOREIGN KEY (island_id)
                            REFERENCES parkour_courses (island_id) ON DELETE CASCADE
                    );
                    CREATE TABLE IF NOT EXISTS parkour_ratings (
                        island_id VARCHAR(36) NOT NULL,
                        player_uuid VARCHAR(36) NOT NULL,
                        stars INT NOT NULL,
                        PRIMARY KEY (island_id, player_uuid),
                        CONSTRAINT fk_parkour_ratings_course FOREIGN KEY (island_id)
                            REFERENCES parkour_courses (island_id) ON DELETE CASCADE
                    );
                    """);
            case H2, GENERIC -> throw new IllegalArgumentException("Unsupported dialect for migration V68: " + dialect);
        };
    }
}
