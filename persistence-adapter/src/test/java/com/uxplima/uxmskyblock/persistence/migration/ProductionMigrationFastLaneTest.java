package com.uxplima.uxmskyblock.persistence.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.uxplima.uxmlib.storage.migration.Migration;
import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmlib.storage.sql.Dialect;
import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Fast-lane SQLite test suite verifying the production persistence foundation (WP2-001 & WP2-003).
 *
 * <p>Validates {@link SkyblockMigrations} and upstream {@link MigrationRunner} against in-memory
 * and file-backed SQLite instances without requiring external containers.
 */
class ProductionMigrationFastLaneTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("1. Clean in-memory SQLite database migrates successfully to LATEST_VERSION")
    void cleanDatabaseMigratesSuccessfully() {
        try (Database db = DatabaseTestFixture.createSqliteInMemory()) {
            MigrationRunner runner = new MigrationRunner(db);
            assertThat(runner.currentVersion()).isEqualTo(0);

            int applied = runner.apply(SkyblockMigrations.getMigrations(db.dialect()));
            assertThat(applied).isEqualTo(SkyblockMigrations.LATEST_VERSION);
            assertThat(runner.currentVersion()).isEqualTo(SkyblockMigrations.LATEST_VERSION);
        }
    }

    @Test
    @DisplayName("2. Idempotent migration rerun applies zero migrations")
    void idempotentRerunAppliesZeroMigrations() {
        try (Database db = DatabaseTestFixture.createSqliteInMemory()) {
            MigrationRunner runner = new MigrationRunner(db);
            runner.apply(SkyblockMigrations.getMigrations(db.dialect()));
            assertThat(runner.currentVersion()).isEqualTo(SkyblockMigrations.LATEST_VERSION);

            int secondRun = runner.apply(SkyblockMigrations.getMigrations(db.dialect()));
            assertThat(secondRun).isEqualTo(0);
            assertThat(runner.currentVersion()).isEqualTo(SkyblockMigrations.LATEST_VERSION);
        }
    }

    @Test
    @DisplayName("3. Verified tables exist and deferred/future tables are absent")
    void verifiesTableExistenceAndDeferredAbsence() throws Exception {
        try (Database db = DatabaseTestFixture.createSqliteInMemory()) {
            MigrationRunner runner = new MigrationRunner(db);
            runner.apply(SkyblockMigrations.getMigrations(db.dialect()));

            try (Connection conn = db.connection()) {
                DatabaseMetaData meta = conn.getMetaData();
                Set<String> tables = new HashSet<>();
                try (ResultSet rs = meta.getTables(null, null, "%", new String[] {"TABLE"})) {
                    while (rs.next()) {
                        tables.add(rs.getString("TABLE_NAME").toLowerCase(Locale.ROOT));
                    }
                }

                // Canonical tables that MUST exist in V6
                assertThat(tables)
                        .contains(
                                "player_accounts",
                                "player_profiles",
                                "player_sessions",
                                "profile_inventories",
                                "inventory_mutation_journals",
                                "inventory_mutation_participants",
                                "profile_switch_operations",
                                "islands",
                                "island_authorities",
                                "island_locations",
                                "island_members",
                                "island_roles",
                                "island_role_permissions",
                                "island_flags",
                                "island_banks",
                                "bank_transactions",
                                "processed_operations",
                                "island_upgrades",
                                "backup_operations",
                                "outbox_events",
                                "consumer_inbox",
                                "world_grid_allocations",
                                "economy_sagas",
                                "island_seasons",
                                "season_snapshots",
                                "season_payouts",
                                "social_ratings",
                                "guestbook_reviews",
                                "subject_visits",
                                "social_bookmarks",
                                "island_alliances",
                                "island_alliance_invites",
                                "temporary_access_grants",
                                "temporary_access_grant_permissions",
                                "reward_grants",
                                "reward_grant_components",
                                "island_warps",
                                "island_bans",
                                "island_vault_pages",
                                "vault_edit_sessions",
                                "vault_escrow_transfers",
                                "vault_audit_logs",
                                "island_missions",
                                "spiral_slot_pool",
                                "player_anti_abuse_records",
                                "island_quarantines",
                                "island_boosters",
                                "island_bankruptcies",
                                "game_mode_instances",
                                "primary_gameplay_roots",
                                "activity_events",
                                "notifications",
                                "island_homes",
                                "island_dimensions",
                                "profile_cosmetics",
                                "island_recycle_operations",
                                "uxmlib_schema_history");

                // Future / deferred tables that MUST NOT exist
                assertThat(tables).doesNotContain("inbox_events");
            }
        }
    }

    @Test
    @DisplayName("4. Verified columns and types exist on canonical foundation tables")
    void verifiesColumnDefinitions() throws Exception {
        try (Database db = DatabaseTestFixture.createSqliteInMemory()) {
            MigrationRunner runner = new MigrationRunner(db);
            runner.apply(SkyblockMigrations.getMigrations(db.dialect()));

            try (Connection conn = db.connection()) {
                DatabaseMetaData meta = conn.getMetaData();

                // player_accounts columns
                Set<String> accountCols = getColumnNames(meta, "player_accounts");
                assertThat(accountCols)
                        .containsExactlyInAnyOrder(
                                "player_uuid",
                                "active_profile_id",
                                "active_switch_operation_id",
                                "created_at",
                                "updated_at");

                // player_profiles columns
                Set<String> profileCols = getColumnNames(meta, "player_profiles");
                assertThat(profileCols)
                        .containsExactlyInAnyOrder("profile_id", "player_uuid", "profile_type", "created_at");

                // player_sessions columns
                Set<String> sessionCols = getColumnNames(meta, "player_sessions");
                assertThat(sessionCols)
                        .containsExactlyInAnyOrder(
                                "player_uuid",
                                "active_profile_id",
                                "authoritative_node",
                                "session_epoch",
                                "state",
                                "handoff_id",
                                "handoff_target_node",
                                "handoff_expires_at",
                                "last_durable_inventory_version",
                                "lease_expires_at",
                                "updated_at");

                // profile_inventories columns (V2)
                Set<String> inventoryCols = getColumnNames(meta, "profile_inventories");
                assertThat(inventoryCols)
                        .containsExactlyInAnyOrder(
                                "profile_id",
                                "profile_inventory_version",
                                "inventory_nbt",
                                "enderchest_nbt",
                                "experience_points",
                                "health",
                                "food_level",
                                "saturation",
                                "active_potion_effects_nbt",
                                "logout_world",
                                "logout_x",
                                "logout_y",
                                "logout_z",
                                "gamemode",
                                "flight_allowed",
                                "updated_at");

                // inventory_mutation_journals columns (V3)
                Set<String> journalCols = getColumnNames(meta, "inventory_mutation_journals");
                assertThat(journalCols)
                        .containsExactlyInAnyOrder(
                                "operation_id",
                                "operation_type",
                                "state",
                                "participant_count",
                                "payload",
                                "expires_at",
                                "created_at",
                                "updated_at");

                // inventory_mutation_participants columns (V3)
                Set<String> participantCols = getColumnNames(meta, "inventory_mutation_participants");
                assertThat(participantCols)
                        .containsExactlyInAnyOrder(
                                "operation_id",
                                "participant_index",
                                "inventory_type",
                                "owner_root_type",
                                "owner_root_id",
                                "expected_version",
                                "authority_type",
                                "authority_id",
                                "authority_epoch",
                                "before_fingerprint",
                                "after_fingerprint",
                                "durable_apply_state",
                                "mutation_delta_payload",
                                "updated_at");

                // profile_switch_operations columns (V4)
                Set<String> switchCols = getColumnNames(meta, "profile_switch_operations");
                assertThat(switchCols)
                        .containsExactlyInAnyOrder(
                                "operation_id",
                                "player_uuid",
                                "from_profile_id",
                                "to_profile_id",
                                "state",
                                "source_snapshot_blob",
                                "target_snapshot_blob",
                                "failure_reason",
                                "created_at",
                                "updated_at");

                // islands columns (V5)
                Set<String> islandCols = getColumnNames(meta, "islands");
                assertThat(islandCols)
                        .containsExactlyInAnyOrder(
                                "id",
                                "owner_profile_id",
                                "owner_account_uuid",
                                "custom_name",
                                "lifecycle",
                                "economic_state",
                                "administrative_state",
                                "freeze_reason",
                                "level_score",
                                "net_worth_minor_units",
                                "version",
                                "created_at",
                                "updated_at");

                // island_authorities columns (V5)
                Set<String> authCols = getColumnNames(meta, "island_authorities");
                assertThat(authCols)
                        .containsExactlyInAnyOrder(
                                "island_id",
                                "authoritative_node",
                                "authority_epoch",
                                "lease_expires_at",
                                "last_heartbeat_at",
                                "updated_at");

                // island_locations columns (V5)
                Set<String> locCols = getColumnNames(meta, "island_locations");
                assertThat(locCols)
                        .containsExactlyInAnyOrder(
                                "island_id",
                                "world_name",
                                "center_x",
                                "center_z",
                                "min_x",
                                "min_z",
                                "max_x",
                                "max_z",
                                "spawn_x",
                                "spawn_y",
                                "spawn_z",
                                "spawn_yaw",
                                "spawn_pitch");

                // island_members columns (V5)
                Set<String> memCols = getColumnNames(meta, "island_members");
                assertThat(memCols)
                        .containsExactlyInAnyOrder("island_id", "player_uuid", "profile_id", "role_id", "joined_at");

                // island_roles columns (V5)
                Set<String> roleCols = getColumnNames(meta, "island_roles");
                assertThat(roleCols)
                        .containsExactlyInAnyOrder("island_id", "role_id", "weight", "display_name", "is_system");

                // island_role_permissions columns (V5)
                Set<String> permCols = getColumnNames(meta, "island_role_permissions");
                assertThat(permCols).containsExactlyInAnyOrder("island_id", "role_id", "permission");

                // island_flags columns (V5)
                Set<String> flagCols = getColumnNames(meta, "island_flags");
                assertThat(flagCols).containsExactlyInAnyOrder("island_id", "flag_name", "flag_value");

                // island_banks columns (V6)
                Set<String> bankCols = getColumnNames(meta, "island_banks");
                assertThat(bankCols)
                        .containsExactlyInAnyOrder(
                                "island_id",
                                "primary_balance_minor_units",
                                "crystals_balance",
                                "exp_balance",
                                "version",
                                "updated_at");

                // bank_transactions columns (V6, and bank_version since V48)
                Set<String> txCols = getColumnNames(meta, "bank_transactions");
                assertThat(txCols)
                        .containsExactlyInAnyOrder(
                                "transaction_id",
                                "operation_id",
                                "island_id",
                                "actor_uuid",
                                "currency_id",
                                "currency_scale",
                                "delta_amount_minor_units",
                                "resulting_balance_minor_units",
                                "reason",
                                "bank_version",
                                "created_at");

                // processed_operations columns (V6)
                Set<String> opCols = getColumnNames(meta, "processed_operations");
                assertThat(opCols)
                        .containsExactlyInAnyOrder(
                                "operation_id",
                                "operation_scope",
                                "actor_id",
                                "idempotency_key",
                                "operation_type",
                                "resource_id",
                                "status",
                                "result_code",
                                "result_payload",
                                "created_at",
                                "completed_at");

                // island_upgrades columns (V7)
                Set<String> upgCols = getColumnNames(meta, "island_upgrades");
                assertThat(upgCols).containsExactlyInAnyOrder("island_id", "upgrade_key", "tier", "updated_at");

                // backup_operations columns (V8)
                Set<String> bakCols = getColumnNames(meta, "backup_operations");
                assertThat(bakCols)
                        .containsExactlyInAnyOrder(
                                "backup_set_id",
                                "backup_type",
                                "target_root_type_id",
                                "target_root_key",
                                "state",
                                "authority_epoch",
                                "db_version",
                                "schema_version",
                                "plugin_version",
                                "failure_reason",
                                "created_at",
                                "completed_at",
                                "updated_at");

                // outbox_events columns (V9)
                Set<String> outboxCols = getColumnNames(meta, "outbox_events");
                assertThat(outboxCols)
                        .containsExactlyInAnyOrder(
                                "event_id",
                                "event_type",
                                "aggregate_id",
                                "payload",
                                "status",
                                "retry_count",
                                "next_attempt_at",
                                "claim_owner",
                                "claim_token",
                                "claim_expires_at",
                                "last_error",
                                "created_at",
                                "processed_at");

                // consumer_inbox columns (V9)
                Set<String> inboxCols = getColumnNames(meta, "consumer_inbox");
                assertThat(inboxCols).containsExactlyInAnyOrder("consumer_name", "event_id", "processed_at");

                // world_grid_allocations columns (V10)
                Set<String> gridCols = getColumnNames(meta, "world_grid_allocations");
                assertThat(gridCols)
                        .containsExactlyInAnyOrder(
                                "sequence_index",
                                "world_name",
                                "center_x",
                                "center_z",
                                "island_id",
                                "allocated_by_node",
                                "allocated_at");

                // economy_sagas columns (V11)
                Set<String> sagaCols = getColumnNames(meta, "economy_sagas");
                assertThat(sagaCols)
                        .containsExactlyInAnyOrder(
                                "saga_id",
                                "player_uuid",
                                "profile_id",
                                "island_id",
                                "saga_type",
                                "state",
                                "amount_minor_units",
                                "currency",
                                "expires_at",
                                "created_at",
                                "updated_at");

                // island_seasons columns (V13)
                Set<String> seasonCols = getColumnNames(meta, "island_seasons");
                assertThat(seasonCols)
                        .containsExactlyInAnyOrder(
                                "season_id", "name", "starts_at", "ends_at", "state", "created_at", "updated_at");

                // season_snapshots columns (V13)
                Set<String> snapshotCols = getColumnNames(meta, "season_snapshots");
                assertThat(snapshotCols)
                        .containsExactlyInAnyOrder(
                                "season_id",
                                "metric",
                                "rank",
                                "island_id",
                                "owner_player_uuid",
                                "score",
                                "snapshot_timestamp");

                // season_payouts columns (V13)
                Set<String> payoutCols = getColumnNames(meta, "season_payouts");
                assertThat(payoutCols)
                        .containsExactlyInAnyOrder(
                                "payout_id",
                                "season_id",
                                "recipient_uuid",
                                "reward_action",
                                "state",
                                "created_at",
                                "dispatched_at");

                // social_ratings columns (V14)
                Set<String> ratingCols = getColumnNames(meta, "social_ratings");
                assertThat(ratingCols)
                        .containsExactlyInAnyOrder(
                                "subject_type_id",
                                "subject_key",
                                "rater_profile_id",
                                "score",
                                "created_at",
                                "updated_at");

                // guestbook_reviews columns (V14)
                Set<String> guestbookCols = getColumnNames(meta, "guestbook_reviews");
                assertThat(guestbookCols)
                        .containsExactlyInAnyOrder(
                                "review_id",
                                "subject_type_id",
                                "subject_key",
                                "author_profile_id",
                                "message",
                                "is_hidden",
                                "is_pinned",
                                "created_at");

                // subject_visits columns (V14)
                Set<String> visitCols = getColumnNames(meta, "subject_visits");
                assertThat(visitCols)
                        .containsExactlyInAnyOrder(
                                "subject_type_id",
                                "subject_key",
                                "visitor_profile_id",
                                "visit_count",
                                "first_visited_at",
                                "last_visited_at");

                // social_bookmarks columns (V14)
                Set<String> bookmarkCols = getColumnNames(meta, "social_bookmarks");
                assertThat(bookmarkCols)
                        .containsExactlyInAnyOrder("profile_id", "subject_type_id", "subject_key", "created_at");

                // island_alliances columns (V15)
                Set<String> allianceCols = getColumnNames(meta, "island_alliances");
                assertThat(allianceCols)
                        .containsExactlyInAnyOrder("alliance_id", "island_a_id", "island_b_id", "created_at");

                // island_alliance_invites columns (V15)
                Set<String> inviteCols = getColumnNames(meta, "island_alliance_invites");
                assertThat(inviteCols)
                        .containsExactlyInAnyOrder(
                                "invite_id",
                                "sender_island_id",
                                "target_island_id",
                                "sender_profile_id",
                                "created_at",
                                "expires_at");

                // temporary_access_grants columns (V16)
                Set<String> grantCols = getColumnNames(meta, "temporary_access_grants");
                assertThat(grantCols)
                        .containsExactlyInAnyOrder(
                                "grant_id",
                                "instance_id",
                                "target_root_type_id",
                                "target_root_key",
                                "grantee_profile_id",
                                "granted_by_profile_id",
                                "termination_policy",
                                "anchor_player_uuid",
                                "anchor_session_epoch",
                                "anchor_node_id",
                                "anchor_process_generation_id",
                                "state",
                                "created_at",
                                "expires_at",
                                "updated_at");

                // temporary_access_grant_permissions columns (V16)
                Set<String> tempPermCols = getColumnNames(meta, "temporary_access_grant_permissions");
                assertThat(tempPermCols).containsExactlyInAnyOrder("grant_id", "permission_key");

                // reward_grants columns (V17)
                Set<String> rewardGrantCols = getColumnNames(meta, "reward_grants");
                assertThat(rewardGrantCols)
                        .containsExactlyInAnyOrder(
                                "grant_id",
                                "recipient_profile_id",
                                "source_type",
                                "source_id",
                                "state",
                                "claimed_at",
                                "expires_at",
                                "created_at",
                                "updated_at");

                // reward_grant_components columns (V17)
                Set<String> rewardCompCols = getColumnNames(meta, "reward_grant_components");
                assertThat(rewardCompCols)
                        .containsExactlyInAnyOrder(
                                "component_id",
                                "grant_id",
                                "component_index",
                                "component_operation_id",
                                "component_type",
                                "payload_type_id",
                                "payload_schema_version",
                                "payload_data",
                                "state",
                                "journal_operation_id",
                                "updated_at");

                // island_warps columns (V18)
                Set<String> warpCols = getColumnNames(meta, "island_warps");
                assertThat(warpCols)
                        .containsExactlyInAnyOrder(
                                "warp_id",
                                "island_id",
                                "warp_name",
                                "world_name",
                                "x",
                                "y",
                                "z",
                                "yaw",
                                "pitch",
                                "icon_material",
                                "category",
                                "is_locked",
                                "created_at",
                                "updated_at");

                // island_bans columns (V18)
                Set<String> banCols = getColumnNames(meta, "island_bans");
                assertThat(banCols)
                        .containsExactlyInAnyOrder(
                                "island_id", "banned_player_uuid", "banned_by_profile_id", "reason", "created_at");

                // island_vault_pages columns (V19)
                Set<String> vaultPageCols = getColumnNames(meta, "island_vault_pages");
                assertThat(vaultPageCols)
                        .containsExactlyInAnyOrder(
                                "island_id",
                                "page",
                                "page_version",
                                "lease_epoch",
                                "active_session_id",
                                "contents_nbt",
                                "last_modified_by",
                                "updated_at");

                // vault_edit_sessions columns (V19)
                Set<String> vaultSessionCols = getColumnNames(meta, "vault_edit_sessions");
                assertThat(vaultSessionCols)
                        .containsExactlyInAnyOrder(
                                "session_id",
                                "island_id",
                                "page",
                                "player_uuid",
                                "lease_epoch",
                                "base_page_version",
                                "state",
                                "escrow_journal",
                                "opened_at",
                                "expires_at",
                                "closed_at");

                // vault_escrow_transfers columns (V19)
                Set<String> escrowCols = getColumnNames(meta, "vault_escrow_transfers");
                assertThat(escrowCols)
                        .containsExactlyInAnyOrder(
                                "transfer_id",
                                "session_id",
                                "source_type",
                                "dest_type",
                                "source_slot",
                                "dest_slot",
                                "source_before_fp",
                                "source_after_fp",
                                "dest_before_fp",
                                "dest_after_fp",
                                "source_expected_version",
                                "dest_expected_version",
                                "source_container_version",
                                "dest_container_version",
                                "item_nbt",
                                "quantity",
                                "state",
                                "created_at",
                                "updated_at");

                // vault_audit_logs columns (V19)
                Set<String> auditCols = getColumnNames(meta, "vault_audit_logs");
                assertThat(auditCols)
                        .containsExactlyInAnyOrder(
                                "log_id",
                                "island_id",
                                "page",
                                "actor_profile_id",
                                "action_type",
                                "slot",
                                "item_summary",
                                "quantity",
                                "created_at");

                // island_boosters columns (V23)
                Set<String> boosterCols = getColumnNames(meta, "island_boosters");
                assertThat(boosterCols)
                        .containsExactlyInAnyOrder(
                                "booster_id",
                                "island_id",
                                "category",
                                "multiplier",
                                "expires_at",
                                "created_at",
                                "paused_at",
                                "remaining_seconds");

                // island_bankruptcies columns (V24)
                Set<String> bankruptcyCols = getColumnNames(meta, "island_bankruptcies");
                assertThat(bankruptcyCols)
                        .containsExactlyInAnyOrder(
                                "island_id",
                                "status",
                                "debt_minor_units",
                                "grace_until",
                                "updated_at",
                                "upkeep_period");

                // game_mode_instances and primary_gameplay_roots columns (V25)
                Set<String> gmiCols = getColumnNames(meta, "game_mode_instances");
                assertThat(gmiCols)
                        .containsExactlyInAnyOrder(
                                "id", "profile_id", "game_mode_type", "ruleset_config", "created_at", "updated_at");

                Set<String> rootCols = getColumnNames(meta, "primary_gameplay_roots");
                assertThat(rootCols)
                        .containsExactlyInAnyOrder("game_mode_instance_id", "root_id", "root_type", "bound_at");

                // activity_events, notifications, and island_homes columns (V26)
                Set<String> activityCols = getColumnNames(meta, "activity_events");
                assertThat(activityCols)
                        .containsExactlyInAnyOrder(
                                "event_id",
                                "instance_id",
                                "actor_profile_id",
                                "event_type",
                                "visibility",
                                "payload_type_id",
                                "payload_schema_version",
                                "payload_data",
                                "created_at",
                                // V52: who did the line, and what a bank line moved.
                                "actor_player",
                                "amount_currency",
                                "amount_minor");

                Set<String> notificationCols = getColumnNames(meta, "notifications");
                assertThat(notificationCols)
                        .containsExactlyInAnyOrder(
                                "notification_id",
                                "recipient_profile_id",
                                "category",
                                "payload_type_id",
                                "payload_schema_version",
                                "payload_data",
                                "is_read",
                                "read_at",
                                "expires_at",
                                "created_at");

                Set<String> homeCols = getColumnNames(meta, "island_homes");
                assertThat(homeCols)
                        .containsExactlyInAnyOrder(
                                "home_id",
                                "owner_profile_id",
                                "island_id",
                                "home_name",
                                "home_scope",
                                "world_name",
                                "x",
                                "y",
                                "z",
                                "yaw",
                                "pitch",
                                "created_at",
                                "updated_at");

                // profile_cosmetics and island_recycle_operations columns (V28)
                Set<String> cosmeticCols = getColumnNames(meta, "profile_cosmetics");
                assertThat(cosmeticCols)
                        .containsExactlyInAnyOrder("profile_id", "cosmetic_id", "unlocked_at", "granted_by");

                Set<String> recycleCols = getColumnNames(meta, "island_recycle_operations");
                assertThat(recycleCols)
                        .containsExactlyInAnyOrder(
                                "operation_id",
                                "island_id",
                                "initiator_uuid",
                                "target_slot",
                                "state",
                                "backup_path",
                                "error_message",
                                "created_at",
                                "updated_at");
            }
        }
    }

    @Test
    @DisplayName("5. Non-circular initial profile creation lifecycle succeeds")
    void nonCircularProfileCreationLifecycleSucceeds() throws Exception {
        try (Database db = DatabaseTestFixture.createSqliteInMemory()) {
            MigrationRunner runner = new MigrationRunner(db);
            runner.apply(SkyblockMigrations.getMigrations(db.dialect()));

            try (Connection conn = db.connection()) {
                enableForeignKeys(conn);

                // 1. Create account with NULL active_profile_id
                try (PreparedStatement stmt = conn.prepareStatement(
                        "INSERT INTO player_accounts (player_uuid, active_profile_id) VALUES (?, NULL)")) {
                    stmt.setString(1, "p-uuid-1");
                    stmt.executeUpdate();
                }

                // 2. Create profile for account
                try (PreparedStatement stmt = conn.prepareStatement(
                        "INSERT INTO player_profiles (profile_id, player_uuid, profile_type) VALUES (?, ?, 'CLASSIC')")) {
                    stmt.setString(1, "prof-uuid-1");
                    stmt.setString(2, "p-uuid-1");
                    stmt.executeUpdate();
                }

                // 3. Update account active_profile_id to newly created profile
                try (PreparedStatement stmt = conn.prepareStatement(
                        "UPDATE player_accounts SET active_profile_id = ? WHERE player_uuid = ?")) {
                    stmt.setString(1, "prof-uuid-1");
                    stmt.setString(2, "p-uuid-1");
                    stmt.executeUpdate();
                }

                // 4. Create player session
                try (PreparedStatement stmt = conn.prepareStatement("""
                        INSERT INTO player_sessions (
                            player_uuid, active_profile_id, authoritative_node, session_epoch,
                            state, last_durable_inventory_version, lease_expires_at
                        ) VALUES (?, ?, ?, 1, 'ACTIVE', 1, CURRENT_TIMESTAMP)
                        """)) {
                    stmt.setString(1, "p-uuid-1");
                    stmt.setString(2, "prof-uuid-1");
                    stmt.setString(3, "node-alpha");
                    stmt.executeUpdate();
                }

                // Verify session read
                try (PreparedStatement stmt = conn.prepareStatement(
                        "SELECT authoritative_node, session_epoch, state, last_durable_inventory_version FROM player_sessions WHERE player_uuid = ?")) {
                    stmt.setString(1, "p-uuid-1");
                    try (ResultSet rs = stmt.executeQuery()) {
                        assertThat(rs.next()).isTrue();
                        assertThat(rs.getString("authoritative_node")).isEqualTo("node-alpha");
                        assertThat(rs.getLong("session_epoch")).isEqualTo(1L);
                        assertThat(rs.getString("state")).isEqualTo("ACTIVE");
                        assertThat(rs.getLong("last_durable_inventory_version")).isEqualTo(1L);
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("6. Composite foreign key prevents cross-tenant profile hijacking")
    void compositeForeignKeyPreventsCrossTenantHijacking() throws Exception {
        try (Database db = DatabaseTestFixture.createSqliteInMemory()) {
            MigrationRunner runner = new MigrationRunner(db);
            runner.apply(SkyblockMigrations.getMigrations(db.dialect()));

            try (Connection conn = db.connection()) {
                enableForeignKeys(conn);

                // Player 1 & Profile 1
                execute(conn, "INSERT INTO player_accounts (player_uuid) VALUES ('p-1')");
                execute(conn, "INSERT INTO player_profiles (profile_id, player_uuid) VALUES ('prof-1', 'p-1')");
                execute(conn, "UPDATE player_accounts SET active_profile_id = 'prof-1' WHERE player_uuid = 'p-1'");

                // Player 2 & Profile 2
                execute(conn, "INSERT INTO player_accounts (player_uuid) VALUES ('p-2')");
                execute(conn, "INSERT INTO player_profiles (profile_id, player_uuid) VALUES ('prof-2', 'p-2')");
                execute(conn, "UPDATE player_accounts SET active_profile_id = 'prof-2' WHERE player_uuid = 'p-2'");

                // ATTEMPT 1: Player 1 tries to set active_profile_id to Player 2's profile
                assertThatThrownBy(() -> {
                            try (PreparedStatement stmt = conn.prepareStatement(
                                    "UPDATE player_accounts SET active_profile_id = 'prof-2' WHERE player_uuid = 'p-1'")) {
                                stmt.executeUpdate();
                            }
                        })
                        .isInstanceOf(SQLException.class);

                // ATTEMPT 2: Player 1 tries to open a session referencing Player 2's profile
                assertThatThrownBy(() -> {
                            try (PreparedStatement stmt = conn.prepareStatement("""
                            INSERT INTO player_sessions (
                                player_uuid, active_profile_id, authoritative_node, session_epoch,
                                state, last_durable_inventory_version, lease_expires_at
                            ) VALUES ('p-1', 'prof-2', 'node-bad', 1, 'ACTIVE', 1, CURRENT_TIMESTAMP)
                            """)) {
                                stmt.executeUpdate();
                            }
                        })
                        .isInstanceOf(SQLException.class);
            }
        }
    }

    @Test
    @DisplayName("7. Cascade delete removes associated profiles and sessions")
    void cascadeDeleteCleansUpProfilesAndSessions() throws Exception {
        try (Database db = DatabaseTestFixture.createSqliteInMemory()) {
            MigrationRunner runner = new MigrationRunner(db);
            runner.apply(SkyblockMigrations.getMigrations(db.dialect()));

            try (Connection conn = db.connection()) {
                enableForeignKeys(conn);

                execute(conn, "INSERT INTO player_accounts (player_uuid) VALUES ('p-casc')");
                execute(conn, "INSERT INTO player_profiles (profile_id, player_uuid) VALUES ('prof-casc', 'p-casc')");
                execute(
                        conn,
                        "UPDATE player_accounts SET active_profile_id = 'prof-casc' WHERE player_uuid = 'p-casc'");
                execute(conn, """
                        INSERT INTO player_sessions (
                            player_uuid, active_profile_id, authoritative_node, session_epoch,
                            state, last_durable_inventory_version, lease_expires_at
                        ) VALUES ('p-casc', 'prof-casc', 'node-1', 1, 'ACTIVE', 1, CURRENT_TIMESTAMP)
                        """);

                // Break circular pointer first so account delete is not blocked by fk_player_accounts_active_profile
                execute(conn, "UPDATE player_accounts SET active_profile_id = NULL WHERE player_uuid = 'p-casc'");

                // Delete account -> cascades to player_profiles and player_sessions
                execute(conn, "DELETE FROM player_accounts WHERE player_uuid = 'p-casc'");

                assertThat(queryCount(conn, "SELECT COUNT(*) FROM player_profiles WHERE player_uuid = 'p-casc'"))
                        .isEqualTo(0);
                assertThat(queryCount(conn, "SELECT COUNT(*) FROM player_sessions WHERE player_uuid = 'p-casc'"))
                        .isEqualTo(0);
            }
        }
    }

    @Test
    @DisplayName("8. File-backed SQLite database preserves schema across restarts")
    void fileBackedDatabasePreservesSchemaAcrossRestarts() {
        Path dbFile = tempDir.resolve("skyblock-prod.db");

        // First run: apply migrations
        try (Database db = DatabaseTestFixture.createSqliteFile(dbFile)) {
            MigrationRunner runner = new MigrationRunner(db);
            int applied = runner.apply(SkyblockMigrations.getMigrations(db.dialect()));
            assertThat(applied).isEqualTo(SkyblockMigrations.LATEST_VERSION);
            assertThat(runner.currentVersion()).isEqualTo(SkyblockMigrations.LATEST_VERSION);
        }

        // Second run: reopen existing database file
        try (Database db = DatabaseTestFixture.createSqliteFile(dbFile)) {
            MigrationRunner runner = new MigrationRunner(db);
            assertThat(runner.currentVersion()).isEqualTo(SkyblockMigrations.LATEST_VERSION);
            int rerun = runner.apply(SkyblockMigrations.getMigrations(db.dialect()));
            assertThat(rerun).isEqualTo(0);
        }
    }

    @Test
    @DisplayName("9. Explicit dialect dispatch: unsupported upstream dialects fail fast with IllegalArgumentException")
    void unsupportedUpstreamDialectsAreRejected() {
        assertThatThrownBy(() -> SkyblockMigrations.getMigrations(Dialect.H2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported SQL dialect: H2");

        assertThatThrownBy(() -> SkyblockMigrations.getMigrations(Dialect.GENERIC))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported SQL dialect: GENERIC");
    }

    @Test
    @DisplayName("10. Null dialect is rejected with NullPointerException")
    @SuppressWarnings("NullAway")
    void nullDialectThrowsNpe() {
        assertThatThrownBy(() -> SkyblockMigrations.getMigrations(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("dialect");
    }

    @Test
    @DisplayName("11. Upgrade from existing V1 database applies V2 cleanly and preserves data")
    void upgradeFromV1AppliesV2Cleanly() throws Exception {
        try (Database db = DatabaseTestFixture.createSqliteInMemory()) {
            MigrationRunner runner = new MigrationRunner(db);

            // 1. Apply only V1 migration
            List<Migration> allMigrations = SkyblockMigrations.getMigrations(db.dialect());
            Migration v1 = allMigrations.get(0);
            int v1Applied = runner.apply(List.of(v1));
            assertThat(v1Applied).isEqualTo(1);
            assertThat(runner.currentVersion()).isEqualTo(1);

            // 2. Verify pre-V2 schema state: profile_inventories does NOT exist
            try (Connection conn = db.connection()) {
                DatabaseMetaData meta = conn.getMetaData();
                try (ResultSet rs = meta.getTables(null, null, "profile_inventories", null)) {
                    assertThat(rs.next()).isFalse();
                }
            }

            // 3. Seed V1 data
            try (Connection conn = db.connection()) {
                enableForeignKeys(conn);
                execute(conn, "INSERT INTO player_accounts (player_uuid) VALUES ('p-upg')");
                execute(conn, "INSERT INTO player_profiles (profile_id, player_uuid) VALUES ('prof-upg', 'p-upg')");
                execute(conn, "UPDATE player_accounts SET active_profile_id = 'prof-upg' WHERE player_uuid = 'p-upg'");
                execute(conn, """
                        INSERT INTO player_sessions (
                            player_uuid, active_profile_id, authoritative_node, session_epoch,
                            state, last_durable_inventory_version, lease_expires_at
                        ) VALUES ('p-upg', 'prof-upg', 'node-1', 1, 'ACTIVE', 1, CURRENT_TIMESTAMP)
                        """);
            }

            // 4. Upgrade by applying V1 + V2 migrations (only V2 should be applied)
            int v2Applied = runner.apply(allMigrations.subList(0, 2));
            assertThat(v2Applied).isEqualTo(1);
            assertThat(runner.currentVersion()).isEqualTo(2);

            // 5. Verify seeded V1 data preserved
            try (Connection conn = db.connection()) {
                assertThat(queryCount(conn, "SELECT COUNT(*) FROM player_accounts WHERE player_uuid = 'p-upg'"))
                        .isEqualTo(1);
                assertThat(queryCount(conn, "SELECT COUNT(*) FROM player_profiles WHERE profile_id = 'prof-upg'"))
                        .isEqualTo(1);
                assertThat(queryCount(conn, "SELECT COUNT(*) FROM player_sessions WHERE player_uuid = 'p-upg'"))
                        .isEqualTo(1);

                // 6. Verify profile_inventories table now exists and can accept rows
                execute(conn, """
                        INSERT INTO profile_inventories (profile_id, inventory_nbt, enderchest_nbt)
                        VALUES ('prof-upg', X'0102', X'0304')
                        """);
                assertThat(queryCount(conn, "SELECT COUNT(*) FROM profile_inventories WHERE profile_id = 'prof-upg'"))
                        .isEqualTo(1);
            }

            // 7. Rerun and assert zero migrations applied
            int rerun = runner.apply(allMigrations.subList(0, 2));
            assertThat(rerun).isEqualTo(0);
            assertThat(runner.currentVersion()).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("12. Upgrade from existing V2 database applies V3 cleanly and preserves data")
    void upgradeFromV2AppliesV3Cleanly() throws Exception {
        try (Database db = DatabaseTestFixture.createSqliteInMemory()) {
            MigrationRunner runner = new MigrationRunner(db);

            // 1. Apply V1 + V2 migrations
            List<Migration> allMigrations = SkyblockMigrations.getMigrations(db.dialect());
            int v2Applied = runner.apply(allMigrations.subList(0, 2));
            assertThat(v2Applied).isEqualTo(2);
            assertThat(runner.currentVersion()).isEqualTo(2);

            // 2. Verify pre-V3 schema state: inventory_mutation_journals does NOT exist
            try (Connection conn = db.connection()) {
                DatabaseMetaData meta = conn.getMetaData();
                try (ResultSet rs = meta.getTables(null, null, "inventory_mutation_journals", null)) {
                    assertThat(rs.next()).isFalse();
                }
            }

            // 3. Seed V2 data
            try (Connection conn = db.connection()) {
                enableForeignKeys(conn);
                execute(conn, "INSERT INTO player_accounts (player_uuid) VALUES ('p-v3-upg')");
                execute(
                        conn,
                        "INSERT INTO player_profiles (profile_id, player_uuid) VALUES ('prof-v3-upg', 'p-v3-upg')");
                execute(
                        conn,
                        "UPDATE player_accounts SET active_profile_id = 'prof-v3-upg' WHERE player_uuid = 'p-v3-upg'");
                execute(conn, """
                        INSERT INTO player_sessions (
                            player_uuid, active_profile_id, authoritative_node, session_epoch,
                            state, last_durable_inventory_version, lease_expires_at
                        ) VALUES ('p-v3-upg', 'prof-v3-upg', 'node-1', 1, 'ACTIVE', 1, CURRENT_TIMESTAMP)
                        """);
                execute(conn, """
                        INSERT INTO profile_inventories (profile_id, inventory_nbt, enderchest_nbt)
                        VALUES ('prof-v3-upg', X'0102', X'0304')
                        """);
            }

            // 4. Upgrade by applying V3 migration
            int v3Applied = runner.apply(allMigrations.subList(0, 3));
            assertThat(v3Applied).isEqualTo(1);
            assertThat(runner.currentVersion()).isEqualTo(3);

            // 5. Verify seeded V1 and V2 data preserved
            try (Connection conn = db.connection()) {
                assertThat(queryCount(conn, "SELECT COUNT(*) FROM player_accounts WHERE player_uuid = 'p-v3-upg'"))
                        .isEqualTo(1);
                assertThat(queryCount(conn, "SELECT COUNT(*) FROM player_profiles WHERE profile_id = 'prof-v3-upg'"))
                        .isEqualTo(1);
                assertThat(queryCount(conn, "SELECT COUNT(*) FROM player_sessions WHERE player_uuid = 'p-v3-upg'"))
                        .isEqualTo(1);
                assertThat(queryCount(
                                conn, "SELECT COUNT(*) FROM profile_inventories WHERE profile_id = 'prof-v3-upg'"))
                        .isEqualTo(1);

                // 6. Verify inventory_mutation_journals and participants accept rows
                execute(conn, """
                        INSERT INTO inventory_mutation_journals (
                            operation_id, operation_type, state, participant_count, payload, expires_at
                        ) VALUES ('op-1', 'TRADE', 'INTENT', 1, '{}', CURRENT_TIMESTAMP)
                        """);
                execute(conn, """
                        INSERT INTO inventory_mutation_participants (
                            operation_id, participant_index, inventory_type, owner_root_type, owner_root_id,
                            expected_version, authority_type, authority_id, authority_epoch,
                            before_fingerprint, after_fingerprint, durable_apply_state, mutation_delta_payload
                        ) VALUES ('op-1', 0, 'PLAYER_INVENTORY', 'PROFILE', 'prof-v3-upg', 1, 'SERVER_NODE', 'node-1', 1, 'fp1', 'fp2', 'PENDING', '{}')
                        """);

                assertThat(queryCount(
                                conn, "SELECT COUNT(*) FROM inventory_mutation_journals WHERE operation_id = 'op-1'"))
                        .isEqualTo(1);
                assertThat(queryCount(
                                conn,
                                "SELECT COUNT(*) FROM inventory_mutation_participants WHERE operation_id = 'op-1'"))
                        .isEqualTo(1);
            }

            // 7. Rerun and assert zero migrations applied
            int rerun = runner.apply(allMigrations.subList(0, 3));
            assertThat(rerun).isEqualTo(0);
            assertThat(runner.currentVersion()).isEqualTo(3);
        }
    }

    @Test
    @DisplayName("13. Upgrade from existing V3 database applies V4 cleanly and preserves data")
    void upgradeFromV3AppliesV4Cleanly() throws Exception {
        try (Database db = DatabaseTestFixture.createSqliteInMemory()) {
            MigrationRunner runner = new MigrationRunner(db);

            // 1. Apply V1 + V2 + V3 migrations
            List<Migration> allMigrations = SkyblockMigrations.getMigrations(db.dialect());
            int v3Applied = runner.apply(allMigrations.subList(0, 3));
            assertThat(v3Applied).isEqualTo(3);
            assertThat(runner.currentVersion()).isEqualTo(3);

            // 2. Verify pre-V4 schema state: profile_switch_operations does NOT exist
            try (Connection conn = db.connection()) {
                DatabaseMetaData meta = conn.getMetaData();
                try (ResultSet rs = meta.getTables(null, null, "profile_switch_operations", null)) {
                    assertThat(rs.next()).isFalse();
                }
            }

            // 3. Seed V3 data
            try (Connection conn = db.connection()) {
                enableForeignKeys(conn);
                execute(conn, "INSERT INTO player_accounts (player_uuid) VALUES ('p-v4-upg')");
                execute(
                        conn,
                        "INSERT INTO player_profiles (profile_id, player_uuid) VALUES ('prof-v4-src', 'p-v4-upg')");
                execute(
                        conn,
                        "INSERT INTO player_profiles (profile_id, player_uuid) VALUES ('prof-v4-tgt', 'p-v4-upg')");
            }

            // 4. Upgrade by applying V1..V4 migrations (only V4 should be applied)
            int v4Applied = runner.apply(allMigrations.subList(0, 4));
            assertThat(v4Applied).isEqualTo(1);
            assertThat(runner.currentVersion()).isEqualTo(4);

            // 5. Verify seeded data preserved
            try (Connection conn = db.connection()) {
                assertThat(queryCount(conn, "SELECT COUNT(*) FROM player_accounts WHERE player_uuid = 'p-v4-upg'"))
                        .isEqualTo(1);

                // 6. Verify profile_switch_operations table accepts rows
                execute(conn, """
                        INSERT INTO profile_switch_operations (
                            operation_id, player_uuid, from_profile_id, to_profile_id, state
                        ) VALUES ('sw-1', 'p-v4-upg', 'prof-v4-src', 'prof-v4-tgt', 'PREPARING')
                        """);
                assertThat(queryCount(
                                conn, "SELECT COUNT(*) FROM profile_switch_operations WHERE operation_id = 'sw-1'"))
                        .isEqualTo(1);
            }

            // 7. Rerun and assert zero migrations applied
            int rerun = runner.apply(allMigrations.subList(0, 4));
            assertThat(rerun).isEqualTo(0);
            assertThat(runner.currentVersion()).isEqualTo(4);
        }
    }

    @Test
    @DisplayName("14. Upgrade from existing V4 database applies V5 cleanly and preserves data")
    void upgradeFromV4AppliesV5Cleanly() throws Exception {
        try (Database db = DatabaseTestFixture.createSqliteInMemory()) {
            MigrationRunner runner = new MigrationRunner(db);

            // 1. Apply V1 + V2 + V3 + V4 migrations
            List<Migration> allMigrations = SkyblockMigrations.getMigrations(db.dialect());
            int v4Applied = runner.apply(allMigrations.subList(0, 4));
            assertThat(v4Applied).isEqualTo(4);
            assertThat(runner.currentVersion()).isEqualTo(4);

            // 2. Verify pre-V5 schema state: islands does NOT exist
            try (Connection conn = db.connection()) {
                DatabaseMetaData meta = conn.getMetaData();
                try (ResultSet rs = meta.getTables(null, null, "islands", null)) {
                    assertThat(rs.next()).isFalse();
                }
            }

            // 3. Seed V4 data
            try (Connection conn = db.connection()) {
                enableForeignKeys(conn);
                execute(conn, "INSERT INTO player_accounts (player_uuid) VALUES ('p-v5-upg')");
                execute(
                        conn,
                        "INSERT INTO player_profiles (profile_id, player_uuid) VALUES ('prof-v5-src', 'p-v5-upg')");
                execute(
                        conn,
                        "INSERT INTO player_profiles (profile_id, player_uuid) VALUES ('prof-v5-tgt', 'p-v5-upg')");
                execute(conn, """
                        INSERT INTO profile_switch_operations (
                            operation_id, player_uuid, from_profile_id, to_profile_id, state
                        ) VALUES ('sw-v5', 'p-v5-upg', 'prof-v5-src', 'prof-v5-tgt', 'PREPARING')
                        """);
            }

            // 4. Upgrade by applying all migrations up to V5 (only V5 should be applied)
            int v5Applied = runner.apply(allMigrations.subList(0, 5));
            assertThat(v5Applied).isEqualTo(1);
            assertThat(runner.currentVersion()).isEqualTo(5);

            // 5. Verify seeded data preserved
            try (Connection conn = db.connection()) {
                assertThat(queryCount(conn, "SELECT COUNT(*) FROM player_accounts WHERE player_uuid = 'p-v5-upg'"))
                        .isEqualTo(1);
                assertThat(queryCount(
                                conn, "SELECT COUNT(*) FROM profile_switch_operations WHERE operation_id = 'sw-v5'"))
                        .isEqualTo(1);

                // 6. Verify islands table accepts rows
                execute(conn, """
                        INSERT INTO islands (id, owner_profile_id, owner_account_uuid)
                        VALUES ('isl-v5', 'prof-v5-src', 'p-v5-upg')
                        """);
                assertThat(queryCount(conn, "SELECT COUNT(*) FROM islands WHERE id = 'isl-v5'"))
                        .isEqualTo(1);
            }

            // 7. Rerun and assert zero migrations applied
            int rerun = runner.apply(allMigrations.subList(0, 5));
            assertThat(rerun).isEqualTo(0);
            assertThat(runner.currentVersion()).isEqualTo(5);
        }
    }

    @Test
    @DisplayName("15. Upgrade from existing V5 database applies V6 cleanly and preserves data")
    void upgradeFromV5AppliesV6Cleanly() throws Exception {
        try (Database db = DatabaseTestFixture.createSqliteInMemory()) {
            MigrationRunner runner = new MigrationRunner(db);

            // 1. Apply V1..V5 migrations
            List<Migration> allMigrations = SkyblockMigrations.getMigrations(db.dialect());
            int v5Applied = runner.apply(allMigrations.subList(0, 5));
            assertThat(v5Applied).isEqualTo(5);
            assertThat(runner.currentVersion()).isEqualTo(5);

            // 2. Verify pre-V6 schema state: island_banks does NOT exist
            try (Connection conn = db.connection()) {
                DatabaseMetaData meta = conn.getMetaData();
                try (ResultSet rs = meta.getTables(null, null, "island_banks", null)) {
                    assertThat(rs.next()).isFalse();
                }
            }

            // 3. Seed V5 data
            try (Connection conn = db.connection()) {
                enableForeignKeys(conn);
                execute(conn, "INSERT INTO player_accounts (player_uuid) VALUES ('p-v6-upg')");
                execute(conn, "INSERT INTO player_profiles (profile_id, player_uuid) VALUES ('prof-v6', 'p-v6-upg')");
                execute(conn, """
                        INSERT INTO islands (id, owner_profile_id, owner_account_uuid)
                        VALUES ('isl-v6', 'prof-v6', 'p-v6-upg')
                        """);
            }

            // 4. Upgrade by applying V6 migration (only V6 should be applied)
            int v6Applied = runner.apply(allMigrations.subList(0, 6));
            assertThat(v6Applied).isEqualTo(1);
            assertThat(runner.currentVersion()).isEqualTo(6);

            // 5. Verify seeded data preserved
            try (Connection conn = db.connection()) {
                assertThat(queryCount(conn, "SELECT COUNT(*) FROM islands WHERE id = 'isl-v6'"))
                        .isEqualTo(1);

                // 6. Verify island_banks and bank_transactions accept rows
                execute(conn, """
                        INSERT INTO island_banks (island_id, primary_balance_minor_units)
                        VALUES ('isl-v6', 12345)
                        """);
                assertThat(queryCount(conn, "SELECT COUNT(*) FROM island_banks WHERE island_id = 'isl-v6'"))
                        .isEqualTo(1);
            }

            // 7. Rerun and assert zero migrations applied
            int rerun = runner.apply(allMigrations.subList(0, 6));
            assertThat(rerun).isEqualTo(0);
            assertThat(runner.currentVersion()).isEqualTo(6);
        }
    }

    @Test
    @DisplayName(
            "16. Step-by-step upgrade from V6 to V7 preserves existing bank and island data and enables upgrade engine")
    void stepByStepUpgradeFromV6ToV7PreservesData() throws Exception {
        try (Database db = DatabaseTestFixture.createSqliteInMemory()) {
            MigrationRunner runner = new MigrationRunner(db);

            // 1. Migrate up to V6
            List<Migration> allMigrations = SkyblockMigrations.getMigrations(db.dialect());
            int v6Applied = runner.apply(allMigrations.subList(0, 6));
            assertThat(v6Applied).isEqualTo(6);
            assertThat(runner.currentVersion()).isEqualTo(6);

            // 2. Verify pre-V7 schema state: island_upgrades does NOT exist
            try (Connection conn = db.connection()) {
                DatabaseMetaData meta = conn.getMetaData();
                try (ResultSet rs = meta.getTables(null, null, "island_upgrades", null)) {
                    assertThat(rs.next()).isFalse();
                }
            }

            // 3. Seed V6 data
            try (Connection conn = db.connection()) {
                enableForeignKeys(conn);
                execute(conn, "INSERT INTO player_accounts (player_uuid) VALUES ('p-v7-upg')");
                execute(conn, "INSERT INTO player_profiles (profile_id, player_uuid) VALUES ('prof-v7', 'p-v7-upg')");
                execute(conn, """
                        INSERT INTO islands (id, owner_profile_id, owner_account_uuid)
                        VALUES ('isl-v7', 'prof-v7', 'p-v7-upg')
                        """);
                execute(conn, """
                        INSERT INTO island_banks (island_id, primary_balance_minor_units)
                        VALUES ('isl-v7', 50000)
                        """);
            }

            // 4. Upgrade by applying migrations up to V7 (only V7 should be applied)
            int v7Applied = runner.apply(allMigrations.subList(0, 7));
            assertThat(v7Applied).isEqualTo(1);
            assertThat(runner.currentVersion()).isEqualTo(7);

            // 5. Verify seeded data preserved
            try (Connection conn = db.connection()) {
                assertThat(queryCount(conn, "SELECT COUNT(*) FROM islands WHERE id = 'isl-v7'"))
                        .isEqualTo(1);
                assertThat(queryCount(conn, "SELECT COUNT(*) FROM island_banks WHERE island_id = 'isl-v7'"))
                        .isEqualTo(1);

                // 6. Verify island_upgrades accepts rows
                execute(conn, """
                        INSERT INTO island_upgrades (island_id, upgrade_key, tier)
                        VALUES ('isl-v7', 'SIZE', 1)
                        """);
                assertThat(queryCount(conn, "SELECT COUNT(*) FROM island_upgrades WHERE island_id = 'isl-v7'"))
                        .isEqualTo(1);
            }

            // 7. Rerun and assert zero migrations applied
            int rerun = runner.apply(allMigrations.subList(0, 7));
            assertThat(rerun).isEqualTo(0);
            assertThat(runner.currentVersion()).isEqualTo(7);
        }
    }

    @Test
    @DisplayName(
            "17. Step-by-step upgrade from V7 to V8 preserves existing upgrade and island data and enables backup operations catalog")
    void stepByStepUpgradeFromV7ToV8PreservesData() throws Exception {
        try (Database db = DatabaseTestFixture.createSqliteInMemory()) {
            MigrationRunner runner = new MigrationRunner(db);

            // 1. Migrate up to V7
            List<Migration> allMigrations = SkyblockMigrations.getMigrations(db.dialect());
            int v7Applied = runner.apply(allMigrations.subList(0, 7));
            assertThat(v7Applied).isEqualTo(7);
            assertThat(runner.currentVersion()).isEqualTo(7);

            // 2. Verify pre-V8 schema state: backup_operations does NOT exist
            try (Connection conn = db.connection()) {
                DatabaseMetaData meta = conn.getMetaData();
                try (ResultSet rs = meta.getTables(null, null, "backup_operations", null)) {
                    assertThat(rs.next()).isFalse();
                }
            }

            // 3. Seed V7 data
            try (Connection conn = db.connection()) {
                enableForeignKeys(conn);
                execute(conn, "INSERT INTO player_accounts (player_uuid) VALUES ('p-v8-upg')");
                execute(conn, "INSERT INTO player_profiles (profile_id, player_uuid) VALUES ('prof-v8', 'p-v8-upg')");
                execute(conn, """
                        INSERT INTO islands (id, owner_profile_id, owner_account_uuid)
                        VALUES ('isl-v8', 'prof-v8', 'p-v8-upg')
                        """);
                execute(conn, """
                        INSERT INTO island_banks (island_id, primary_balance_minor_units)
                        VALUES ('isl-v8', 50000)
                        """);
                execute(conn, """
                        INSERT INTO island_upgrades (island_id, upgrade_key, tier)
                        VALUES ('isl-v8', 'SIZE', 2)
                        """);
            }

            // 4. Upgrade by applying migrations up to V8 (only V8 should be applied)
            int v8Applied = runner.apply(allMigrations.subList(0, 8));
            assertThat(v8Applied).isEqualTo(1);
            assertThat(runner.currentVersion()).isEqualTo(8);

            // 5. Verify seeded data preserved
            try (Connection conn = db.connection()) {
                assertThat(queryCount(conn, "SELECT COUNT(*) FROM islands WHERE id = 'isl-v8'"))
                        .isEqualTo(1);
                assertThat(queryCount(conn, "SELECT COUNT(*) FROM island_banks WHERE island_id = 'isl-v8'"))
                        .isEqualTo(1);
                assertThat(queryCount(conn, "SELECT COUNT(*) FROM island_upgrades WHERE island_id = 'isl-v8'"))
                        .isEqualTo(1);

                // 6. Verify backup_operations accepts rows
                execute(conn, """
                        INSERT INTO backup_operations (
                            backup_set_id, backup_type, target_root_type_id, target_root_key,
                            state, authority_epoch, db_version, schema_version, plugin_version
                        ) VALUES ('bak-v8', 'ROOT_BACKUP', 'ISLAND', 'isl-v8', 'AVAILABLE', 1, 100, 8, '1.0.0')
                        """);
                assertThat(queryCount(conn, "SELECT COUNT(*) FROM backup_operations WHERE backup_set_id = 'bak-v8'"))
                        .isEqualTo(1);
            }

            // 7. Rerun and assert zero migrations applied
            int rerun = runner.apply(allMigrations.subList(0, 8));
            assertThat(rerun).isEqualTo(0);
            assertThat(runner.currentVersion()).isEqualTo(8);
        }
    }

    @Test
    @DisplayName(
            "18. Step-by-step upgrade from V8 to V9 preserves existing backup data and enables transactional outbox and consumer inbox")
    void stepByStepUpgradeFromV8ToV9PreservesData() throws Exception {
        try (Database db = DatabaseTestFixture.createSqliteInMemory()) {
            MigrationRunner runner = new MigrationRunner(db);

            // 1. Migrate up to V8
            List<Migration> allMigrations = SkyblockMigrations.getMigrations(db.dialect());
            int v8Applied = runner.apply(allMigrations.subList(0, 8));
            assertThat(v8Applied).isEqualTo(8);
            assertThat(runner.currentVersion()).isEqualTo(8);

            // 2. Verify pre-V9 schema state: outbox_events and consumer_inbox do NOT exist
            try (Connection conn = db.connection()) {
                DatabaseMetaData meta = conn.getMetaData();
                try (ResultSet rs = meta.getTables(null, null, "outbox_events", null)) {
                    assertThat(rs.next()).isFalse();
                }
                try (ResultSet rs = meta.getTables(null, null, "consumer_inbox", null)) {
                    assertThat(rs.next()).isFalse();
                }
            }

            // 3. Seed V8 data
            try (Connection conn = db.connection()) {
                enableForeignKeys(conn);
                execute(conn, "INSERT INTO player_accounts (player_uuid) VALUES ('p-v9-upg')");
                execute(conn, "INSERT INTO player_profiles (profile_id, player_uuid) VALUES ('prof-v9', 'p-v9-upg')");
                execute(conn, """
                        INSERT INTO islands (id, owner_profile_id, owner_account_uuid)
                        VALUES ('isl-v9', 'prof-v9', 'p-v9-upg')
                        """);
                execute(conn, """
                        INSERT INTO island_banks (island_id, primary_balance_minor_units)
                        VALUES ('isl-v9', 50000)
                        """);
                execute(conn, """
                        INSERT INTO island_upgrades (island_id, upgrade_key, tier)
                        VALUES ('isl-v9', 'SIZE', 2)
                        """);
                execute(conn, """
                        INSERT INTO backup_operations (
                            backup_set_id, backup_type, target_root_type_id, target_root_key,
                            state, authority_epoch, db_version, schema_version, plugin_version
                        ) VALUES ('bak-v9', 'ROOT_BACKUP', 'ISLAND', 'isl-v9', 'AVAILABLE', 1, 100, 8, '1.0.0')
                        """);
            }

            // 4. Upgrade by applying V9 migration (only V9 should be applied)
            int v9Applied = runner.apply(allMigrations.subList(0, 9));
            assertThat(v9Applied).isEqualTo(1);
            assertThat(runner.currentVersion()).isEqualTo(9);

            // 5. Verify seeded data preserved
            try (Connection conn = db.connection()) {
                assertThat(queryCount(conn, "SELECT COUNT(*) FROM islands WHERE id = 'isl-v9'"))
                        .isEqualTo(1);
                assertThat(queryCount(conn, "SELECT COUNT(*) FROM island_banks WHERE island_id = 'isl-v9'"))
                        .isEqualTo(1);
                assertThat(queryCount(conn, "SELECT COUNT(*) FROM island_upgrades WHERE island_id = 'isl-v9'"))
                        .isEqualTo(1);
                assertThat(queryCount(conn, "SELECT COUNT(*) FROM backup_operations WHERE backup_set_id = 'bak-v9'"))
                        .isEqualTo(1);

                // 6. Verify outbox_events accepts rows
                execute(conn, """
                        INSERT INTO outbox_events (
                            event_id, event_type, aggregate_id, payload, status
                        ) VALUES ('evt-v9-1', 'ISLAND_CREATED', 'isl-v9', '{"tier":1}', 'PENDING')
                        """);
                assertThat(queryCount(conn, "SELECT COUNT(*) FROM outbox_events WHERE event_id = 'evt-v9-1'"))
                        .isEqualTo(1);

                // 7. Verify consumer_inbox accepts rows
                execute(conn, """
                        INSERT INTO consumer_inbox (
                            consumer_name, event_id
                        ) VALUES ('feed-consumer', 'evt-v9-1')
                        """);
                assertThat(queryCount(conn, "SELECT COUNT(*) FROM consumer_inbox WHERE event_id = 'evt-v9-1'"))
                        .isEqualTo(1);
            }

            // 8. Rerun and assert zero migrations applied
            int rerun = runner.apply(allMigrations.subList(0, 9));
            assertThat(rerun).isEqualTo(0);
            assertThat(runner.currentVersion()).isEqualTo(9);
        }
    }

    @Test
    @DisplayName("13. Step-by-step upgrade from V9 to V10 preserves data and adds world_grid_allocations")
    void stepByStepUpgradeFromV9ToV10PreservesData() throws Exception {
        try (Database db = DatabaseTestFixture.createSqliteInMemory()) {
            MigrationRunner runner = new MigrationRunner(db);

            // 1. Migrate up to V9
            List<Migration> allMigrations = SkyblockMigrations.getMigrations(db.dialect());
            int v9Applied = runner.apply(allMigrations.subList(0, 9));
            assertThat(v9Applied).isEqualTo(9);
            assertThat(runner.currentVersion()).isEqualTo(9);

            // 2. Verify pre-V10 schema state: world_grid_allocations does NOT exist
            try (Connection conn = db.connection()) {
                DatabaseMetaData meta = conn.getMetaData();
                try (ResultSet rs = meta.getTables(null, null, "world_grid_allocations", null)) {
                    assertThat(rs.next()).isFalse();
                }
            }

            // 3. Seed V9 data
            try (Connection conn = db.connection()) {
                enableForeignKeys(conn);
                execute(conn, "INSERT INTO player_accounts (player_uuid) VALUES ('p-v10-upg')");
                execute(conn, "INSERT INTO player_profiles (profile_id, player_uuid) VALUES ('prof-v10', 'p-v10-upg')");
                execute(conn, """
                        INSERT INTO islands (id, owner_profile_id, owner_account_uuid)
                        VALUES ('isl-v10', 'prof-v10', 'p-v10-upg')
                        """);
                execute(conn, """
                        INSERT INTO outbox_events (
                            event_id, event_type, aggregate_id, payload, status
                        ) VALUES ('evt-v10-1', 'ISLAND_CREATED', 'isl-v10', '{"tier":1}', 'PENDING')
                        """);
            }

            // 4. Upgrade by applying V10 migration (only V10 should be applied)
            int v10Applied = runner.apply(allMigrations.subList(0, 10));
            assertThat(v10Applied).isEqualTo(1);
            assertThat(runner.currentVersion()).isEqualTo(10);

            // 5. Verify seeded data preserved
            try (Connection conn = db.connection()) {
                assertThat(queryCount(conn, "SELECT COUNT(*) FROM islands WHERE id = 'isl-v10'"))
                        .isEqualTo(1);
                assertThat(queryCount(conn, "SELECT COUNT(*) FROM outbox_events WHERE event_id = 'evt-v10-1'"))
                        .isEqualTo(1);

                // 6. Verify world_grid_allocations accepts rows
                execute(conn, """
                        INSERT INTO world_grid_allocations (
                            sequence_index, world_name, center_x, center_z, island_id, allocated_by_node
                        ) VALUES (0, 'world', 0, 0, 'isl-v10', 'node-1')
                        """);
                assertThat(queryCount(conn, "SELECT COUNT(*) FROM world_grid_allocations WHERE sequence_index = 0"))
                        .isEqualTo(1);
            }

            // 7. Rerun and assert zero migrations applied
            int rerun = runner.apply(allMigrations.subList(0, 10));
            assertThat(rerun).isEqualTo(0);
            assertThat(runner.currentVersion()).isEqualTo(10);
        }
    }

    @Test
    @DisplayName("14. Step-by-step upgrade from V10 to V11 preserves data and adds economy_sagas")
    void stepByStepUpgradeFromV10ToV11PreservesData() throws Exception {
        try (Database db = DatabaseTestFixture.createSqliteInMemory()) {
            MigrationRunner runner = new MigrationRunner(db);

            // 1. Migrate up to V10
            List<Migration> allMigrations = SkyblockMigrations.getMigrations(db.dialect());
            int v10Applied = runner.apply(allMigrations.subList(0, 10));
            assertThat(v10Applied).isEqualTo(10);
            assertThat(runner.currentVersion()).isEqualTo(10);

            // 2. Verify pre-V11 schema state: economy_sagas does NOT exist
            try (Connection conn = db.connection()) {
                DatabaseMetaData meta = conn.getMetaData();
                try (ResultSet rs = meta.getTables(null, null, "economy_sagas", null)) {
                    assertThat(rs.next()).isFalse();
                }
            }

            // 3. Seed V10 data
            try (Connection conn = db.connection()) {
                enableForeignKeys(conn);
                execute(conn, "INSERT INTO player_accounts (player_uuid) VALUES ('p-v11-upg')");
                execute(conn, "INSERT INTO player_profiles (profile_id, player_uuid) VALUES ('prof-v11', 'p-v11-upg')");
                execute(conn, """
                        INSERT INTO islands (id, owner_profile_id, owner_account_uuid)
                        VALUES ('isl-v11', 'prof-v11', 'p-v11-upg')
                        """);
                execute(conn, """
                        INSERT INTO world_grid_allocations (
                            sequence_index, world_name, center_x, center_z, island_id, allocated_by_node
                        ) VALUES (1, 'world', 100, 100, 'isl-v11', 'node-1')
                        """);
            }

            // 4. Upgrade by applying V11 migration (only V11 should be applied)
            int v11Applied = runner.apply(allMigrations.subList(0, 11));
            assertThat(v11Applied).isEqualTo(1);
            assertThat(runner.currentVersion()).isEqualTo(11);

            // 5. Verify seeded data preserved
            try (Connection conn = db.connection()) {
                assertThat(queryCount(conn, "SELECT COUNT(*) FROM islands WHERE id = 'isl-v11'"))
                        .isEqualTo(1);
                assertThat(queryCount(conn, "SELECT COUNT(*) FROM world_grid_allocations WHERE sequence_index = 1"))
                        .isEqualTo(1);

                // 6. Verify economy_sagas accepts rows
                execute(conn, """
                        INSERT INTO economy_sagas (
                            saga_id, player_uuid, profile_id, island_id, saga_type, state,
                            amount_minor_units, currency, expires_at
                        ) VALUES ('saga-1', 'p-v11-upg', 'prof-v11', 'isl-v11', 'DEPOSIT', 'STARTED', 1000, 'VAULT', CURRENT_TIMESTAMP)
                        """);
                assertThat(queryCount(conn, "SELECT COUNT(*) FROM economy_sagas WHERE saga_id = 'saga-1'"))
                        .isEqualTo(1);
            }

            // 7. Rerun and assert zero migrations applied
            int rerun = runner.apply(allMigrations.subList(0, 11));
            assertThat(rerun).isEqualTo(0);
            assertThat(runner.currentVersion()).isEqualTo(11);
        }
    }

    @Test
    @DisplayName(
            "15. Step-by-step upgrade from V11 to V12 enforces unique constraints on island coords and owner profile")
    void stepByStepUpgradeFromV11ToV12EnforcesUniqueConstraints() throws Exception {
        try (Database db = DatabaseTestFixture.createSqliteInMemory()) {
            MigrationRunner runner = new MigrationRunner(db);

            // 1. Migrate up to V11
            List<Migration> allMigrations = SkyblockMigrations.getMigrations(db.dialect());
            int v11Applied = runner.apply(allMigrations.subList(0, 11));
            assertThat(v11Applied).isEqualTo(11);
            assertThat(runner.currentVersion()).isEqualTo(11);

            // 2. Seed V11 data
            try (Connection conn = db.connection()) {
                enableForeignKeys(conn);
                execute(conn, "INSERT INTO player_accounts (player_uuid) VALUES ('p-v12-1')");
                execute(conn, "INSERT INTO player_profiles (profile_id, player_uuid) VALUES ('prof-v12-1', 'p-v12-1')");
                execute(conn, """
                        INSERT INTO islands (id, owner_profile_id, owner_account_uuid)
                        VALUES ('isl-v12-1', 'prof-v12-1', 'p-v12-1')
                        """);
                execute(conn, """
                        INSERT INTO island_locations (
                            island_id, world_name, center_x, center_z, min_x, min_z, max_x, max_z,
                            spawn_x, spawn_y, spawn_z, spawn_yaw, spawn_pitch
                        ) VALUES ('isl-v12-1', 'world', 0, 0, -50, -50, 50, 50, 0.5, 100.0, 0.5, 0.0, 0.0)
                        """);
            }

            // 3. Upgrade by applying V12
            int v12Applied = runner.apply(allMigrations.subList(0, 12));
            assertThat(v12Applied).isEqualTo(1);
            assertThat(runner.currentVersion()).isEqualTo(12);

            // 4. Verify unique constraint: duplicate owner_profile_id is rejected
            try (Connection conn = db.connection()) {
                enableForeignKeys(conn);
                assertThatThrownBy(() -> execute(conn, """
                        INSERT INTO islands (id, owner_profile_id, owner_account_uuid)
                        VALUES ('isl-v12-dup-owner', 'prof-v12-1', 'p-v12-1')
                        """)).isInstanceOf(SQLException.class);

                // 5. Verify unique constraint: duplicate island coordinates are rejected
                execute(conn, "INSERT INTO player_accounts (player_uuid) VALUES ('p-v12-2')");
                execute(conn, "INSERT INTO player_profiles (profile_id, player_uuid) VALUES ('prof-v12-2', 'p-v12-2')");
                execute(conn, """
                        INSERT INTO islands (id, owner_profile_id, owner_account_uuid)
                        VALUES ('isl-v12-2', 'prof-v12-2', 'p-v12-2')
                        """);

                assertThatThrownBy(() -> execute(conn, """
                        INSERT INTO island_locations (
                            island_id, world_name, center_x, center_z, min_x, min_z, max_x, max_z,
                            spawn_x, spawn_y, spawn_z, spawn_yaw, spawn_pitch
                        ) VALUES ('isl-v12-2', 'world', 0, 0, -50, -50, 50, 50, 0.5, 100.0, 0.5, 0.0, 0.0)
                        """)).isInstanceOf(SQLException.class);
            }

            // 6. Rerun and assert zero migrations applied
            int rerun = runner.apply(allMigrations.subList(0, 12));
            assertThat(rerun).isEqualTo(0);
            assertThat(runner.currentVersion()).isEqualTo(12);
        }
    }

    @Test
    @DisplayName("16. Step-by-step upgrade from V12 to V13 creates season, snapshot, and payout tables")
    void stepByStepUpgradeFromV12ToV13CreatesSeasonTables() throws Exception {
        try (Database db = DatabaseTestFixture.createSqliteInMemory()) {
            MigrationRunner runner = new MigrationRunner(db);

            // 1. Migrate up to V12
            List<Migration> allMigrations = SkyblockMigrations.getMigrations(db.dialect());
            int v12Applied = runner.apply(allMigrations.subList(0, 12));
            assertThat(v12Applied).isEqualTo(12);
            assertThat(runner.currentVersion()).isEqualTo(12);

            // 2. Upgrade by applying V13
            int v13Applied = runner.apply(allMigrations.subList(0, 13));
            assertThat(v13Applied).isEqualTo(1);
            assertThat(runner.currentVersion()).isEqualTo(13);

            // 3. Verify V13 tables work
            try (Connection conn = db.connection()) {
                enableForeignKeys(conn);
                execute(conn, """
                        INSERT INTO island_seasons (season_id, name, starts_at, ends_at, state)
                        VALUES (1, 'Season 1', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'ACTIVE')
                        """);
                assertThat(queryCount(conn, "SELECT COUNT(*) FROM island_seasons WHERE season_id = 1"))
                        .isEqualTo(1);
            }

            // 4. Rerun and assert zero migrations applied
            int rerun = runner.apply(allMigrations.subList(0, 13));
            assertThat(rerun).isEqualTo(0);
            assertThat(runner.currentVersion()).isEqualTo(13);
        }
    }

    @Test
    @DisplayName(
            "17. Step-by-step upgrade from V13 to V14 creates social ratings, guestbook, visits, and bookmark tables")
    void stepByStepUpgradeFromV13ToV14CreatesSocialTables() throws Exception {
        try (Database db = DatabaseTestFixture.createSqliteInMemory()) {
            MigrationRunner runner = new MigrationRunner(db);

            // 1. Migrate up to V13
            List<Migration> allMigrations = SkyblockMigrations.getMigrations(db.dialect());
            int v13Applied = runner.apply(allMigrations.subList(0, 13));
            assertThat(v13Applied).isEqualTo(13);
            assertThat(runner.currentVersion()).isEqualTo(13);

            // 2. Upgrade by applying V14
            int v14Applied = runner.apply(allMigrations.subList(0, 14));
            assertThat(v14Applied).isEqualTo(1);
            assertThat(runner.currentVersion()).isEqualTo(14);

            // 3. Verify V14 tables work
            try (Connection conn = db.connection()) {
                enableForeignKeys(conn);
                execute(conn, """
                        INSERT INTO social_ratings (subject_type_id, subject_key, rater_profile_id, score)
                        VALUES ('uxm:island', '11111111-1111-1111-1111-111111111111', '22222222-2222-2222-2222-222222222222', 5)
                        """);
                assertThat(queryCount(conn, "SELECT COUNT(*) FROM social_ratings WHERE score = 5"))
                        .isEqualTo(1);
            }

            // 4. Rerun and assert zero migrations applied
            int rerun = runner.apply(allMigrations.subList(0, 14));
            assertThat(rerun).isEqualTo(0);
            assertThat(runner.currentVersion()).isEqualTo(14);
        }
    }

    @Test
    @DisplayName("18. Step-by-step upgrade from V14 to V15 creates island alliances and invites tables")
    void stepByStepUpgradeFromV14ToV15CreatesAllianceTables() throws Exception {
        try (Database db = DatabaseTestFixture.createSqliteInMemory()) {
            MigrationRunner runner = new MigrationRunner(db);

            // 1. Migrate up to V14
            List<Migration> allMigrations = SkyblockMigrations.getMigrations(db.dialect());
            int v14Applied = runner.apply(allMigrations.subList(0, 14));
            assertThat(v14Applied).isEqualTo(14);
            assertThat(runner.currentVersion()).isEqualTo(14);

            // 2. Upgrade by applying V15
            int v15Applied = runner.apply(allMigrations.subList(0, 15));
            assertThat(v15Applied).isEqualTo(1);
            assertThat(runner.currentVersion()).isEqualTo(15);

            // 3. Verify V15 tables work
            try (Connection conn = db.connection()) {
                enableForeignKeys(conn);
                execute(conn, """
                        INSERT INTO island_alliances (alliance_id, island_a_id, island_b_id)
                        VALUES ('11111111-1111-1111-1111-111111111111', '22222222-2222-2222-2222-222222222222', '33333333-3333-3333-3333-333333333333')
                        """);
                assertThat(
                                queryCount(
                                        conn,
                                        "SELECT COUNT(*) FROM island_alliances WHERE alliance_id = '11111111-1111-1111-1111-111111111111'"))
                        .isEqualTo(1);

                execute(conn, """
                        INSERT INTO island_alliance_invites (invite_id, sender_island_id, target_island_id, sender_profile_id, expires_at)
                        VALUES ('44444444-4444-4444-4444-444444444444', '22222222-2222-2222-2222-222222222222', '55555555-5555-5555-5555-555555555555', '66666666-6666-6666-6666-666666666666', CURRENT_TIMESTAMP)
                        """);
                assertThat(
                                queryCount(
                                        conn,
                                        "SELECT COUNT(*) FROM island_alliance_invites WHERE invite_id = '44444444-4444-4444-4444-444444444444'"))
                        .isEqualTo(1);
            }

            // 4. Rerun and assert zero migrations applied
            int rerun = runner.apply(allMigrations.subList(0, 15));
            assertThat(rerun).isEqualTo(0);
            assertThat(runner.currentVersion()).isEqualTo(15);
        }
    }

    @Test
    @DisplayName("19. Step-by-step upgrade from V15 to V16 creates temporary access grants and permissions tables")
    void stepByStepUpgradeFromV15ToV16CreatesTemporaryAccessTables() throws Exception {
        try (Database db = DatabaseTestFixture.createSqliteInMemory()) {
            MigrationRunner runner = new MigrationRunner(db);

            // 1. Migrate up to V15
            List<Migration> allMigrations = SkyblockMigrations.getMigrations(db.dialect());
            int v15Applied = runner.apply(allMigrations.subList(0, 15));
            assertThat(v15Applied).isEqualTo(15);
            assertThat(runner.currentVersion()).isEqualTo(15);

            // 2. Upgrade by applying V16
            int v16Applied = runner.apply(allMigrations.subList(0, 16));
            assertThat(v16Applied).isEqualTo(1);
            assertThat(runner.currentVersion()).isEqualTo(16);

            // 3. Verify V16 tables and foreign key cascade work
            try (Connection conn = db.connection()) {
                enableForeignKeys(conn);
                execute(conn, """
                        INSERT INTO temporary_access_grants (
                            grant_id, instance_id, target_root_type_id, target_root_key,
                            grantee_profile_id, granted_by_profile_id, termination_policy, state
                        ) VALUES (
                            '11111111-1111-1111-1111-111111111111',
                            '22222222-2222-2222-2222-222222222222',
                            'uxm:island',
                            '22222222-2222-2222-2222-222222222222',
                            '33333333-3333-3333-3333-333333333333',
                            '44444444-4444-4444-4444-444444444444',
                            'UNTIL_REVOKED',
                            'ACTIVE'
                        )
                        """);
                assertThat(
                                queryCount(
                                        conn,
                                        "SELECT COUNT(*) FROM temporary_access_grants WHERE grant_id = '11111111-1111-1111-1111-111111111111'"))
                        .isEqualTo(1);

                execute(conn, """
                        INSERT INTO temporary_access_grant_permissions (grant_id, permission_key)
                        VALUES ('11111111-1111-1111-1111-111111111111', 'uxm:block.break')
                        """);
                assertThat(
                                queryCount(
                                        conn,
                                        "SELECT COUNT(*) FROM temporary_access_grant_permissions WHERE grant_id = '11111111-1111-1111-1111-111111111111'"))
                        .isEqualTo(1);

                // Delete grant and verify cascade deletes permission
                execute(
                        conn,
                        "DELETE FROM temporary_access_grants WHERE grant_id = '11111111-1111-1111-1111-111111111111'");
                assertThat(
                                queryCount(
                                        conn,
                                        "SELECT COUNT(*) FROM temporary_access_grant_permissions WHERE grant_id = '11111111-1111-1111-1111-111111111111'"))
                        .isEqualTo(0);
            }

            // 4. Rerun and assert zero migrations applied
            int rerun = runner.apply(allMigrations.subList(0, 16));
            assertThat(rerun).isEqualTo(0);
            assertThat(runner.currentVersion()).isEqualTo(16);
        }
    }

    @Test
    @DisplayName("20. Step-by-step upgrade from V16 to V17 creates reward grants and components tables")
    void stepByStepUpgradeFromV16ToV17CreatesRewardTables() throws Exception {
        try (Database db = DatabaseTestFixture.createSqliteInMemory()) {
            MigrationRunner runner = new MigrationRunner(db);

            // 1. Migrate up to V16
            List<Migration> allMigrations = SkyblockMigrations.getMigrations(db.dialect());
            int v16Applied = runner.apply(allMigrations.subList(0, 16));
            assertThat(v16Applied).isEqualTo(16);
            assertThat(runner.currentVersion()).isEqualTo(16);

            // 2. Upgrade by applying V17
            int v17Applied = runner.apply(allMigrations.subList(0, 17));
            assertThat(v17Applied).isEqualTo(1);
            assertThat(runner.currentVersion()).isEqualTo(17);

            // 3. Verify V17 tables and foreign key cascade work
            try (Connection conn = db.connection()) {
                enableForeignKeys(conn);
                execute(
                        conn,
                        "INSERT INTO player_accounts (player_uuid) VALUES ('00000000-0000-0000-0000-000000000001');");
                execute(
                        conn,
                        "INSERT INTO player_profiles (profile_id, player_uuid, profile_type) VALUES ('11111111-1111-1111-1111-111111111111', '00000000-0000-0000-0000-000000000001', 'CLASSIC');");

                execute(conn, """
                        INSERT INTO reward_grants (
                            grant_id, recipient_profile_id, source_type, source_id, state
                        ) VALUES (
                            '22222222-2222-2222-2222-222222222222',
                            '11111111-1111-1111-1111-111111111111',
                            'SEASON_PAYOUT',
                            'season-1-rank-1',
                            'PENDING'
                        )
                        """);
                assertThat(
                                queryCount(
                                        conn,
                                        "SELECT COUNT(*) FROM reward_grants WHERE grant_id = '22222222-2222-2222-2222-222222222222'"))
                        .isEqualTo(1);

                execute(conn, """
                        INSERT INTO reward_grant_components (
                            component_id, grant_id, component_index, component_operation_id,
                            component_type, payload_type_id, payload_schema_version, payload_data, state
                        ) VALUES (
                            '33333333-3333-3333-3333-333333333333',
                            '22222222-2222-2222-2222-222222222222',
                            0,
                            '44444444-4444-4444-4444-444444444444',
                            'ITEM',
                            'uxm:item_bundle',
                            1,
                            '{}',
                            'PENDING'
                        )
                        """);
                assertThat(
                                queryCount(
                                        conn,
                                        "SELECT COUNT(*) FROM reward_grant_components WHERE component_id = '33333333-3333-3333-3333-333333333333'"))
                        .isEqualTo(1);

                // Delete grant and verify cascade deletes component
                execute(conn, "DELETE FROM reward_grants WHERE grant_id = '22222222-2222-2222-2222-222222222222'");
                assertThat(
                                queryCount(
                                        conn,
                                        "SELECT COUNT(*) FROM reward_grant_components WHERE component_id = '33333333-3333-3333-3333-333333333333'"))
                        .isEqualTo(0);
            }

            // 4. Rerun and assert zero migrations applied
            int rerun = runner.apply(allMigrations.subList(0, 17));
            assertThat(rerun).isEqualTo(0);
            assertThat(runner.currentVersion()).isEqualTo(17);
        }
    }

    @Test
    @DisplayName("21. Step-by-step upgrade from V17 to V18 creates island warps and island bans tables")
    void stepByStepUpgradeFromV17ToV18CreatesWarpAndBanTables() throws Exception {
        try (Database db = DatabaseTestFixture.createSqliteInMemory()) {
            MigrationRunner runner = new MigrationRunner(db);

            // 1. Migrate up to V17
            List<Migration> allMigrations = SkyblockMigrations.getMigrations(db.dialect());
            int v17Applied = runner.apply(allMigrations.subList(0, 17));
            assertThat(v17Applied).isEqualTo(17);
            assertThat(runner.currentVersion()).isEqualTo(17);

            // 2. Upgrade by applying V18
            int v18Applied = runner.apply(allMigrations.subList(0, 18));
            assertThat(v18Applied).isEqualTo(1);
            assertThat(runner.currentVersion()).isEqualTo(18);

            // 3. Verify V18 tables and foreign key cascade work
            try (Connection conn = db.connection()) {
                enableForeignKeys(conn);
                execute(
                        conn,
                        "INSERT INTO player_accounts (player_uuid) VALUES ('00000000-0000-0000-0000-000000000001');");
                execute(
                        conn,
                        "INSERT INTO player_profiles (profile_id, player_uuid, profile_type) VALUES ('11111111-1111-1111-1111-111111111111', '00000000-0000-0000-0000-000000000001', 'CLASSIC');");
                execute(conn, """
                        INSERT INTO islands (
                            id, owner_profile_id, owner_account_uuid, custom_name, lifecycle,
                            economic_state, administrative_state, level_score, net_worth_minor_units, version
                        ) VALUES (
                            '99999999-9999-9999-9999-999999999999',
                            '11111111-1111-1111-1111-111111111111',
                            '00000000-0000-0000-0000-000000000001',
                            'Alpha Island',
                            'ACTIVE',
                            'NORMAL',
                            'NORMAL',
                            100,
                            50000,
                            1
                        );
                        """);

                // Insert warp
                execute(conn, """
                        INSERT INTO island_warps (
                            warp_id, island_id, warp_name, world_name, x, y, z, yaw, pitch, icon_material, category, is_locked
                        ) VALUES (
                            'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
                            '99999999-9999-9999-9999-999999999999',
                            'market',
                            'skyblock_world',
                            100.5, 65.0, -200.5,
                            90.0, 0.0,
                            'CHEST',
                            'SHOPS',
                            0
                        );
                        """);
                assertThat(
                                queryCount(
                                        conn,
                                        "SELECT COUNT(*) FROM island_warps WHERE warp_id = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'"))
                        .isEqualTo(1);

                // Unique constraint on (island_id, warp_name) rejects duplicate
                assertThatThrownBy(() -> execute(conn, """
                        INSERT INTO island_warps (
                            warp_id, island_id, warp_name, world_name, x, y, z, yaw, pitch, icon_material, category, is_locked
                        ) VALUES (
                            'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb',
                            '99999999-9999-9999-9999-999999999999',
                            'market',
                            'skyblock_world',
                            105.5, 65.0, -200.5,
                            0.0, 0.0,
                            'CHEST',
                            'SHOPS',
                            0
                        );
                        """)).isInstanceOf(SQLException.class);

                // Insert island ban
                execute(conn, """
                        INSERT INTO island_bans (
                            island_id, banned_player_uuid, banned_by_profile_id, reason
                        ) VALUES (
                            '99999999-9999-9999-9999-999999999999',
                            '77777777-7777-7777-7777-777777777777',
                            '11111111-1111-1111-1111-111111111111',
                            'Griefing attempts'
                        );
                        """);
                assertThat(
                                queryCount(
                                        conn,
                                        "SELECT COUNT(*) FROM island_bans WHERE island_id = '99999999-9999-9999-9999-999999999999'"))
                        .isEqualTo(1);

                // Delete island and verify cascade deletes both warps and bans
                execute(conn, "DELETE FROM islands WHERE id = '99999999-9999-9999-9999-999999999999'");
                assertThat(
                                queryCount(
                                        conn,
                                        "SELECT COUNT(*) FROM island_warps WHERE warp_id = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'"))
                        .isEqualTo(0);
                assertThat(
                                queryCount(
                                        conn,
                                        "SELECT COUNT(*) FROM island_bans WHERE island_id = '99999999-9999-9999-9999-999999999999'"))
                        .isEqualTo(0);
            }

            // 4. Rerun and assert zero migrations applied
            int rerun = runner.apply(allMigrations.subList(0, 18));
            assertThat(rerun).isEqualTo(0);
            assertThat(runner.currentVersion()).isEqualTo(18);
        }
    }

    @Test
    @DisplayName(
            "22. Step-by-step upgrade from V18 to V19 creates island vault pages, edit sessions, escrow transfers, and audit logs tables")
    void stepByStepUpgradeFromV18ToV19CreatesVaultTables() throws Exception {
        try (Database db = DatabaseTestFixture.createSqliteInMemory()) {
            MigrationRunner runner = new MigrationRunner(db);

            // 1. Migrate up to V18
            List<Migration> allMigrations = SkyblockMigrations.getMigrations(db.dialect());
            int v18Applied = runner.apply(allMigrations.subList(0, 18));
            assertThat(v18Applied).isEqualTo(18);
            assertThat(runner.currentVersion()).isEqualTo(18);

            // 2. Upgrade by applying V19
            int v19Applied = runner.apply(allMigrations.subList(0, 19));
            assertThat(v19Applied).isEqualTo(1);
            assertThat(runner.currentVersion()).isEqualTo(19);

            // 3. Verify V19 tables and foreign key cascade work
            try (Connection conn = db.connection()) {
                enableForeignKeys(conn);
                execute(
                        conn,
                        "INSERT INTO player_accounts (player_uuid) VALUES ('00000000-0000-0000-0000-000000000001');");
                execute(
                        conn,
                        "INSERT INTO player_profiles (profile_id, player_uuid, profile_type) VALUES ('11111111-1111-1111-1111-111111111111', '00000000-0000-0000-0000-000000000001', 'CLASSIC');");
                execute(conn, """
                        INSERT INTO islands (
                            id, owner_profile_id, owner_account_uuid, custom_name, lifecycle,
                            economic_state, administrative_state, level_score, net_worth_minor_units, version
                        ) VALUES (
                            '99999999-9999-9999-9999-999999999999',
                            '11111111-1111-1111-1111-111111111111',
                            '00000000-0000-0000-0000-000000000001',
                            'Alpha Island',
                            'ACTIVE',
                            'NORMAL',
                            'NORMAL',
                            100,
                            50000,
                            1
                        );
                        """);

                // Insert vault page
                execute(conn, """
                        INSERT INTO island_vault_pages (
                            island_id, page, page_version, lease_epoch, active_session_id, contents_nbt, last_modified_by
                        ) VALUES (
                            '99999999-9999-9999-9999-999999999999',
                            1,
                            1,
                            1,
                            NULL,
                            X'010203',
                            '11111111-1111-1111-1111-111111111111'
                        );
                        """);
                assertThat(
                                queryCount(
                                        conn,
                                        "SELECT COUNT(*) FROM island_vault_pages WHERE island_id = '99999999-9999-9999-9999-999999999999' AND page = 1"))
                        .isEqualTo(1);

                // Insert edit session
                execute(conn, """
                        INSERT INTO vault_edit_sessions (
                            session_id, island_id, page, player_uuid, lease_epoch, base_page_version, state, expires_at
                        ) VALUES (
                            'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
                            '99999999-9999-9999-9999-999999999999',
                            1,
                            '00000000-0000-0000-0000-000000000001',
                            1,
                            1,
                            'ACTIVE',
                            DATETIME('now', '+60 seconds')
                        );
                        """);
                assertThat(
                                queryCount(
                                        conn,
                                        "SELECT COUNT(*) FROM vault_edit_sessions WHERE session_id = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'"))
                        .isEqualTo(1);

                // Insert escrow transfer
                execute(conn, """
                        INSERT INTO vault_escrow_transfers (
                            transfer_id, session_id, source_type, dest_type, source_slot, dest_slot,
                            source_before_fp, source_after_fp, dest_before_fp, dest_after_fp,
                            source_expected_version, dest_expected_version, source_container_version, dest_container_version,
                            item_nbt, quantity, state
                        ) VALUES (
                            'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb',
                            'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
                            'VAULT',
                            'PLAYER',
                            0,
                            0,
                            'fp-before',
                            'fp-after',
                            'dest-before',
                            'dest-after',
                            1,
                            1,
                            1,
                            1,
                            X'040506',
                            1,
                            'INTENT'
                        );
                        """);
                assertThat(
                                queryCount(
                                        conn,
                                        "SELECT COUNT(*) FROM vault_escrow_transfers WHERE transfer_id = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb'"))
                        .isEqualTo(1);

                // Insert audit log
                execute(conn, """
                        INSERT INTO vault_audit_logs (
                            log_id, island_id, page, actor_profile_id, action_type, slot, item_summary, quantity
                        ) VALUES (
                            'cccccccc-cccc-cccc-cccc-cccccccccccc',
                            '99999999-9999-9999-9999-999999999999',
                            1,
                            '11111111-1111-1111-1111-111111111111',
                            'WITHDRAW',
                            0,
                            'DIAMOND_SWORD x1',
                            1
                        );
                        """);
                assertThat(
                                queryCount(
                                        conn,
                                        "SELECT COUNT(*) FROM vault_audit_logs WHERE log_id = 'cccccccc-cccc-cccc-cccc-cccccccccccc'"))
                        .isEqualTo(1);

                // Delete island and verify cascade deletes vault pages, sessions, escrow transfers, and audit logs
                execute(conn, "DELETE FROM islands WHERE id = '99999999-9999-9999-9999-999999999999'");
                assertThat(
                                queryCount(
                                        conn,
                                        "SELECT COUNT(*) FROM island_vault_pages WHERE island_id = '99999999-9999-9999-9999-999999999999'"))
                        .isEqualTo(0);
                assertThat(
                                queryCount(
                                        conn,
                                        "SELECT COUNT(*) FROM vault_edit_sessions WHERE session_id = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'"))
                        .isEqualTo(0);
                assertThat(
                                queryCount(
                                        conn,
                                        "SELECT COUNT(*) FROM vault_escrow_transfers WHERE transfer_id = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb'"))
                        .isEqualTo(0);
                assertThat(
                                queryCount(
                                        conn,
                                        "SELECT COUNT(*) FROM vault_audit_logs WHERE log_id = 'cccccccc-cccc-cccc-cccc-cccccccccccc'"))
                        .isEqualTo(0);
            }

            // 4. Rerun and assert zero migrations applied
            int rerun = runner.apply(allMigrations.subList(0, 19));
            assertThat(rerun).isEqualTo(0);
            assertThat(runner.currentVersion()).isEqualTo(19);
        }
    }

    @Test
    @DisplayName("23. Step-by-step upgrade from V19 to V20 creates island missions table")
    void stepByStepUpgradeFromV19ToV20CreatesMissionTable() throws Exception {
        try (Database db = DatabaseTestFixture.createSqliteInMemory()) {
            MigrationRunner runner = new MigrationRunner(db);

            // 1. Migrate up to V19
            List<Migration> allMigrations = SkyblockMigrations.getMigrations(db.dialect());
            int v19Applied = runner.apply(allMigrations.subList(0, 19));
            assertThat(v19Applied).isEqualTo(19);
            assertThat(runner.currentVersion()).isEqualTo(19);

            // 2. Upgrade by applying V20
            int v20Applied = runner.apply(allMigrations.subList(0, 20));
            assertThat(v20Applied).isEqualTo(1);
            assertThat(runner.currentVersion()).isEqualTo(20);

            // 3. Verify V20 table and foreign key cascade
            try (Connection conn = db.connection()) {
                enableForeignKeys(conn);
                execute(
                        conn,
                        "INSERT INTO player_accounts (player_uuid) VALUES ('00000000-0000-0000-0000-000000000001');");
                execute(
                        conn,
                        "INSERT INTO player_profiles (profile_id, player_uuid, profile_type) VALUES ('11111111-1111-1111-1111-111111111111', '00000000-0000-0000-0000-000000000001', 'CLASSIC');");
                execute(conn, """
                        INSERT INTO islands (
                            id, owner_profile_id, owner_account_uuid, custom_name, lifecycle,
                            economic_state, administrative_state, level_score, net_worth_minor_units, version
                        ) VALUES (
                            '99999999-9999-9999-9999-999999999999',
                            '11111111-1111-1111-1111-111111111111',
                            '00000000-0000-0000-0000-000000000001',
                            'Alpha Island',
                            'ACTIVE',
                            'NORMAL',
                            'NORMAL',
                            0,
                            0,
                            1
                        );
                        """);

                execute(conn, """
                        INSERT INTO island_missions (
                            island_id, profile_id, mission_id, progress_count, completed, completed_at, updated_at
                        ) VALUES (
                            '99999999-9999-9999-9999-999999999999',
                            '11111111-1111-1111-1111-111111111111',
                            'wheat_1',
                            10,
                            0,
                            NULL,
                            CURRENT_TIMESTAMP
                        );
                        """);

                assertThat(
                                queryCount(
                                        conn,
                                        "SELECT COUNT(*) FROM island_missions WHERE island_id = '99999999-9999-9999-9999-999999999999'"))
                        .isEqualTo(1);

                // Delete island and verify cascade deletes island_missions
                execute(conn, "DELETE FROM islands WHERE id = '99999999-9999-9999-9999-999999999999'");
                assertThat(
                                queryCount(
                                        conn,
                                        "SELECT COUNT(*) FROM island_missions WHERE island_id = '99999999-9999-9999-9999-999999999999'"))
                        .isEqualTo(0);
            }

            // 4. Rerun and assert zero migrations applied
            int rerun = runner.apply(allMigrations.subList(0, 20));
            assertThat(rerun).isEqualTo(0);
            assertThat(runner.currentVersion()).isEqualTo(20);
        }
    }

    @Test
    @DisplayName("24. Step-by-step upgrade from V20 to V21 creates spiral slot pool table")
    void stepByStepUpgradeFromV20ToV21CreatesSpiralSlotPoolTable() throws Exception {
        try (Database db = DatabaseTestFixture.createSqliteInMemory()) {
            MigrationRunner runner = new MigrationRunner(db);

            // 1. Migrate up to V20
            List<Migration> allMigrations = SkyblockMigrations.getMigrations(db.dialect());
            int v20Applied = runner.apply(allMigrations.subList(0, 20));
            assertThat(v20Applied).isEqualTo(20);
            assertThat(runner.currentVersion()).isEqualTo(20);

            // 2. Upgrade by applying V21
            int v21Applied = runner.apply(allMigrations.subList(0, 21));
            assertThat(v21Applied).isEqualTo(1);
            assertThat(runner.currentVersion()).isEqualTo(21);

            // 3. Verify spiral_slot_pool table accepts rows and records states
            try (Connection conn = db.connection()) {
                execute(conn, """
                        INSERT INTO spiral_slot_pool (
                            slot_index, world_name, grid_x, grid_z, is_allocated, vacated_at
                        ) VALUES (
                            42, 'skyblock_world', 500, 500, 0, CURRENT_TIMESTAMP
                        );
                        """);

                assertThat(queryCount(
                                conn,
                                "SELECT COUNT(*) FROM spiral_slot_pool WHERE slot_index = 42 AND is_allocated = 0"))
                        .isEqualTo(1);
            }

            // 4. Rerun and assert zero migrations applied
            int rerun = runner.apply(allMigrations.subList(0, 21));
            assertThat(rerun).isEqualTo(0);
            assertThat(runner.currentVersion()).isEqualTo(21);
        }
    }

    @Test
    @DisplayName("25. Step-by-step upgrade from V21 to V22 creates player anti abuse records and island quarantines")
    void stepByStepUpgradeFromV21ToV22CreatesAntiAbuseTables() throws Exception {
        try (Database db = DatabaseTestFixture.createSqliteInMemory()) {
            MigrationRunner runner = new MigrationRunner(db);

            // 1. Migrate up to V21
            List<Migration> allMigrations = SkyblockMigrations.getMigrations(db.dialect());
            int v21Applied = runner.apply(allMigrations.subList(0, 21));
            assertThat(v21Applied).isEqualTo(21);
            assertThat(runner.currentVersion()).isEqualTo(21);

            // 2. Upgrade by applying V22
            int v22Applied = runner.apply(allMigrations.subList(0, 22));
            assertThat(v22Applied).isEqualTo(1);
            assertThat(runner.currentVersion()).isEqualTo(22);

            // 3. Verify player_anti_abuse_records and island_quarantines accept rows
            try (Connection conn = db.connection()) {
                enableForeignKeys(conn);
                execute(conn, """
                        INSERT INTO player_anti_abuse_records (
                            player_uuid, last_island_reset_at, resets_today_count, reset_window_start,
                            coop_cooldown_expires_at, updated_at
                        ) VALUES (
                            '11111111-1111-1111-1111-111111111111', CURRENT_TIMESTAMP, 1, CURRENT_TIMESTAMP, NULL, CURRENT_TIMESTAMP
                        );
                        """);

                assertThat(
                                queryCount(
                                        conn,
                                        "SELECT COUNT(*) FROM player_anti_abuse_records WHERE player_uuid = '11111111-1111-1111-1111-111111111111'"))
                        .isEqualTo(1);

                // Insert dummy island to test island_quarantines foreign key
                execute(
                        conn,
                        "INSERT INTO player_accounts (player_uuid) VALUES ('00000000-0000-0000-0000-000000000001');");
                execute(
                        conn,
                        "INSERT INTO player_profiles (profile_id, player_uuid, profile_type) VALUES ('00000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000001', 'CLASSIC');");
                execute(conn, """
                        INSERT INTO islands (
                            id, owner_profile_id, owner_account_uuid, custom_name,
                            lifecycle, economic_state, administrative_state, version
                        ) VALUES (
                            '99999999-9999-9999-9999-999999999999',
                            '00000000-0000-0000-0000-000000000002',
                            '00000000-0000-0000-0000-000000000001',
                            'Quarantine Island', 'ACTIVE', 'NORMAL', 'NORMAL', 1
                        );
                        """);

                execute(conn, """
                        INSERT INTO island_quarantines (
                            island_id, quarantined_until, quarantine_reason, created_at
                        ) VALUES (
                            '99999999-9999-9999-9999-999999999999', CURRENT_TIMESTAMP, 'NEW_ISLAND_CREATION', CURRENT_TIMESTAMP
                        );
                        """);

                assertThat(
                                queryCount(
                                        conn,
                                        "SELECT COUNT(*) FROM island_quarantines WHERE island_id = '99999999-9999-9999-9999-999999999999'"))
                        .isEqualTo(1);

                // Delete island and verify cascade deletes island_quarantines
                execute(conn, "DELETE FROM islands WHERE id = '99999999-9999-9999-9999-999999999999'");
                assertThat(
                                queryCount(
                                        conn,
                                        "SELECT COUNT(*) FROM island_quarantines WHERE island_id = '99999999-9999-9999-9999-999999999999'"))
                        .isEqualTo(0);
            }

            // 4. Rerun and assert zero migrations applied
            int rerun = runner.apply(allMigrations.subList(0, 22));
            assertThat(rerun).isEqualTo(0);
            assertThat(runner.currentVersion()).isEqualTo(22);
        }
    }

    @Test
    @DisplayName("26. Step-by-step upgrade from V22 to V23 creates island boosters table")
    void stepByStepUpgradeFromV22ToV23CreatesIslandBoostersTable() throws Exception {
        try (Database db = DatabaseTestFixture.createSqliteInMemory()) {
            MigrationRunner runner = new MigrationRunner(db);

            // 1. Migrate up to V22
            List<Migration> allMigrations = SkyblockMigrations.getMigrations(db.dialect());
            int v22Applied = runner.apply(allMigrations.subList(0, 22));
            assertThat(v22Applied).isEqualTo(22);
            assertThat(runner.currentVersion()).isEqualTo(22);

            // 2. Upgrade by applying V23
            int v23Applied = runner.apply(allMigrations.subList(0, 23));
            assertThat(v23Applied).isEqualTo(1);
            assertThat(runner.currentVersion()).isEqualTo(23);

            // 3. Verify island_boosters accepts rows and foreign key cascade works
            try (Connection conn = db.connection()) {
                enableForeignKeys(conn);

                execute(
                        conn,
                        "INSERT INTO player_accounts (player_uuid) VALUES ('00000000-0000-0000-0000-000000000001');");
                execute(
                        conn,
                        "INSERT INTO player_profiles (profile_id, player_uuid, profile_type) VALUES ('00000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000001', 'CLASSIC');");
                execute(conn, """
                        INSERT INTO islands (
                            id, owner_profile_id, owner_account_uuid, custom_name,
                            lifecycle, economic_state, administrative_state, version
                        ) VALUES (
                            '88888888-8888-8888-8888-888888888888',
                            '00000000-0000-0000-0000-000000000002',
                            '00000000-0000-0000-0000-000000000001',
                            'Booster Island', 'ACTIVE', 'NORMAL', 'NORMAL', 1
                        );
                        """);

                execute(conn, """
                        INSERT INTO island_boosters (
                            booster_id, island_id, category, multiplier, expires_at, created_at, paused_at, remaining_seconds
                        ) VALUES (
                            'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb',
                            '88888888-8888-8888-8888-888888888888',
                            'SPAWNER_RATE', 2.0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, NULL, 0
                        );
                        """);

                assertThat(
                                queryCount(
                                        conn,
                                        "SELECT COUNT(*) FROM island_boosters WHERE booster_id = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb'"))
                        .isEqualTo(1);

                // Delete island and verify cascade deletes booster
                execute(conn, "DELETE FROM islands WHERE id = '88888888-8888-8888-8888-888888888888'");
                assertThat(
                                queryCount(
                                        conn,
                                        "SELECT COUNT(*) FROM island_boosters WHERE booster_id = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb'"))
                        .isEqualTo(0);
            }

            // 4. Rerun and assert zero migrations applied
            int rerun = runner.apply(allMigrations.subList(0, 23));
            assertThat(rerun).isEqualTo(0);
            assertThat(runner.currentVersion()).isEqualTo(23);
        }
    }

    @Test
    @DisplayName("27. Step-by-step upgrade from V23 to V24 creates island bankruptcies table")
    void stepByStepUpgradeFromV23ToV24CreatesIslandBankruptciesTable() throws Exception {
        try (Database db = DatabaseTestFixture.createSqliteInMemory()) {
            MigrationRunner runner = new MigrationRunner(db);

            // 1. Migrate up to V23
            List<Migration> allMigrations = SkyblockMigrations.getMigrations(db.dialect());
            int v23Applied = runner.apply(allMigrations.subList(0, 23));
            assertThat(v23Applied).isEqualTo(23);
            assertThat(runner.currentVersion()).isEqualTo(23);

            // 2. Upgrade by applying V24
            int v24Applied = runner.apply(allMigrations.subList(0, 24));
            assertThat(v24Applied).isEqualTo(1);
            assertThat(runner.currentVersion()).isEqualTo(24);

            // 3. Verify island_bankruptcies accepts rows and foreign key cascade works
            try (Connection conn = db.connection()) {
                enableForeignKeys(conn);

                execute(
                        conn,
                        "INSERT INTO player_accounts (player_uuid) VALUES ('00000000-0000-0000-0000-000000000001');");
                execute(
                        conn,
                        "INSERT INTO player_profiles (profile_id, player_uuid, profile_type) VALUES ('00000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000001', 'CLASSIC');");
                execute(conn, """
                        INSERT INTO islands (
                            id, owner_profile_id, owner_account_uuid, custom_name,
                            lifecycle, economic_state, administrative_state, version
                        ) VALUES (
                            '99999999-8888-7777-6666-555555555555',
                            '00000000-0000-0000-0000-000000000002',
                            '00000000-0000-0000-0000-000000000001',
                            'Bankruptcy Island', 'ACTIVE', 'NORMAL', 'NORMAL', 1
                        );""");

                execute(conn, """
                        INSERT INTO island_bankruptcies (
                            island_id, status, debt_minor_units, grace_until, updated_at
                        ) VALUES (
                            '99999999-8888-7777-6666-555555555555',
                            'GRACE', 50000, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                        );""");

                assertThat(queryCount(conn, "SELECT COUNT(*) FROM island_bankruptcies;"))
                        .isEqualTo(1);

                // Cascade delete when island is deleted
                execute(conn, "DELETE FROM islands WHERE id = '99999999-8888-7777-6666-555555555555';");
                assertThat(queryCount(conn, "SELECT COUNT(*) FROM island_bankruptcies;"))
                        .isEqualTo(0);
            }

            // 4. Rerun and assert zero migrations applied
            int rerun = runner.apply(allMigrations.subList(0, 24));
            assertThat(rerun).isEqualTo(0);
            assertThat(runner.currentVersion()).isEqualTo(24);
        }
    }

    @Test
    @DisplayName("28. Step-by-step upgrade from V24 to V25 creates game mode hierarchy tables")
    void stepByStepUpgradeFromV24ToV25CreatesGameModeHierarchyTables() throws Exception {
        try (Database db = DatabaseTestFixture.createSqliteInMemory()) {
            MigrationRunner runner = new MigrationRunner(db);

            // 1. Migrate up to V24
            List<Migration> allMigrations = SkyblockMigrations.getMigrations(db.dialect());
            int v24Applied = runner.apply(allMigrations.subList(0, 24));
            assertThat(v24Applied).isEqualTo(24);
            assertThat(runner.currentVersion()).isEqualTo(24);

            // 2. Upgrade by applying V25
            int v25Applied = runner.apply(allMigrations.subList(0, 25));
            assertThat(v25Applied).isEqualTo(1);
            assertThat(runner.currentVersion()).isEqualTo(25);

            // 3. Verify game_mode_instances and primary_gameplay_roots accept rows
            try (Connection conn = db.connection()) {
                enableForeignKeys(conn);

                execute(
                        conn,
                        "INSERT INTO player_accounts (player_uuid) VALUES ('00000000-0000-0000-0000-000000000001');");
                execute(
                        conn,
                        "INSERT INTO player_profiles (profile_id, player_uuid, profile_type) VALUES ('00000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000001', 'CLASSIC');");
                execute(
                        conn,
                        "INSERT INTO game_mode_instances (id, profile_id, game_mode_type, ruleset_config) "
                                + "VALUES ('test-inst-1', '00000000-0000-0000-0000-000000000002', 'SKYBLOCK', '{}');");
                execute(
                        conn,
                        "INSERT INTO primary_gameplay_roots (game_mode_instance_id, root_id, root_type) "
                                + "VALUES ('test-inst-1', '99999999-8888-7777-6666-555555555555', 'ISLAND');");

                try (Statement stmt = conn.createStatement();
                        ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM game_mode_instances;")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt(1)).isEqualTo(1);
                }
                try (Statement stmt = conn.createStatement();
                        ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM primary_gameplay_roots;")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt(1)).isEqualTo(1);
                }
            }

            // 4. Rerun and assert zero migrations applied
            int rerun = runner.apply(allMigrations.subList(0, 25));
            assertThat(rerun).isEqualTo(0);
            assertThat(runner.currentVersion()).isEqualTo(25);
        }
    }

    @Test
    @DisplayName(
            "29. Step-by-step upgrade from V25 to V26 creates enterprise activity events, notifications, and island homes tables")
    void stepByStepUpgradeFromV25ToV26CreatesEnterpriseTables() throws Exception {
        try (Database db = DatabaseTestFixture.createSqliteInMemory()) {
            MigrationRunner runner = new MigrationRunner(db);

            // 1. Migrate up to V25
            List<Migration> allMigrations = SkyblockMigrations.getMigrations(db.dialect());
            int v25Applied = runner.apply(allMigrations.subList(0, 25));
            assertThat(v25Applied).isEqualTo(25);
            assertThat(runner.currentVersion()).isEqualTo(25);

            // 2. Upgrade by applying V26
            int v26Applied = runner.apply(allMigrations.subList(0, 26));
            assertThat(v26Applied).isEqualTo(1);
            assertThat(runner.currentVersion()).isEqualTo(26);

            // 3. Verify activity_events, notifications, and island_homes accept rows
            try (Connection conn = db.connection()) {
                enableForeignKeys(conn);

                execute(
                        conn,
                        "INSERT INTO player_accounts (player_uuid) VALUES ('00000000-0000-0000-0000-000000000001');");
                execute(
                        conn,
                        "INSERT INTO player_profiles (profile_id, player_uuid, profile_type) VALUES ('00000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000001', 'CLASSIC');");
                execute(
                        conn,
                        "INSERT INTO islands (id, owner_profile_id, owner_account_uuid) VALUES ('11111111-2222-3333-4444-555555555555', '00000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000001');");

                execute(
                        conn,
                        "INSERT INTO activity_events (event_id, instance_id, actor_profile_id, event_type, visibility, payload_type_id, payload_schema_version, payload_data) "
                                + "VALUES ('evt-1', '11111111-2222-3333-4444-555555555555', '00000000-0000-0000-0000-000000000002', 'ISLAND_LEVEL_UP', 'PUBLIC', 'LEVEL_UP', 1, '{}');");

                execute(
                        conn,
                        "INSERT INTO notifications (notification_id, recipient_profile_id, category, payload_type_id, payload_schema_version, payload_data, is_read) "
                                + "VALUES ('notif-1', '00000000-0000-0000-0000-000000000002', 'SYSTEM', 'ANNOUNCEMENT', 1, '{}', 0);");

                execute(
                        conn,
                        "INSERT INTO island_homes (home_id, owner_profile_id, island_id, home_name, home_scope, world_name, x, y, z, yaw, pitch) "
                                + "VALUES ('home-1', '00000000-0000-0000-0000-000000000002', '11111111-2222-3333-4444-555555555555', 'default', 'ISLAND', 'skyblock_world', 10.5, 65.0, 20.5, 90.0, 0.0);");

                try (Statement stmt = conn.createStatement();
                        ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM activity_events;")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt(1)).isEqualTo(1);
                }
                try (Statement stmt = conn.createStatement();
                        ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM notifications;")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt(1)).isEqualTo(1);
                }
                try (Statement stmt = conn.createStatement();
                        ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM island_homes;")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt(1)).isEqualTo(1);
                }
            }

            // 4. Rerun and assert zero migrations applied
            int rerun = runner.apply(allMigrations.subList(0, 26));
            assertThat(rerun).isEqualTo(0);
            assertThat(runner.currentVersion()).isEqualTo(26);
        }
    }

    @Test
    @DisplayName("30. Step-by-step upgrade from V26 to V27 creates island dimensions table")
    void stepByStepUpgradeFromV26ToV27CreatesIslandDimensionsTable() throws Exception {
        try (Database db = DatabaseTestFixture.createSqliteInMemory()) {
            MigrationRunner runner = new MigrationRunner(db);

            // 1. Migrate up to V26
            List<Migration> allMigrations = SkyblockMigrations.getMigrations(db.dialect());
            int v26Applied = runner.apply(allMigrations.subList(0, 26));
            assertThat(v26Applied).isEqualTo(26);
            assertThat(runner.currentVersion()).isEqualTo(26);

            // 2. Upgrade by applying V27
            int v27Applied = runner.apply(allMigrations.subList(0, 27));
            assertThat(v27Applied).isEqualTo(1);
            assertThat(runner.currentVersion()).isEqualTo(27);

            // 3. Verify island_dimensions accepts rows
            try (Connection conn = db.connection()) {
                enableForeignKeys(conn);

                execute(
                        conn,
                        "INSERT INTO player_accounts (player_uuid) VALUES ('00000000-0000-0000-0000-000000000001');");
                execute(
                        conn,
                        "INSERT INTO player_profiles (profile_id, player_uuid, profile_type) VALUES ('00000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000001', 'CLASSIC');");
                execute(
                        conn,
                        "INSERT INTO islands (id, owner_profile_id, owner_account_uuid) VALUES ('11111111-2222-3333-4444-555555555555', '00000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000001');");
                execute(
                        conn,
                        "INSERT INTO island_dimensions (island_id, dimension_type) VALUES ('11111111-2222-3333-4444-555555555555', 'NETHER');");

                try (Statement stmt = conn.createStatement();
                        ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM island_dimensions;")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt(1)).isEqualTo(1);
                }
            }

            // 4. Rerun and assert zero migrations applied
            int rerun = runner.apply(allMigrations.subList(0, 27));
            assertThat(rerun).isEqualTo(0);
            assertThat(runner.currentVersion()).isEqualTo(27);
        }
    }

    @Test
    @DisplayName("31. Step-by-step upgrade from V27 to V28 creates profile cosmetics and island recycle operations")
    void stepByStepUpgradeFromV27ToV28CreatesCosmeticsAndRecycleTables() throws Exception {
        try (Database db = DatabaseTestFixture.createSqliteInMemory()) {
            MigrationRunner runner = new MigrationRunner(db);

            // 1. Migrate up to V27
            List<Migration> allMigrations = SkyblockMigrations.getMigrations(db.dialect());
            int v27Applied = runner.apply(allMigrations.subList(0, 27));
            assertThat(v27Applied).isEqualTo(27);
            assertThat(runner.currentVersion()).isEqualTo(27);

            // 2. Upgrade by applying V28
            int v28Applied = runner.apply(allMigrations.subList(0, 28));
            assertThat(v28Applied).isEqualTo(1);
            assertThat(runner.currentVersion()).isEqualTo(28);

            // 3. Verify profile_cosmetics and island_recycle_operations accept rows
            try (Connection conn = db.connection()) {
                enableForeignKeys(conn);

                execute(
                        conn,
                        "INSERT INTO player_accounts (player_uuid) VALUES ('00000000-0000-0000-0000-000000000001');");
                execute(
                        conn,
                        "INSERT INTO player_profiles (profile_id, player_uuid, profile_type) VALUES ('00000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000001', 'CLASSIC');");
                execute(
                        conn,
                        "INSERT INTO islands (id, owner_profile_id, owner_account_uuid) VALUES ('11111111-2222-3333-4444-555555555555', '00000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000001');");
                execute(
                        conn,
                        "INSERT INTO profile_cosmetics (profile_id, cosmetic_id, granted_by) VALUES ('00000000-0000-0000-0000-000000000002', 'trail_sparks', 'REWARD');");
                execute(
                        conn,
                        "INSERT INTO island_recycle_operations (operation_id, island_id, initiator_uuid, target_slot, state) VALUES ('22222222-3333-4444-5555-666666666666', '11111111-2222-3333-4444-555555555555', '00000000-0000-0000-0000-000000000001', 42, 'REQUESTED');");

                try (Statement stmt = conn.createStatement();
                        ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM profile_cosmetics;")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt(1)).isEqualTo(1);
                }
                try (Statement stmt = conn.createStatement();
                        ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM island_recycle_operations;")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt(1)).isEqualTo(1);
                }
            }

            // 4. Rerun and assert zero migrations applied
            int rerun = runner.apply(allMigrations.subList(0, 28));
            assertThat(rerun).isEqualTo(0);
            assertThat(runner.currentVersion()).isEqualTo(28);
        }
    }

    private static void enableForeignKeys(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA foreign_keys = ON;");
        }
    }

    private static void execute(Connection conn, String sql) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        }
    }

    private static int queryCount(Connection conn, String sql) throws SQLException {
        try (Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private static Set<String> getColumnNames(DatabaseMetaData meta, String tableName) throws SQLException {
        Set<String> columns = new HashSet<>();
        try (ResultSet rs = meta.getColumns(null, null, tableName, "%")) {
            while (rs.next()) {
                columns.add(rs.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
            }
        }
        return columns;
    }

    @Test
    @DisplayName("32. Step-by-step upgrade from V28 to V29 records an owed inventory purge and keeps existing records")
    void stepByStepUpgradeFromV28ToV29AddsTheOwedPurge() throws Exception {
        try (Database db = DatabaseTestFixture.createSqliteInMemory()) {
            MigrationRunner runner = new MigrationRunner(db);
            List<Migration> allMigrations = SkyblockMigrations.getMigrations(db.dialect());
            assertThat(runner.apply(allMigrations.subList(0, 28))).isEqualTo(28);
            try (java.sql.Connection conn = db.connection();
                    java.sql.Statement stmt = conn.createStatement()) {
                stmt.execute("INSERT INTO player_anti_abuse_records (player_uuid, resets_today_count) "
                        + "VALUES ('00000000-0000-0000-0000-00000000aaaa', 2)");
            }

            assertThat(runner.apply(allMigrations.subList(0, 29))).isEqualTo(1);
            assertThat(runner.currentVersion()).isEqualTo(29);

            try (java.sql.Connection conn = db.connection();
                    java.sql.Statement stmt = conn.createStatement();
                    java.sql.ResultSet rs = stmt.executeQuery(
                            "SELECT resets_today_count, inventory_purge_owed FROM player_anti_abuse_records")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).describedAs("the record kept").isEqualTo(2);
                assertThat(rs.getBoolean(2)).describedAs("nothing owed yet").isFalse();
            }
        }
    }

    @Test
    @DisplayName("33. Step-by-step upgrade from V29 to V30 records the upkeep period charged and keeps existing debts")
    void stepByStepUpgradeFromV29ToV30AddsTheUpkeepPeriod() throws Exception {
        try (Database db = DatabaseTestFixture.createSqliteInMemory()) {
            MigrationRunner runner = new MigrationRunner(db);
            List<Migration> allMigrations = SkyblockMigrations.getMigrations(db.dialect());
            assertThat(runner.apply(allMigrations.subList(0, 29))).isEqualTo(29);
            try (java.sql.Connection conn = db.connection();
                    java.sql.Statement stmt = conn.createStatement()) {
                stmt.execute(
                        "INSERT INTO player_accounts (player_uuid) VALUES ('00000000-0000-0000-0000-00000000aaaa')");
                stmt.execute("INSERT INTO player_profiles (profile_id, player_uuid) VALUES "
                        + "('00000000-0000-0000-0000-00000000bbbb', '00000000-0000-0000-0000-00000000aaaa')");
                stmt.execute(
                        "INSERT INTO islands (id, owner_account_uuid, owner_profile_id, lifecycle, created_at, updated_at) "
                                + "VALUES ('00000000-0000-0000-0000-00000000cccc', '00000000-0000-0000-0000-00000000aaaa', "
                                + "'00000000-0000-0000-0000-00000000bbbb', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
                stmt.execute("INSERT INTO island_bankruptcies (island_id, status, debt_minor_units) "
                        + "VALUES ('00000000-0000-0000-0000-00000000cccc', 'GRACE', 60000)");
            }

            assertThat(runner.apply(allMigrations.subList(0, 30))).isEqualTo(1);
            assertThat(runner.currentVersion()).isEqualTo(30);

            try (java.sql.Connection conn = db.connection();
                    java.sql.Statement stmt = conn.createStatement();
                    java.sql.ResultSet rs =
                            stmt.executeQuery("SELECT debt_minor_units, upkeep_period FROM island_bankruptcies")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getLong(1)).describedAs("the debt kept").isEqualTo(60_000L);
                assertThat(rs.getLong(2)).describedAs("no period charged yet").isEqualTo(-1L);
            }
        }
    }
}
