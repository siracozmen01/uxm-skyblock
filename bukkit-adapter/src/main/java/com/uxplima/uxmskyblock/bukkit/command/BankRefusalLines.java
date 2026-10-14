package com.uxplima.uxmskyblock.bukkit.command;

import com.uxplima.uxmskyblock.core.domain.bank.BankTransactionOutcome;

/**
 * The catalogue line for island bank money that did not move.
 *
 * <p>Every place that moves bank money used to put the refusal in front of the player as it stood:
 * an English sentence from the code, often with an island's id in it, or a Java record. The kind of
 * refusal picks the line now, so a bank command, a shop command and a shop window say the same thing
 * in the player's language, and the reason stays in the log.
 */
public final class BankRefusalLines {

    private BankRefusalLines() {}

    /**
     * The line for a move that did not happen.
     *
     * @param walletKey the line for a wallet that would not pay or take, which reads differently
     *     for a deposit and a withdrawal
     */
    public static String keyFor(BankTransactionOutcome outcome, String walletKey) {
        return switch (outcome) {
            case BankTransactionOutcome.AuthorityRejected rejected ->
                switch (rejected.kind()) {
                    case NO_ISLAND -> "error.no_island";
                    case NO_AUTHORITY -> "bank.refused_elsewhere";
                    case INVALID_AMOUNT -> "bank.refused_amount";
                    case WALLET_REFUSED -> walletKey;
                    case REFUND_FAILED -> "bank.refund_failed";
                    case NO_ECONOMY -> "bank.no_economy";
                    case BANK_FULL -> "bank.full";
                    case OUTCOME_UNKNOWN -> "bank.outcome_unknown";
                    case OTHER -> "bank.refused";
                };
            case BankTransactionOutcome.StaleVersion _ -> "bank.busy";
            case BankTransactionOutcome.DuplicateOperation _ -> "bank.already_done";
            case BankTransactionOutcome.BankNotFound _ -> "error.no_island";
            case BankTransactionOutcome.InsufficientFunds _ -> "bank.refused";
            case BankTransactionOutcome.Success _ -> "bank.refused";
        };
    }
}
