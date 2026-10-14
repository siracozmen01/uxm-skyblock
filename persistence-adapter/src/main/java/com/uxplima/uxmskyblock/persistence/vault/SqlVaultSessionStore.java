package com.uxplima.uxmskyblock.persistence.vault;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.inventory.PlayerStateWrite;
import com.uxplima.uxmskyblock.core.domain.vault.VaultEditSession;
import com.uxplima.uxmskyblock.core.domain.vault.VaultSessionId;
import com.uxplima.uxmskyblock.core.domain.vault.VaultSessionState;
import com.uxplima.uxmskyblock.persistence.inventory.PlayerStateColumns;
import org.jspecify.annotations.Nullable;

/**
 * Who is holding a vault page, and what happens when they let go.
 *
 * <p>Split out of the storage adapter with the escrow journal, because the three answered different
 * questions in one file: what is in a page, who is holding it, and what was attempted. This is the
 * lease: acquiring it, committing under it, aborting it, and finding the ones that ran out.
 */
public final class SqlVaultSessionStore {

    /** An edit session of the island vault ended without its changes: the session closed and every slot it held let go. */
    private final VaultSessionAborts aborts = new VaultSessionAborts(this);

    final Database database;

    public SqlVaultSessionStore(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    public Optional<VaultEditSession> acquireEditSession(
            IslandId islandId, int page, UUID playerUuid, Duration leaseDuration) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(playerUuid, "playerUuid must not be null");
        Objects.requireNonNull(leaseDuration, "leaseDuration must not be null");

        String selectPageSql = """
                SELECT page_version, lease_epoch, active_session_id
                FROM island_vault_pages
                WHERE island_id = ? AND page = ?
                """;

        String checkSessionSql = """
                SELECT state, expires_at
                FROM vault_edit_sessions
                WHERE session_id = ?
                """;

        String abortSessionSql = """
                UPDATE vault_edit_sessions
                SET state = 'ABORTED', closed_at = CURRENT_TIMESTAMP
                WHERE session_id = ? AND state = 'ACTIVE'
                """;

        String updatePageSql = """
                UPDATE island_vault_pages
                SET lease_epoch = ?, active_session_id = ?, updated_at = CURRENT_TIMESTAMP
                WHERE island_id = ? AND page = ? AND lease_epoch = ?
                """;

        String insertSessionSql = """
                INSERT INTO vault_edit_sessions (
                    session_id, island_id, page, player_uuid, lease_epoch, base_page_version, state, opened_at, expires_at
                ) VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?)
                """;

        try (Connection conn = database.connection()) {
            boolean autoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                long currentVersion;
                long currentEpoch;
                String activeSessionStr = null;

                try (PreparedStatement selectStmt = conn.prepareStatement(selectPageSql)) {
                    selectStmt.setString(1, islandId.value().toString());
                    selectStmt.setInt(2, page);
                    try (ResultSet rs = selectStmt.executeQuery()) {
                        if (!rs.next()) {
                            conn.rollback();
                            return Optional.empty();
                        }
                        currentVersion = rs.getLong("page_version");
                        currentEpoch = rs.getLong("lease_epoch");
                        activeSessionStr = rs.getString("active_session_id");
                    }
                }

                Instant now = Instant.now();

                // If currently locked, check whether active session is expired
                if (activeSessionStr != null) {
                    try (PreparedStatement checkStmt = conn.prepareStatement(checkSessionSql)) {
                        checkStmt.setString(1, activeSessionStr);
                        try (ResultSet rs = checkStmt.executeQuery()) {
                            if (rs.next()) {
                                String state = rs.getString("state");
                                Timestamp expTs = rs.getTimestamp("expires_at");
                                Instant expiresAt = expTs.toInstant();
                                if ("ACTIVE".equals(state) && expiresAt.isAfter(now)) {
                                    // Session is still active and valid - locked!
                                    conn.rollback();
                                    return Optional.empty();
                                }
                            }
                        }
                    }

                    // Previous session is expired or not active; mark it ABORTED
                    try (PreparedStatement abortStmt = conn.prepareStatement(abortSessionSql)) {
                        abortStmt.setString(1, activeSessionStr);
                        abortStmt.executeUpdate();
                    }
                }

                // Acquire lease: increment epoch, bind session
                VaultSessionId newSessionId = VaultSessionId.random();
                long nextEpoch = currentEpoch + 1;
                Instant expiresAt = now.plus(leaseDuration);

                int updatedRows;
                try (PreparedStatement updateStmt = conn.prepareStatement(updatePageSql)) {
                    updateStmt.setLong(1, nextEpoch);
                    updateStmt.setString(2, newSessionId.toString());
                    updateStmt.setString(3, islandId.value().toString());
                    updateStmt.setInt(4, page);
                    updateStmt.setLong(5, currentEpoch);
                    updatedRows = updateStmt.executeUpdate();
                }

                if (updatedRows == 0) {
                    // Concurrent lease acquisition race condition
                    conn.rollback();
                    return Optional.empty();
                }

                try (PreparedStatement insertStmt = conn.prepareStatement(insertSessionSql)) {
                    insertStmt.setString(1, newSessionId.toString());
                    insertStmt.setString(2, islandId.value().toString());
                    insertStmt.setInt(3, page);
                    insertStmt.setString(4, playerUuid.toString());
                    insertStmt.setLong(5, nextEpoch);
                    insertStmt.setLong(6, currentVersion);
                    insertStmt.setTimestamp(7, Timestamp.from(now));
                    insertStmt.setTimestamp(8, Timestamp.from(expiresAt));
                    insertStmt.executeUpdate();
                }

                conn.commit();

                VaultEditSession session = new VaultEditSession(
                        newSessionId,
                        islandId,
                        page,
                        playerUuid,
                        nextEpoch,
                        currentVersion,
                        VaultSessionState.ACTIVE,
                        null,
                        now,
                        expiresAt,
                        null);
                return Optional.of(session);
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to acquire edit session for island " + islandId + " page " + page, e);
        }
    }

    public Optional<VaultEditSession> findSession(VaultSessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        String sql = """
                SELECT session_id, island_id, page, player_uuid, lease_epoch, base_page_version, state, escrow_journal, opened_at, expires_at, closed_at
                FROM vault_edit_sessions
                WHERE session_id = ?
                """;

        try (Connection conn = database.connection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, sessionId.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapVaultEditSession(rs));
                }
            }
            return Optional.empty();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find vault session " + sessionId, e);
        }
    }

    public boolean commitEditSession(
            VaultSessionId sessionId,
            byte[] newContentsNbt,
            String modifiedBy,
            @Nullable PlayerStateWrite playerState) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(newContentsNbt, "newContentsNbt must not be null");
        Objects.requireNonNull(modifiedBy, "modifiedBy must not be null");

        String selectSessionSql = """
                SELECT island_id, page, lease_epoch, base_page_version, state, expires_at
                FROM vault_edit_sessions
                WHERE session_id = ?
                """;

        String updatePageSql = """
                UPDATE island_vault_pages
                SET contents_nbt = ?,
                    page_version = page_version + 1,
                    active_session_id = NULL,
                    last_modified_by = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE island_id = ?
                  AND page = ?
                  AND page_version = ?
                  AND lease_epoch = ?
                  AND active_session_id = ?
                """;

        String updateProfileInvSql = "UPDATE profile_inventories SET profile_inventory_version = "
                + "profile_inventory_version + 1, " + PlayerStateColumns.ASSIGNMENTS
                + ", updated_at = CURRENT_TIMESTAMP WHERE profile_id = ? AND profile_inventory_version = ?";

        String commitSessionSql = """
                UPDATE vault_edit_sessions
                SET state = 'COMMITTED',
                    closed_at = CURRENT_TIMESTAMP
                WHERE session_id = ? AND state = 'ACTIVE'
                """;

        String commitTransfersSql = """
                UPDATE vault_escrow_transfers
                SET state = 'COMMITTED',
                    updated_at = CURRENT_TIMESTAMP
                WHERE session_id = ? AND state IN ('INTENT', 'APPLYING', 'APPLIED')
                """;

        try (Connection conn = database.connection()) {
            boolean autoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                String islandIdStr;
                int page;
                long leaseEpoch;
                long basePageVersion;
                String state;
                Timestamp expTs;

                try (PreparedStatement selectStmt = conn.prepareStatement(selectSessionSql)) {
                    selectStmt.setString(1, sessionId.toString());
                    try (ResultSet rs = selectStmt.executeQuery()) {
                        if (!rs.next()) {
                            conn.rollback();
                            return false;
                        }
                        islandIdStr = rs.getString("island_id");
                        page = rs.getInt("page");
                        leaseEpoch = rs.getLong("lease_epoch");
                        basePageVersion = rs.getLong("base_page_version");
                        state = rs.getString("state");
                        expTs = rs.getTimestamp("expires_at");
                    }
                }

                if (!"ACTIVE".equals(state) || expTs.toInstant().isBefore(Instant.now())) {
                    conn.rollback();
                    return false;
                }

                // 0. The player's session, locked first as every write of player state locks it, and
                // still this node's, at this epoch, active and leased.
                if (playerState != null && !playerSessionHolds(conn, playerState)) {
                    conn.rollback();
                    return false;
                }

                // 1. Atomic CAS update on island_vault_pages
                int updatedPageRows;
                try (PreparedStatement updatePageStmt = conn.prepareStatement(updatePageSql)) {
                    updatePageStmt.setBytes(1, newContentsNbt);
                    updatePageStmt.setString(2, modifiedBy);
                    updatePageStmt.setString(3, islandIdStr);
                    updatePageStmt.setInt(4, page);
                    updatePageStmt.setLong(5, basePageVersion);
                    updatePageStmt.setLong(6, leaseEpoch);
                    updatePageStmt.setString(7, sessionId.toString());
                    updatedPageRows = updatePageStmt.executeUpdate();
                }

                if (updatedPageRows == 0) {
                    conn.rollback();
                    return false;
                }

                // 2. The player's whole state, in the same transaction as the page: what left the page
                // is in their inventory and what entered it is not, and neither is written alone.
                if (playerState != null) {
                    try (PreparedStatement invStmt = conn.prepareStatement(updateProfileInvSql)) {
                        int next = PlayerStateColumns.bind(invStmt, 1, playerState.state());
                        invStmt.setString(
                                next, playerState.state().profileId().value().toString());
                        invStmt.setLong(next + 1, playerState.expectedVersion());
                        if (invStmt.executeUpdate() == 0) {
                            conn.rollback();
                            return false;
                        }
                    }
                    try (PreparedStatement versionStmt = conn.prepareStatement(
                            "UPDATE player_sessions SET last_durable_inventory_version = ?, updated_at = "
                                    + "CURRENT_TIMESTAMP WHERE player_uuid = ?")) {
                        versionStmt.setLong(1, playerState.writtenVersion());
                        versionStmt.setString(
                                2, playerState.playerUuid().value().toString());
                        versionStmt.executeUpdate();
                    }
                }

                // 3. Mark session COMMITTED
                int sessionRows;
                try (PreparedStatement commitSessionStmt = conn.prepareStatement(commitSessionSql)) {
                    commitSessionStmt.setString(1, sessionId.toString());
                    sessionRows = commitSessionStmt.executeUpdate();
                }

                if (sessionRows == 0) {
                    conn.rollback();
                    return false;
                }

                // 4. Finalize associated escrow transfers
                try (PreparedStatement transfersStmt = conn.prepareStatement(commitTransfersSql)) {
                    transfersStmt.setString(1, sessionId.toString());
                    transfersStmt.executeUpdate();
                }

                conn.commit();
                return true;
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to commit vault edit session " + sessionId, e);
        }
    }

    /** Whether the session behind {@code write} is still the writer's, read under its row lock. */
    private boolean playerSessionHolds(Connection conn, PlayerStateWrite write) throws SQLException {
        String select = "SELECT active_profile_id, authoritative_node, session_epoch, state, "
                + "(CASE WHEN lease_expires_at >= CURRENT_TIMESTAMP THEN 1 ELSE 0 END) AS lease_valid "
                + "FROM player_sessions WHERE player_uuid = ?"
                + (database.dialect() == com.uxplima.uxmlib.storage.sql.Dialect.SQLITE ? "" : " FOR UPDATE");
        try (PreparedStatement ps = conn.prepareStatement(select)) {
            ps.setString(1, write.playerUuid().value().toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next()
                        && write.state().profileId().value().toString().equals(rs.getString("active_profile_id"))
                        && write.node().value().equals(rs.getString("authoritative_node"))
                        && rs.getLong("session_epoch") == write.sessionEpoch()
                        && "ACTIVE".equals(rs.getString("state"))
                        && rs.getInt("lease_valid") == 1;
            }
        }
    }

    public List<VaultEditSession> findExpiredActiveSessions() {
        // The cutoff is bound rather than written as CURRENT_TIMESTAMP, because expires_at was
        // written with setTimestamp and CURRENT_TIMESTAMP is the database's own clock in the
        // database's own zone. Comparing the two compares two clocks: under SQLite the driver
        // stores the timestamp as a number and CURRENT_TIMESTAMP is text, so every live session
        // read as expired, and on a server whose zone is not the JVM's the lease was off by the
        // difference. Binding the cutoff sends both sides through the same conversion.
        String sql = """
                SELECT session_id, island_id, page, player_uuid, lease_epoch, base_page_version, state, escrow_journal, opened_at, expires_at, closed_at
                FROM vault_edit_sessions
                WHERE state = 'ACTIVE' AND expires_at < ?
                """;

        try (Connection conn = database.connection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setTimestamp(1, Timestamp.from(Instant.now()));
            try (ResultSet rs = stmt.executeQuery()) {
                List<VaultEditSession> list = new ArrayList<>();
                while (rs.next()) {
                    list.add(mapVaultEditSession(rs));
                }
                return list;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query expired active vault edit sessions", e);
        }
    }

    private static VaultEditSession mapVaultEditSession(ResultSet rs) throws SQLException {
        Timestamp closedTs = rs.getTimestamp("closed_at");
        Instant closedAt = closedTs != null ? closedTs.toInstant() : null;
        return new VaultEditSession(
                VaultSessionId.fromString(rs.getString("session_id")),
                IslandId.fromString(rs.getString("island_id")),
                rs.getInt("page"),
                UUID.fromString(rs.getString("player_uuid")),
                rs.getLong("lease_epoch"),
                rs.getLong("base_page_version"),
                VaultSessionState.valueOf(rs.getString("state")),
                rs.getString("escrow_journal"),
                rs.getTimestamp("opened_at").toInstant(),
                rs.getTimestamp("expires_at").toInstant(),
                closedAt);
    }

    public boolean abortEditSession(VaultSessionId sessionId) {
        return aborts.abortEditSession(sessionId);
    }
}
