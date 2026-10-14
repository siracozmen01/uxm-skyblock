package com.uxplima.uxmskyblock.persistence.vault;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;

import com.uxplima.uxmskyblock.core.domain.vault.VaultSessionId;

/**
 * An edit session of the island vault ended without its changes: the session closed and every slot it held let go. Split out of {@link SqlVaultSessionStore}, whose services and words it uses, so neither file is over the size the
 * standards draw a line at.
 */
final class VaultSessionAborts {

    private final SqlVaultSessionStore host;

    VaultSessionAborts(SqlVaultSessionStore host) {
        this.host = host;
    }

    public boolean abortEditSession(VaultSessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");

        String selectSessionSql = """
                SELECT island_id, page
                FROM vault_edit_sessions
                WHERE session_id = ?
                """;

        String abortSessionSql = """
                UPDATE vault_edit_sessions
                SET state = 'ABORTED',
                    closed_at = CURRENT_TIMESTAMP
                WHERE session_id = ? AND state = 'ACTIVE'
                """;

        String unlockPageSql = """
                UPDATE island_vault_pages
                SET active_session_id = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE island_id = ? AND page = ? AND active_session_id = ?
                """;

        String abortTransfersSql = """
                UPDATE vault_escrow_transfers
                SET state = 'ABORTED',
                    updated_at = CURRENT_TIMESTAMP
                WHERE session_id = ? AND state NOT IN ('COMMITTED', 'ABORTED', 'RECOVERY_REQUIRED')
                """;

        try (Connection conn = host.database.connection()) {
            boolean autoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                String islandId;
                int page;

                try (PreparedStatement selectStmt = conn.prepareStatement(selectSessionSql)) {
                    selectStmt.setString(1, sessionId.toString());
                    try (ResultSet rs = selectStmt.executeQuery()) {
                        if (!rs.next()) {
                            conn.rollback();
                            return false;
                        }
                        islandId = rs.getString("island_id");
                        page = rs.getInt("page");
                    }
                }

                try (PreparedStatement abortStmt = conn.prepareStatement(abortSessionSql)) {
                    abortStmt.setString(1, sessionId.toString());
                    abortStmt.executeUpdate();
                }

                try (PreparedStatement unlockStmt = conn.prepareStatement(unlockPageSql)) {
                    unlockStmt.setString(1, islandId);
                    unlockStmt.setInt(2, page);
                    unlockStmt.setString(3, sessionId.toString());
                    unlockStmt.executeUpdate();
                }

                try (PreparedStatement transfersStmt = conn.prepareStatement(abortTransfersSql)) {
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
            throw new RuntimeException("Failed to abort vault edit session " + sessionId, e);
        }
    }
}
