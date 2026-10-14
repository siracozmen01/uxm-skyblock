package com.uxplima.uxmskyblock.core.domain.bank;

import java.util.Objects;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * Sealed algebraic data type representing the exhaustive outcome of an island bank transaction.
 */
public sealed interface BankTransactionOutcome {

    /** Transaction completed successfully, updating balance and appending audit trail. */
    record Success(IslandBank updatedBank, BankTransaction transaction) implements BankTransactionOutcome {
        public Success {
            Objects.requireNonNull(updatedBank, "updatedBank");
            Objects.requireNonNull(transaction, "transaction");
        }
    }

    /** Transaction rejected due to insufficient funds (overdraft prevented). */
    record InsufficientFunds(long currentBalance, long requestedDelta) implements BankTransactionOutcome {}

    /** Transaction rejected due to optimistic concurrency version mismatch. */
    record StaleVersion(long expectedVersion, long actualVersion) implements BankTransactionOutcome {}

    /**
     * Transaction refused before or after it touched the bank.
     *
     * <p>The reason is a sentence for the log and for a developer reading the API. It is English and
     * it can hold an island's id, so it is never what a player reads: the {@link Kind} is, and the
     * player's line comes out of the catalogue by it.
     */
    record AuthorityRejected(Kind kind, String reason) implements BankTransactionOutcome {
        public AuthorityRejected {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(reason, "reason");
        }

        /** A refusal nobody has classified yet. */
        public AuthorityRejected(String reason) {
            this(Kind.OTHER, reason);
        }

        /** What a refusal was about, which is what the player is told. */
        public enum Kind {
            /** The player belongs to no island. */
            NO_ISLAND,
            /** Another server holds the island, or the lease on it has run out. */
            NO_AUTHORITY,
            /** The amount was zero or less. */
            INVALID_AMOUNT,
            /** The player's own wallet would not pay or would not take the money, and nothing moved. */
            WALLET_REFUSED,
            /** Money left one side, could not reach the other, and could not be put back. */
            REFUND_FAILED,
            /** The server has no economy plugin, so there is no wallet to take from or give to. */
            NO_ECONOMY,
            /** The island bank holds as much as its bank limit lets it, so nothing more goes in. */
            BANK_FULL,
            /**
             * The database did not answer whether the move landed. The saga that asked is left as it
             * stood and is settled from its journal, and nothing is paid back in the meantime.
             */
            OUTCOME_UNKNOWN,
            /** Anything else. */
            OTHER
        }
    }

    /** Transaction was already applied previously with the same operation ID / idempotency key. */
    record DuplicateOperation(
            UUID operationId,
            String message,
            @Nullable String status,
            @Nullable String resultCode,
            @Nullable String resultPayload)
            implements BankTransactionOutcome {
        public DuplicateOperation(UUID operationId, String message) {
            this(operationId, message, null, null, null);
        }

        public DuplicateOperation {
            Objects.requireNonNull(operationId, "operationId");
            Objects.requireNonNull(message, "message");
        }
    }

    /** Island bank account was not found. */
    record BankNotFound(String message) implements BankTransactionOutcome {
        public BankNotFound {
            Objects.requireNonNull(message, "message");
        }
    }
}
