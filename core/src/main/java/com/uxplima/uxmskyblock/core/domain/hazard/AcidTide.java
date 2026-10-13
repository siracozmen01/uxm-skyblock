package com.uxplima.uxmskyblock.core.domain.hazard;

import java.time.Duration;
import java.util.Objects;

/**
 * The acid tide: the sea rises a number of blocks over half a period and falls back over the other half, smoothly,
 * by the wall clock, so every server of a network finds the same tide at the same moment.
 *
 * @param rise how many blocks the sea stands higher at high tide, 0 for a sea that keeps still
 * @param period how long one tide takes, from low to high and back
 */
public record AcidTide(int rise, Duration period) {

    /** A sea that keeps still. */
    public static final AcidTide STILL = new AcidTide(0, Duration.ofMinutes(20));

    public AcidTide {
        Objects.requireNonNull(period, "period must not be null");
        if (rise < 0 || rise > 16) {
            throw new IllegalArgumentException("the tide rises 0 to 16 blocks: " + rise);
        }
        if (period.toSeconds() < 60) {
            throw new IllegalArgumentException("a tide takes at least a minute: " + period);
        }
    }

    /** Whether the sea moves at all. */
    public boolean moves() {
        return rise > 0;
    }

    /** How many blocks above its surface the sea stands at {@code epochMillis}: 0 at low tide, the rise at high. */
    public int level(long epochMillis) {
        double phase = Math.floorMod(epochMillis, period.toMillis()) / (double) period.toMillis();
        return (int) Math.round(rise * (1 - Math.cos(2 * Math.PI * phase)) / 2);
    }
}
