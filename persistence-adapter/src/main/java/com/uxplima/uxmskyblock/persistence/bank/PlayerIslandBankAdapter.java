package com.uxplima.uxmskyblock.persistence.bank;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmlib.storage.sql.Dialect;
import com.uxplima.uxmskyblock.core.application.bank.IslandBankPort;
import com.uxplima.uxmskyblock.core.domain.bank.BankTransaction;
import com.uxplima.uxmskyblock.core.domain.bank.BankTransactionOutcome;
import com.uxplima.uxmskyblock.core.domain.bank.IslandBank;
import com.uxplima.uxmskyblock.core.domain.event.StagedOutboxEvent;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.persistence.sql.DialectTransactions;
import com.uxplima.uxmskyblock.persistence.sql.SupportedDialects;
import org.jspecify.annotations.Nullable;

/**
 * Production SQL persistence adapter for Island Bank operations.
 *
 * <p>Enforces:
 * <ul>
 *   <li>Dual-mode transactions (SQLite {@code BEGIN IMMEDIATE} vs MariaDB/Postgres row-level locks)</li>
 *   <li>Authority epoch fencing via {@code island_authorities} row locks</li>
 *   <li>Atomic scoped idempotency reservation via {@code processed_operations}</li>
 *   <li>Optimistic concurrency control via monotonic version check on {@code island_banks}</li>
 *   <li>Exact minor units financial mathematics preventing negative balances</li>
 *   <li>Append-only audit trail logging into {@code bank_transactions}</li>
 * </ul>
 */
public final class PlayerIslandBankAdapter implements IslandBankPort {

    private final Database database;
    final Dialect dialect;
    final DialectTransactions tx;
    private final BankTransactionSteps steps = new BankTransactionSteps(this);
    private final SqlIslandBankLedger ledger;

    public PlayerIslandBankAdapter(Database database) {
        this.database = Objects.requireNonNull(database, "database");
        this.dialect = database.dialect();
        this.tx = new DialectTransactions(this.dialect);
        this.ledger = new SqlIslandBankLedger(database);
        SupportedDialects.require(dialect, "island bank persistence");
    }

    @Override
    public BankTransactionOutcome executeTransaction(
            IslandId islandId,
            UUID actorUuid,
            String currencyId,
            int currencyScale,
            long deltaAmountMinorUnits,
            String reason,
            String currentNode,
            long expectedEpoch,
            long expectedVersion,
            UUID operationId,
            String idempotencyKey) {
        return executeTransaction(
                islandId,
                actorUuid,
                currencyId,
                currencyScale,
                deltaAmountMinorUnits,
                reason,
                currentNode,
                expectedEpoch,
                expectedVersion,
                operationId,
                idempotencyKey,
                null);
    }

    @Override
    public BankTransactionOutcome executeTransaction(
            IslandId islandId,
            UUID actorUuid,
            String currencyId,
            int currencyScale,
            long deltaAmountMinorUnits,
            String reason,
            String currentNode,
            long expectedEpoch,
            long expectedVersion,
            UUID operationId,
            String idempotencyKey,
            @Nullable StagedOutboxEvent outboxEvent) {
        return executeTransaction(
                islandId,
                actorUuid,
                currencyId,
                currencyScale,
                deltaAmountMinorUnits,
                reason,
                currentNode,
                expectedEpoch,
                expectedVersion,
                operationId,
                idempotencyKey,
                "ISLAND_BANK",
                outboxEvent);
    }

    @Override
    public BankTransactionOutcome executeTransaction(
            IslandId islandId,
            UUID actorUuid,
            String currencyId,
            int currencyScale,
            long deltaAmountMinorUnits,
            String reason,
            String currentNode,
            long expectedEpoch,
            long expectedVersion,
            UUID operationId,
            String idempotencyKey,
            String operationScope,
            @Nullable StagedOutboxEvent outboxEvent) {
        return Objects.requireNonNull(transact(
                islandId,
                actorUuid,
                currencyId,
                currencyScale,
                deltaAmountMinorUnits,
                reason,
                currentNode,
                expectedEpoch,
                expectedVersion,
                operationId,
                idempotencyKey,
                operationScope,
                outboxEvent,
                null));
    }

    /**
     * The same charge, with {@code step} written in its transaction.
     *
     * @return the outcome, or {@code null} when the charge went through and {@code step} answered
     *     false, so both were rolled back and nothing happened
     */
    public @Nullable BankTransactionOutcome executeTransactionWith(
            IslandId islandId,
            UUID actorUuid,
            String currencyId,
            int currencyScale,
            long deltaAmountMinorUnits,
            String reason,
            String currentNode,
            long expectedEpoch,
            long expectedVersion,
            UUID operationId,
            String idempotencyKey,
            String operationScope,
            @Nullable StagedOutboxEvent outboxEvent,
            InTransaction step) {
        Objects.requireNonNull(step, "step");
        return transact(
                islandId,
                actorUuid,
                currencyId,
                currencyScale,
                deltaAmountMinorUnits,
                reason,
                currentNode,
                expectedEpoch,
                expectedVersion,
                operationId,
                idempotencyKey,
                operationScope,
                outboxEvent,
                step);
    }

    private @Nullable BankTransactionOutcome transact(
            IslandId islandId,
            UUID actorUuid,
            String currencyId,
            int currencyScale,
            long deltaAmountMinorUnits,
            String reason,
            String currentNode,
            long expectedEpoch,
            long expectedVersion,
            UUID operationId,
            String idempotencyKey,
            String operationScope,
            @Nullable StagedOutboxEvent outboxEvent,
            @Nullable InTransaction step) {

        Objects.requireNonNull(islandId, "islandId");
        Objects.requireNonNull(actorUuid, "actorUuid");
        Objects.requireNonNull(currencyId, "currencyId");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(currentNode, "currentNode");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");

        String effectiveScope =
                (operationScope != null && !operationScope.isBlank()) ? operationScope.trim() : "ISLAND_BANK";

        String upperCurrency = currencyId.trim().toUpperCase(Locale.ROOT);
        // Beside its three columns the bank holds every currency the operator lists, one row each, under the
        // operator's own id in lower case.
        boolean held =
                !upperCurrency.equals("PRIMARY") && !upperCurrency.equals("CRYSTALS") && !upperCurrency.equals("EXP");
        if (held) {
            upperCurrency = currencyId.trim().toLowerCase(Locale.ROOT);
            if (!com.uxplima.uxmskyblock.core.application.bank.BankCurrencies.isHeld(upperCurrency)) {
                throw new IllegalArgumentException("Unsupported currency ID: " + currencyId);
            }
        }

        try (Connection connection = database.connection()) {
            tx.begin(connection);
            try {
                // Step 1: Idempotency check on processed_operations
                BankTransactionOutcome.DuplicateOperation recorded =
                        recordedOperation(connection, effectiveScope, actorUuid, idempotencyKey, operationId);
                if (recorded != null) {
                    tx.rollbackQuietly(connection);
                    return recorded;
                }

                // Step 2: Insert PENDING reservation in processed_operations
                BankTransactionOutcome.DuplicateOperation winner =
                        steps.reserve(connection, operationId, effectiveScope, actorUuid, idempotencyKey, islandId);
                if (winner != null) {
                    return winner;
                }

                // Step 3: Authority lease verification with row locking
                BankTransactionOutcome.AuthorityRejected refused =
                        steps.refusedAuthority(connection, islandId, operationId, currentNode, expectedEpoch);
                if (refused != null) {
                    return refused;
                }

                // Step 4: Lock & read island_banks
                String bankSql = (dialect == Dialect.SQLITE)
                        ? "SELECT primary_balance_minor_units, crystals_balance, exp_balance, version FROM island_banks WHERE island_id = ?"
                        : "SELECT primary_balance_minor_units, crystals_balance, exp_balance, version FROM island_banks WHERE island_id = ? FOR UPDATE";

                long primary;
                long crystals;
                long exp;
                long actualVersion;

                try (PreparedStatement ps = connection.prepareStatement(bankSql)) {
                    ps.setString(1, islandId.value().toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            updateProcessedOp(connection, operationId, "REJECTED", "BANK_NOT_FOUND");
                            tx.commit(connection);
                            return new BankTransactionOutcome.BankNotFound("Bank not found for island " + islandId);
                        }
                        primary = rs.getLong("primary_balance_minor_units");
                        crystals = rs.getLong("crystals_balance");
                        exp = rs.getLong("exp_balance");
                        actualVersion = rs.getLong("version");
                    }
                }

                long heldBalance = held ? ledger.heldBalance(connection, islandId, upperCurrency) : 0L;

                // Check OCC version
                if (actualVersion != expectedVersion) {
                    updateProcessedOp(connection, operationId, "REJECTED", "STALE_OCC_VERSION");
                    tx.commit(connection);
                    return new BankTransactionOutcome.StaleVersion(expectedVersion, actualVersion);
                }

                // Balance check
                long currentBal = held
                        ? heldBalance
                        : switch (upperCurrency) {
                            case "PRIMARY" -> primary;
                            case "CRYSTALS" -> crystals;
                            case "EXP" -> exp;
                            default -> throw new IllegalStateException("Unexpected currency: " + upperCurrency);
                        };

                long newBal = currentBal + deltaAmountMinorUnits;
                if (newBal < 0) {
                    updateProcessedOp(connection, operationId, "REJECTED", "INSUFFICIENT_FUNDS");
                    tx.commit(connection);
                    return new BankTransactionOutcome.InsufficientFunds(currentBal, deltaAmountMinorUnits);
                }

                long newPrimary = upperCurrency.equals("PRIMARY") ? newBal : primary;
                long newCrystals = upperCurrency.equals("CRYSTALS") ? newBal : crystals;
                long newExp = upperCurrency.equals("EXP") ? newBal : exp;

                // Step 5: Update island_banks
                String updateBankSql = """
                        UPDATE island_banks
                        SET primary_balance_minor_units = ?,
                            crystals_balance = ?,
                            exp_balance = ?,
                            version = version + 1,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE island_id = ? AND version = ?
                        """;
                try (PreparedStatement ps = connection.prepareStatement(updateBankSql)) {
                    ps.setLong(1, newPrimary);
                    ps.setLong(2, newCrystals);
                    ps.setLong(3, newExp);
                    ps.setString(4, islandId.value().toString());
                    ps.setLong(5, expectedVersion);
                    int updated = ps.executeUpdate();
                    if (updated != 1) {
                        tx.rollbackQuietly(connection);
                        return new BankTransactionOutcome.StaleVersion(expectedVersion, actualVersion);
                    }
                }

                if (held) {
                    ledger.writeHeld(connection, islandId, upperCurrency, newBal);
                }

                // Step 5.5: The write this charge pays for, in the same transaction or not at all.
                if (step != null && !step.apply(connection)) {
                    tx.rollbackQuietly(connection);
                    return null;
                }

                // Step 6: Append bank_transactions audit record
                UUID txId = UUID.randomUUID();
                steps.writeAudit(
                        connection,
                        txId,
                        operationId,
                        islandId,
                        actorUuid,
                        upperCurrency,
                        currencyScale,
                        deltaAmountMinorUnits,
                        newBal,
                        reason,
                        expectedVersion + 1);

                // Step 7: Finalize processed_operations to APPLIED
                String resultPayload = BankTransactionSteps.resultPayload(
                        islandId,
                        actorUuid,
                        deltaAmountMinorUnits,
                        upperCurrency,
                        effectiveScope,
                        reason,
                        newBal,
                        txId);
                updateProcessedOp(connection, operationId, "APPLIED", "SUCCESS", resultPayload);

                // Step 7.5: Stage outbox event atomically in same transaction
                if (outboxEvent != null) {
                    com.uxplima.uxmskyblock.persistence.event.OutboxSqlHelper.stageEvent(connection, outboxEvent);
                }

                java.util.Map<String, Long> heldNow = ledger.heldBalances(connection, islandId);

                // Step 8: Commit transaction
                tx.commit(connection);

                IslandBank updatedBank = new IslandBank(
                        islandId, newPrimary, newCrystals, newExp, expectedVersion + 1, Instant.now(), heldNow);
                BankTransaction transaction = new BankTransaction(
                        txId,
                        operationId,
                        islandId,
                        actorUuid,
                        upperCurrency,
                        currencyScale,
                        deltaAmountMinorUnits,
                        newBal,
                        reason,
                        Instant.now());

                return new BankTransactionOutcome.Success(updatedBank, transaction);

            } catch (SQLException e) {
                tx.rollbackQuietly(connection);
                throw e;
            }
        } catch (SQLException e) {
            throw new IslandBankPersistenceException(
                    "Failed to execute bank transaction for island " + islandId + " (operation " + operationId + ")",
                    e);
        }
    }

    @Override
    public int purgeSettledOperationsBefore(java.time.Instant before) {
        Objects.requireNonNull(before, "before must not be null");
        // Settled only. A record that never settled is a reservation a crash left behind, and
        // deleting one is exactly what would let the operation it was reserving run a second time.
        String sql = "DELETE FROM processed_operations WHERE status IN ('APPLIED', 'REJECTED') "
                + "AND completed_at IS NOT NULL AND completed_at < ?";
        try (Connection connection = database.connection();
                PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setTimestamp(1, java.sql.Timestamp.from(before));
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new IslandBankPersistenceException("Failed to sweep settled bank operations", e);
        }
    }

    void updateProcessedOp(Connection connection, UUID operationId, String status, String resultCode)
            throws SQLException {
        updateProcessedOp(connection, operationId, status, resultCode, null);
    }

    private void updateProcessedOp(
            Connection connection, UUID operationId, String status, String resultCode, @Nullable String resultPayload)
            throws SQLException {
        String updateSql = (dialect == Dialect.POSTGRES) ? """
                UPDATE processed_operations
                SET status = ?, result_code = ?, result_payload = CAST(? AS jsonb), completed_at = CURRENT_TIMESTAMP
                WHERE operation_id = ?
                """ : """
                UPDATE processed_operations
                SET status = ?, result_code = ?, result_payload = ?, completed_at = CURRENT_TIMESTAMP
                WHERE operation_id = ?
                """;
        try (PreparedStatement ps = connection.prepareStatement(updateSql)) {
            ps.setString(1, status);
            ps.setString(2, resultCode);
            ps.setString(3, resultPayload);
            ps.setString(4, operationId.toString());
            ps.executeUpdate();
        }
    }

    @Override
    public Optional<IslandBank> findBankByIslandId(IslandId islandId) {
        return ledger.findBankByIslandId(islandId);
    }

    @Override
    public IslandBank createBank(IslandId islandId) {
        return ledger.createBank(islandId);
    }

    @Override
    public List<BankTransaction> getTransactionHistory(IslandId islandId, int limit) {
        return ledger.getTransactionHistory(islandId, limit);
    }

    /** What processed_operations already holds for this request, or null when it holds nothing. */
    static BankTransactionOutcome.@Nullable DuplicateOperation recordedOperation(
            Connection connection, String scope, UUID actorUuid, String idempotencyKey, UUID operationId)
            throws SQLException {
        String checkOpSql = """
                SELECT operation_id, status, result_code, result_payload
                FROM processed_operations
                WHERE (operation_scope = ? AND actor_id = ? AND idempotency_key = ?)
                   OR operation_id = ?
                """;
        try (PreparedStatement ps = connection.prepareStatement(checkOpSql)) {
            ps.setString(1, scope);
            ps.setString(2, actorUuid.toString());
            ps.setString(3, idempotencyKey);
            ps.setString(4, operationId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                UUID existingOpId = UUID.fromString(rs.getString("operation_id"));
                String status = rs.getString("status");
                String resultCode = rs.getString("result_code");
                String resultPayload = rs.getString("result_payload");
                return new BankTransactionOutcome.DuplicateOperation(
                        existingOpId,
                        "Operation already recorded with status " + status + " (" + resultCode + ")",
                        status,
                        resultCode,
                        resultPayload);
            }
        }
    }
}
