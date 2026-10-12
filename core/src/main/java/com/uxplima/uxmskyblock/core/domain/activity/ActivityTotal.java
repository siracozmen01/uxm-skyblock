package com.uxplima.uxmskyblock.core.domain.activity;

import java.util.Objects;
import java.util.UUID;

/**
 * What one player moved through the bank, added up over the lines a query reads.
 *
 * @param player who moved it
 * @param type whether it went in or came out
 * @param currency the currency's id, {@link ActivityAmount#PRIMARY} for the server's money
 * @param minorUnits the sum, always positive: the type says which way it went
 * @param lines how many lines the sum is made of
 */
public record ActivityTotal(UUID player, ActivityEventType type, String currency, long minorUnits, int lines) {

    public ActivityTotal {
        Objects.requireNonNull(player, "player must not be null");
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        if (minorUnits < 0) {
            throw new IllegalArgumentException("a total is written without its sign");
        }
        if (lines < 1) {
            throw new IllegalArgumentException("a total is made of at least one line");
        }
    }
}
