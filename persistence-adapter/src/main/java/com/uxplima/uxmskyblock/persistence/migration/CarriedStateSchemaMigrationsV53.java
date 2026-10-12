package com.uxplima.uxmskyblock.persistence.migration;

import static com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations.V53_DESCRIPTION;

import com.uxplima.uxmlib.storage.migration.Migration;
import com.uxplima.uxmlib.storage.sql.Dialect;

/**
 * V53: what a player put down in each group of worlds, and the group they carry for.
 *
 * <p>A server playing several modes gave a player one inventory for all of them. A profile now keeps, for each
 * group of worlds it is not standing in, what the player put down there, and records the group whose things it
 * carries as its own inventory. Both are written with that inventory, in one transaction.
 */
final class CarriedStateSchemaMigrationsV53 {

    private CarriedStateSchemaMigrationsV53() {}

    static Migration migration(Dialect dialect) {
        return switch (dialect) {
            case SQLITE -> new Migration(53, V53_DESCRIPTION, """
                    CREATE TABLE IF NOT EXISTS carried_states (
                        profile_id VARCHAR(36) NOT NULL,
                        state_group VARCHAR(64) NOT NULL,
                        inventory_nbt BLOB NOT NULL,
                        enderchest_nbt BLOB NOT NULL,
                        experience_points INT NOT NULL,
                        health DOUBLE NOT NULL,
                        food_level INT NOT NULL,
                        saturation FLOAT NOT NULL,
                        active_potion_effects_nbt BLOB NULL,
                        advancements TEXT NOT NULL,
                        PRIMARY KEY (profile_id, state_group),
                        CONSTRAINT fk_carried_states_profile FOREIGN KEY (profile_id)
                            REFERENCES player_profiles (profile_id) ON DELETE CASCADE
                    );
                    CREATE TABLE IF NOT EXISTS carried_groups (
                        profile_id VARCHAR(36) NOT NULL PRIMARY KEY,
                        state_group VARCHAR(64) NOT NULL,
                        CONSTRAINT fk_carried_groups_profile FOREIGN KEY (profile_id)
                            REFERENCES player_profiles (profile_id) ON DELETE CASCADE
                    );
                    """);
            case MYSQL -> new Migration(53, V53_DESCRIPTION, """
                    CREATE TABLE IF NOT EXISTS carried_states (
                        profile_id VARCHAR(36) NOT NULL,
                        state_group VARCHAR(64) NOT NULL,
                        inventory_nbt MEDIUMBLOB NOT NULL,
                        enderchest_nbt MEDIUMBLOB NOT NULL,
                        experience_points INT NOT NULL,
                        health DOUBLE NOT NULL,
                        food_level INT NOT NULL,
                        saturation FLOAT NOT NULL,
                        active_potion_effects_nbt MEDIUMBLOB NULL,
                        advancements MEDIUMTEXT NOT NULL,
                        PRIMARY KEY (profile_id, state_group),
                        CONSTRAINT fk_carried_states_profile FOREIGN KEY (profile_id)
                            REFERENCES player_profiles (profile_id) ON DELETE CASCADE
                    );
                    CREATE TABLE IF NOT EXISTS carried_groups (
                        profile_id VARCHAR(36) NOT NULL PRIMARY KEY,
                        state_group VARCHAR(64) NOT NULL,
                        CONSTRAINT fk_carried_groups_profile FOREIGN KEY (profile_id)
                            REFERENCES player_profiles (profile_id) ON DELETE CASCADE
                    );
                    """);
            case POSTGRES -> new Migration(53, V53_DESCRIPTION, """
                    CREATE TABLE IF NOT EXISTS carried_states (
                        profile_id VARCHAR(36) NOT NULL,
                        state_group VARCHAR(64) NOT NULL,
                        inventory_nbt BYTEA NOT NULL,
                        enderchest_nbt BYTEA NOT NULL,
                        experience_points INT NOT NULL,
                        health DOUBLE PRECISION NOT NULL,
                        food_level INT NOT NULL,
                        saturation REAL NOT NULL,
                        active_potion_effects_nbt BYTEA NULL,
                        advancements TEXT NOT NULL,
                        PRIMARY KEY (profile_id, state_group),
                        CONSTRAINT fk_carried_states_profile FOREIGN KEY (profile_id)
                            REFERENCES player_profiles (profile_id) ON DELETE CASCADE
                    );
                    CREATE TABLE IF NOT EXISTS carried_groups (
                        profile_id VARCHAR(36) NOT NULL PRIMARY KEY,
                        state_group VARCHAR(64) NOT NULL,
                        CONSTRAINT fk_carried_groups_profile FOREIGN KEY (profile_id)
                            REFERENCES player_profiles (profile_id) ON DELETE CASCADE
                    );
                    """);
            case H2, GENERIC -> throw new IllegalArgumentException("Unsupported dialect for migration V53: " + dialect);
        };
    }
}
