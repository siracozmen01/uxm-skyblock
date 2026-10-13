package com.uxplima.uxmskyblock.core.application.tradewinds;

import java.time.Duration;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A delivery a port offers: so many of a good, carried to another port within a time. What it rewards is the
 * operator's, written beside it.
 *
 * @param id the key the operator gave it, unique in the file, and the key of its name in the language files
 * @param from the port that offers it
 * @param item the good, by the name the server knows it under
 * @param count how many of it the hold carries to {@code to}
 * @param to the port it is delivered to
 * @param within how long the crew has from taking it
 */
public record Contract(String id, String from, String item, int count, String to, Duration within) {

    private static final Pattern ID = Pattern.compile("[a-z0-9_-]{1,64}");

    /** The most a contract asks for: a full hold of the largest rank, six rows of 64. */
    public static final int MAX_COUNT = 64 * 54;

    /** The longest a contract gives, a week. */
    public static final Duration MAX_WITHIN = Duration.ofDays(7);

    public Contract {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(within, "within");
        if (!ID.matcher(id).matches()) {
            throw new IllegalArgumentException("a contract's key is from 1 to 64 of a-z, 0-9, _ and -: " + id);
        }
        if (count < 1 || count > MAX_COUNT) {
            throw new IllegalArgumentException("a contract asks for from 1 to " + MAX_COUNT + " items");
        }
        if (from.equals(to)) {
            throw new IllegalArgumentException("a contract is delivered to another port than the one offering it");
        }
        if (within.compareTo(Duration.ofMinutes(1)) < 0 || within.compareTo(MAX_WITHIN) > 0) {
            throw new IllegalArgumentException("a contract gives from a minute to " + MAX_WITHIN.toDays() + " days");
        }
    }
}
