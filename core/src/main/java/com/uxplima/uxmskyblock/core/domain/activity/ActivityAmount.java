package com.uxplima.uxmskyblock.core.domain.activity;

import java.util.Objects;

/**
 * What one bank line moved: how much of which currency, in or out.
 *
 * <p>The sentence of a line is written for a reader and cannot be added up. This is the number beside
 * it, so an island can ask who put in and who took out how much.
 *
 * @param currency the currency's id as the bank file writes it, {@link #PRIMARY} for the server's money
 * @param minorUnits the amount moved, positive in and negative out; the server's money in hundredths,
 *     an operator currency in whole units
 */
public record ActivityAmount(String currency, long minorUnits) {

    /** The id the server's own money is kept under. */
    public static final String PRIMARY = "primary";

    public ActivityAmount {
        Objects.requireNonNull(currency, "currency must not be null");
        if (currency.isBlank()) {
            throw new IllegalArgumentException("currency must not be blank");
        }
        if (minorUnits == 0) {
            throw new IllegalArgumentException("an amount that moved nothing is not an amount");
        }
    }
}
