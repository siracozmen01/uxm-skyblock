package com.uxplima.uxmskyblock.persistence.inventory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.Objects;
import java.util.Optional;

import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmlib.storage.sql.Dialect;
import com.uxplima.uxmskyblock.core.application.inventory.ProfileInventoryCheckpointPort;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.inventory.ProfileInventoryMutationOutcome;
import com.uxplima.uxmskyblock.core.domain.inventory.ProfileInventoryRecord;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import com.uxplima.uxmskyblock.persistence.sql.SupportedDialects;

/**
 * Canonical SQL persistence adapter implementing {@link ProfileInventoryCheckpointPort}.
 *
 * <p>Durability Scope and Invariants:
 * <ul>
 *   <li>This adapter strictly implements routine ambient {@code CHECKPOINTED} snapshot persistence under Hybrid durability.</li>
 *   <li>It is strictly NOT an {@code InventoryMutationJournal} replacement.</li>
 *   <li>It is strictly NOT valid for {@code IMMEDIATE}, economic, or value-sensitive item mutation execution.</li>
 *   <li>Immediate, economic, and value-sensitive mutations remain strictly owned by the {@code InventoryMutationJournal}
 *       protocol and must not be downgraded or routed through this ambient checkpoint path.</li>
 * </ul>
 *
 * <p>Enforces the frozen transaction protocol:
 * <ul>
 *   <li>SQLite: writer serialization via {@code BEGIN IMMEDIATE} transactions.</li>
 *   <li>MariaDB / PostgreSQL: row lock serialization via {@code SELECT player_sessions ... FOR UPDATE}.</li>
 *   <li>Atomic authority check under the same transaction (node, epoch, ACTIVE state, active profile binding, DB clock lease).</li>
 *   <li>OCC mutation on {@code profile_inventories} updating version = version + 1.</li>
 *   <li>Asserts affectedRows == 1; aborts/rolls back on authority or version mismatch.</li>
 * </ul>
 */
public final class PlayerProfileInventoryAdapter implements ProfileInventoryCheckpointPort {

    private final Database database;
    private final Dialect dialect;

    private final String selectSessionAuthoritySql;
    private final String updateInventoryOccSql;
    private final String selectInventorySql;
    private final String insertInventorySql;

    public PlayerProfileInventoryAdapter(Database database) {
        this.database = Objects.requireNonNull(database, "database");
        this.dialect = database.dialect();
        SupportedDialects.require(dialect, "inventory persistence");

        this.selectSessionAuthoritySql = buildSelectSessionAuthoritySql(this.dialect);
        this.updateInventoryOccSql = buildUpdateInventoryOccSql();
        this.selectInventorySql = buildSelectInventorySql();
        this.insertInventorySql = buildInsertInventorySql();
    }

    static String buildSelectSessionAuthoritySql(Dialect dialect) {
        String base = "SELECT active_profile_id, authoritative_node, session_epoch, state, "
                + "(CASE WHEN lease_expires_at >= CURRENT_TIMESTAMP THEN 1 ELSE 0 END) AS lease_valid "
                + "FROM player_sessions "
                + "WHERE player_uuid = ?";
        return dialect == Dialect.SQLITE ? base : base + " FOR UPDATE";
    }

    static String buildUpdateInventoryOccSql() {
        return "UPDATE profile_inventories "
                + "SET profile_inventory_version = profile_inventory_version + 1, "
                + PlayerStateColumns.ASSIGNMENTS + ", "
                + "updated_at = CURRENT_TIMESTAMP "
                + "WHERE profile_id = ? "
                + "AND profile_inventory_version = ?";
    }

    private static String buildSelectInventorySql() {
        return "SELECT profile_id, profile_inventory_version, inventory_nbt, enderchest_nbt, "
                + "experience_points, health, food_level, saturation, active_potion_effects_nbt, "
                + "logout_world, logout_x, logout_y, logout_z, gamemode, flight_allowed "
                + "FROM profile_inventories "
                + "WHERE profile_id = ?";
    }

    private static String buildInsertInventorySql() {
        return "INSERT INTO profile_inventories ("
                + "profile_id, profile_inventory_version, inventory_nbt, enderchest_nbt, "
                + "experience_points, health, food_level, saturation, active_potion_effects_nbt, "
                + "logout_world, logout_x, logout_y, logout_z, gamemode, flight_allowed, updated_at"
                + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)";
    }

    @Override
    public ProfileInventoryMutationOutcome checkpointInventory(
            PlayerUuid playerUuid,
            ProfileId profileId,
            ServerNodeId currentNode,
            long expectedEpoch,
            long expectedVersion,
            ProfileInventoryRecord state) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(profileId, "profileId");
        Objects.requireNonNull(currentNode, "currentNode");
        Objects.requireNonNull(state, "state");
        if (!state.profileId().equals(profileId)) {
            throw new IllegalArgumentException(
                    "The state of " + state.profileId() + " cannot be written as " + profileId);
        }

        if (dialect == Dialect.SQLITE) {
            return executeSqliteCheckpoint(playerUuid, profileId, currentNode, expectedEpoch, expectedVersion, state);
        }
        return executeServerCheckpoint(playerUuid, profileId, currentNode, expectedEpoch, expectedVersion, state);
    }

    private ProfileInventoryMutationOutcome executeSqliteCheckpoint(
            PlayerUuid playerUuid,
            ProfileId profileId,
            ServerNodeId currentNode,
            long expectedEpoch,
            long expectedVersion,
            ProfileInventoryRecord state) {
        try (Connection conn = database.connection()) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("BEGIN IMMEDIATE");
            }
            try {
                boolean authorityValid = validateAuthority(conn, playerUuid, profileId, currentNode, expectedEpoch)
                        && !journalOwns(conn, profileId);
                if (!authorityValid) {
                    rollbackSqlite(conn);
                    return ProfileInventoryMutationOutcome.rejected();
                }

                int affected = updateOcc(conn, profileId, expectedVersion, state);
                if (affected == 1) {
                    try (Statement stmt = conn.createStatement()) {
                        stmt.execute("COMMIT");
                    }
                    return ProfileInventoryMutationOutcome.success(expectedVersion + 1);
                } else {
                    rollbackSqlite(conn);
                    return ProfileInventoryMutationOutcome.rejected();
                }
            } catch (Exception e) {
                rollbackSqlite(conn);
                throw e;
            }
        } catch (SQLException e) {
            throw new InventoryPersistenceException("Failed SQLite inventory checkpoint", e);
        }
    }

    private ProfileInventoryMutationOutcome executeServerCheckpoint(
            PlayerUuid playerUuid,
            ProfileId profileId,
            ServerNodeId currentNode,
            long expectedEpoch,
            long expectedVersion,
            ProfileInventoryRecord state) {
        try (Connection conn = database.connection()) {
            conn.setAutoCommit(false);
            try {
                boolean authorityValid = validateAuthority(conn, playerUuid, profileId, currentNode, expectedEpoch)
                        && !journalOwns(conn, profileId);
                if (!authorityValid) {
                    conn.rollback();
                    return ProfileInventoryMutationOutcome.rejected();
                }

                int affected = updateOcc(conn, profileId, expectedVersion, state);
                if (affected == 1) {
                    conn.commit();
                    return ProfileInventoryMutationOutcome.success(expectedVersion + 1);
                } else {
                    conn.rollback();
                    return ProfileInventoryMutationOutcome.rejected();
                }
            } catch (Exception e) {
                try {
                    conn.rollback();
                } catch (SQLException rollbackEx) {
                    e.addSuppressed(rollbackEx);
                }
                throw e;
            }
        } catch (SQLException e) {
            throw new InventoryPersistenceException("Failed server DB inventory checkpoint", e);
        }
    }

    private boolean validateAuthority(
            Connection conn, PlayerUuid playerUuid, ProfileId profileId, ServerNodeId currentNode, long expectedEpoch)
            throws SQLException {
        return authorityHolds(conn, selectSessionAuthoritySql, playerUuid, profileId, currentNode, expectedEpoch);
    }

    /**
     * Whether {@code currentNode} holds {@code playerUuid}'s session at {@code expectedEpoch}, active, leased and
     * playing {@code profileId}, read with {@code sql} from {@link #buildSelectSessionAuthoritySql}.
     */
    static boolean authorityHolds(
            Connection conn,
            String sql,
            PlayerUuid playerUuid,
            ProfileId profileId,
            ServerNodeId currentNode,
            long expectedEpoch)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerUuid.value().toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return false;
                }
                String activeProfileId = rs.getString("active_profile_id");
                String authNode = rs.getString("authoritative_node");
                long epoch = rs.getLong("session_epoch");
                String state = rs.getString("state");
                int leaseValid = rs.getInt("lease_valid");

                return profileId.value().toString().equals(activeProfileId)
                        && currentNode.value().equals(authNode)
                        && epoch == expectedEpoch
                        && "ACTIVE".equals(state)
                        && leaseValid == 1;
            }
        }
    }

    /**
     * Whether a journaled mutation has an intent open on this inventory.
     *
     * <p>While it does, the inventory in memory may hold an item the journal has not committed. A
     * checkpoint that wrote it moved the version under the commit, the commit was refused, the item
     * was taken back in memory and the journal aborted, and the durable inventory kept the item: a
     * crash before the next checkpoint gave the player the item and left the reward to be delivered
     * again. The journal owns the inventory until its intent settles; the checkpoint waits for the
     * next interval. It reads under the session row lock the intent also takes, so the two cannot
     * pass each other.
     *
     * <p>A side of a trade that recovery already put back is the player's again, though the trade
     * stays open until its other side is put back too.
     */
    static boolean journalOwns(Connection conn, ProfileId profileId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM inventory_mutation_journals j "
                + "JOIN inventory_mutation_participants p ON p.operation_id = j.operation_id "
                + "WHERE p.owner_root_id = ? AND j.state = 'INTENT' AND p.durable_apply_state <> 'REVERTED'")) {
            ps.setString(1, profileId.value().toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private int updateOcc(Connection conn, ProfileId profileId, long expectedVersion, ProfileInventoryRecord state)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(updateInventoryOccSql)) {
            int next = PlayerStateColumns.bind(ps, 1, state);
            ps.setString(next, profileId.value().toString());
            ps.setLong(next + 1, expectedVersion);
            return ps.executeUpdate();
        }
    }

    static void rollbackSqlite(Connection conn) {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("ROLLBACK");
        } catch (SQLException ignored) {
            // Already rolled back or connection closed
        }
    }

    @Override
    public Optional<ProfileInventoryRecord> loadInventory(ProfileId profileId) {
        Objects.requireNonNull(profileId, "profileId");
        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement(selectInventorySql)) {
            ps.setString(1, profileId.value().toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                ProfileId id = ProfileId.fromString(rs.getString("profile_id"));
                long version = rs.getLong("profile_inventory_version");
                byte[] inv = rs.getBytes("inventory_nbt");
                byte[] ec = rs.getBytes("enderchest_nbt");
                int xp = rs.getInt("experience_points");
                double hp = rs.getDouble("health");
                int food = rs.getInt("food_level");
                float sat = rs.getFloat("saturation");
                byte[] potion = rs.getBytes("active_potion_effects_nbt");
                String world = rs.getString("logout_world");
                double x = rs.getDouble("logout_x");
                Double logoutX = rs.wasNull() ? null : x;
                double y = rs.getDouble("logout_y");
                Double logoutY = rs.wasNull() ? null : y;
                double z = rs.getDouble("logout_z");
                Double logoutZ = rs.wasNull() ? null : z;
                String gamemode = rs.getString("gamemode");
                boolean flight = rs.getBoolean("flight_allowed");

                return Optional.of(new ProfileInventoryRecord(
                        id, version, inv, ec, xp, hp, food, sat, potion, world, logoutX, logoutY, logoutZ, gamemode,
                        flight));
            }
        } catch (SQLException e) {
            throw new InventoryPersistenceException("Failed to load profile inventory for " + profileId, e);
        }
    }

    @Override
    public void initializeInventory(ProfileInventoryRecord record) {
        Objects.requireNonNull(record, "record");
        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement(insertInventorySql)) {
            ps.setString(1, record.profileId().value().toString());
            ps.setLong(2, record.version());
            ps.setBytes(3, record.inventoryNbt());
            ps.setBytes(4, record.enderchestNbt());
            ps.setInt(5, record.experiencePoints());
            ps.setDouble(6, record.health());
            ps.setInt(7, record.foodLevel());
            ps.setFloat(8, record.saturation());

            byte[] potion = record.activePotionEffectsNbt();
            if (potion != null) {
                ps.setBytes(9, potion);
            } else {
                ps.setNull(9, Types.BINARY);
            }

            if (record.logoutWorld() != null) {
                ps.setString(10, record.logoutWorld());
            } else {
                ps.setNull(10, Types.VARCHAR);
            }

            if (record.logoutX() != null) {
                ps.setDouble(11, record.logoutX());
            } else {
                ps.setNull(11, Types.DOUBLE);
            }

            if (record.logoutY() != null) {
                ps.setDouble(12, record.logoutY());
            } else {
                ps.setNull(12, Types.DOUBLE);
            }

            if (record.logoutZ() != null) {
                ps.setDouble(13, record.logoutZ());
            } else {
                ps.setNull(13, Types.DOUBLE);
            }

            ps.setString(14, record.gamemode());
            ps.setBoolean(15, record.flightAllowed());

            ps.executeUpdate();
        } catch (SQLException e) {
            throw new InventoryPersistenceException(
                    "Failed to initialize profile inventory for " + record.profileId(), e);
        }
    }
}
