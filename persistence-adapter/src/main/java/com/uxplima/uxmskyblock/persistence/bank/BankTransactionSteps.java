package com.uxplima.uxmskyblock.persistence.bank;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.UUID;

import com.google.gson.JsonObject;
import com.uxplima.uxmlib.storage.sql.Dialect;
import com.uxplima.uxmskyblock.core.domain.bank.BankTransactionOutcome;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.persistence.sql.UniqueViolations;
import org.jspecify.annotations.Nullable;

/**
 * The steps of one bank transaction that stand on their own, run inside the transaction {@link
 * PlayerIslandBankAdapter} holds open: the operation reserved under its idempotency key, the island's authority
 * checked under a row lock, the audit row written and the payload the applied operation keeps. Split out of the
 * adapter so neither file is over the size the standards draw a line at.
 */
final class BankTransactionSteps {

    private final PlayerIslandBankAdapter host;

    BankTransactionSteps(PlayerIslandBankAdapter host) {
        this.host = host;
    }

    /**
     * Reserves the operation as pending. When another request with the same scope, actor and key reserved it first,
     * answers with what that one recorded; otherwise null, and the reservation stands.
     */
    BankTransactionOutcome.@Nullable DuplicateOperation reserve(
            Connection connection,
            UUID operationId,
            String effectiveScope,
            UUID actorUuid,
            String idempotencyKey,
            IslandId islandId)
            throws SQLException {
        String insertOpSql = """
                INSERT INTO processed_operations (
                    operation_id, operation_scope, actor_id, idempotency_key,
                    operation_type, resource_id, status, created_at
                ) VALUES (?, ?, ?, ?, 'BANK_TRANSACTION', ?, 'PENDING', CURRENT_TIMESTAMP)
                """;
        try (PreparedStatement ps = connection.prepareStatement(insertOpSql)) {
            ps.setString(1, operationId.toString());
            ps.setString(2, effectiveScope);
            ps.setString(3, actorUuid.toString());
            ps.setString(4, idempotencyKey);
            ps.setString(5, islandId.value().toString());
            ps.executeUpdate();
        } catch (SQLException collided) {
            // Another request with the same scope, actor and key reserved it between the check
            // above and this insert, and the engine held this one on the unique index until that
            // one committed. That is the same request, not a failure: roll this draft back and
            // answer with what the other one recorded. It used to surface as a persistence
            // error, so a caller retrying one deposit got a server error for its own retry.
            if (!UniqueViolations.isUniqueViolation(collided)) {
                throw collided;
            }
            host.tx.rollbackQuietly(connection);
            BankTransactionOutcome.DuplicateOperation winner = PlayerIslandBankAdapter.recordedOperation(
                    connection, effectiveScope, actorUuid, idempotencyKey, operationId);
            host.tx.rollbackQuietly(connection);
            if (winner != null) {
                return winner;
            }
            throw collided;
        }
        return null;
    }

    /**
     * Checks under a row lock that this node holds the island's authority at the epoch expected and that its lease
     * is still good. A refusal is recorded and committed and returned; null means the transaction may go on.
     */
    BankTransactionOutcome.@Nullable AuthorityRejected refusedAuthority(
            Connection connection, IslandId islandId, UUID operationId, String currentNode, long expectedEpoch)
            throws SQLException {
        String authSql = (host.dialect == Dialect.SQLITE)
                ? "SELECT authoritative_node, authority_epoch, lease_expires_at, CURRENT_TIMESTAMP AS db_now FROM island_authorities WHERE island_id = ?"
                : "SELECT authoritative_node, authority_epoch, lease_expires_at, CURRENT_TIMESTAMP AS db_now FROM island_authorities WHERE island_id = ? FOR UPDATE";

        try (PreparedStatement ps = connection.prepareStatement(authSql)) {
            ps.setString(1, islandId.value().toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    host.updateProcessedOp(connection, operationId, "REJECTED", "AUTHORITY_NOT_FOUND");
                    host.tx.commit(connection);
                    return new BankTransactionOutcome.AuthorityRejected(
                            BankTransactionOutcome.AuthorityRejected.Kind.NO_AUTHORITY,
                            "Island authority record not found");
                }

                String authoritativeNode = rs.getString("authoritative_node");
                long authorityEpoch = rs.getLong("authority_epoch");
                Timestamp leaseExpiresAt = rs.getTimestamp("lease_expires_at");
                Timestamp dbNow = rs.getTimestamp("db_now");

                if (!authoritativeNode.equals(currentNode)) {
                    host.updateProcessedOp(connection, operationId, "REJECTED", "AUTHORITY_NODE_MISMATCH");
                    host.tx.commit(connection);
                    return new BankTransactionOutcome.AuthorityRejected(
                            BankTransactionOutcome.AuthorityRejected.Kind.NO_AUTHORITY,
                            "Authority lease held by node: " + authoritativeNode);
                }

                if (authorityEpoch != expectedEpoch) {
                    host.updateProcessedOp(connection, operationId, "REJECTED", "STALE_AUTHORITY_EPOCH");
                    host.tx.commit(connection);
                    return new BankTransactionOutcome.AuthorityRejected(
                            BankTransactionOutcome.AuthorityRejected.Kind.NO_AUTHORITY,
                            "Stale authority epoch: expected " + expectedEpoch + " but was " + authorityEpoch);
                }

                if (leaseExpiresAt == null || leaseExpiresAt.before(dbNow)) {
                    host.updateProcessedOp(connection, operationId, "REJECTED", "AUTHORITY_LEASE_EXPIRED");
                    host.tx.commit(connection);
                    return new BankTransactionOutcome.AuthorityRejected(
                            BankTransactionOutcome.AuthorityRejected.Kind.NO_AUTHORITY, "Authority lease has expired");
                }
            }
        }
        return null;
    }

    /** Appends the audit row of one applied move, at the version the bank row reached with it. */
    void writeAudit(
            Connection connection,
            UUID txId,
            UUID operationId,
            IslandId islandId,
            UUID actorUuid,
            String upperCurrency,
            int currencyScale,
            long deltaAmountMinorUnits,
            long newBal,
            String reason,
            long bankVersion)
            throws SQLException {
        String insertTxSql = """
                INSERT INTO bank_transactions (
                    transaction_id, operation_id, island_id, actor_uuid,
                    currency_id, currency_scale, delta_amount_minor_units,
                    resulting_balance_minor_units, reason, bank_version, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """;
        try (PreparedStatement ps = connection.prepareStatement(insertTxSql)) {
            ps.setString(1, txId.toString());
            ps.setString(2, operationId.toString());
            ps.setString(3, islandId.value().toString());
            ps.setString(4, actorUuid.toString());
            ps.setString(5, upperCurrency);
            ps.setInt(6, currencyScale);
            ps.setLong(7, deltaAmountMinorUnits);
            ps.setLong(8, newBal);
            ps.setString(9, reason);
            // The version the bank row reached with this move, one more than the one it was read at.
            ps.setLong(10, bankVersion);
            ps.executeUpdate();
        }
    }

    /** The payload an applied operation keeps, which a retry of the same request is answered with. */
    static String resultPayload(
            IslandId islandId,
            UUID actorUuid,
            long deltaAmountMinorUnits,
            String upperCurrency,
            String effectiveScope,
            String reason,
            long newBal,
            UUID txId) {
        JsonObject payloadObj = new JsonObject();
        payloadObj.addProperty("status", "SUCCESS");
        payloadObj.addProperty("islandId", islandId.value().toString());
        payloadObj.addProperty("actorId", actorUuid.toString());
        payloadObj.addProperty("amount", deltaAmountMinorUnits);
        payloadObj.addProperty("currencyId", upperCurrency);
        payloadObj.addProperty("scope", effectiveScope);
        payloadObj.addProperty("reason", reason);
        payloadObj.addProperty("newBalanceMinorUnits", newBal);
        payloadObj.addProperty("transactionId", txId.toString());
        return payloadObj.toString();
    }
}
