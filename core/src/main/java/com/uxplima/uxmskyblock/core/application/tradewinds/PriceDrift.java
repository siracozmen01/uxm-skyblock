package com.uxplima.uxmskyblock.core.application.tradewinds;

import java.time.Duration;
import java.util.Objects;

/**
 * How a port's prices move with its trade: each item the crews sell there pushes the price of that good down by
 * {@code perItem} of itself, and each they buy pushes it up, never past {@code floor} or {@code ceiling} of the
 * price the operator wrote. The push fades by half every {@code halfLife}, so a market left alone comes back to
 * its written prices.
 *
 * <p>Each price is the one the lot it trades leaves behind: a sale is paid at the price after its lot is sold, and a
 * purchase asked at the price after its lot is bought. So a lot bought and sold straight back, or sold and bought
 * straight back, never comes out ahead, however large the lot and however far one lot moves the price. The price
 * paid is rounded down and the price asked rounded up, so a port still asks more for a good than it pays for it.
 *
 * @param perItem how far one item moves the price, as a share of it
 * @param floor the lowest the price goes, as a share of the written price
 * @param ceiling the highest the price goes, as a share of the written price
 * @param halfLife how long the push of past trade takes to fade by half
 */
public record PriceDrift(double perItem, double floor, double ceiling, Duration halfLife) {

    /** How far a price may stray from a whole number by the arithmetic alone. */
    private static final double ROUNDING = 1e-9;

    /** Prices that never move. */
    public static final PriceDrift NONE = new PriceDrift(0, 1, 1, Duration.ofHours(1));

    public PriceDrift {
        Objects.requireNonNull(halfLife, "halfLife");
        if (!(perItem >= 0 && perItem <= 1)) {
            throw new IllegalArgumentException("one item moves a price by a share from 0 to 1: " + perItem);
        }
        if (!(floor > 0 && floor <= 1)) {
            throw new IllegalArgumentException("the floor is a share above 0, at most 1: " + floor);
        }
        if (!(ceiling >= 1 && ceiling <= 10)) {
            throw new IllegalArgumentException("the ceiling is a share from 1 to 10: " + ceiling);
        }
        if (halfLife.isNegative() || halfLife.isZero()) {
            throw new IllegalArgumentException("the push fades over a time above nothing: " + halfLife);
        }
    }

    /** Whether prices move at all. */
    public boolean moves() {
        return perItem > 0 && (floor < 1 || ceiling > 1);
    }

    /** The push {@code pressure} left after {@code elapsed}: halved every half life. */
    public double faded(double pressure, Duration elapsed) {
        if (elapsed.isNegative() || elapsed.isZero()) {
            return pressure;
        }
        return pressure * Math.pow(0.5, (double) elapsed.toMillis() / halfLife.toMillis());
    }

    /** What a price is multiplied by under {@code pressure}, items sold there less items bought there. */
    public double factor(double pressure) {
        return Math.max(floor, Math.min(ceiling, 1 - perItem * pressure));
    }

    /**
     * {@code good} as the port prices its next lot under {@code pressure}: what it pays once the lot is sold, and
     * what it asks once the lot is bought.
     */
    public Port.Good priced(Port.Good good, double pressure) {
        double sold = factor(pressure + good.lot());
        double bought = factor(pressure - good.lot());
        if (sold == 1 && bought == 1) {
            return good;
        }
        // A product such as 200 times 1.1 comes out a hair above 220; it is read as the whole number it is.
        long pays = good.pays() == 0 ? 0 : Math.max(1, (long) Math.floor(good.pays() * sold + ROUNDING));
        long asks = good.asks() == 0 ? 0 : Math.max(pays + 1, (long) Math.ceil(good.asks() * bought - ROUNDING));
        return new Port.Good(good.item(), good.lot(), pays, good.asks() == 0 ? 0 : asks);
    }
}
