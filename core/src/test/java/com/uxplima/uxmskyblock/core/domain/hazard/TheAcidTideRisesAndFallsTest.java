package com.uxplima.uxmskyblock.core.domain.hazard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TheAcidTideRisesAndFallsTest {

    private static final long MINUTE = 60_000L;

    @Test
    @DisplayName("The sea is low at the start of a period, high at its middle, and moves a block at a time between")
    void theTideTurns() {
        AcidTide tide = new AcidTide(3, Duration.ofMinutes(20));

        assertThat(tide.level(0)).isZero();
        assertThat(tide.level(10 * MINUTE)).isEqualTo(3);
        assertThat(tide.level(20 * MINUTE)).describedAs("the next period").isZero();
        assertThat(tide.level(30 * MINUTE)).isEqualTo(3);
        assertThat(tide.level(-10 * MINUTE)).describedAs("before the epoch too").isEqualTo(3);
        List<Integer> levels = new ArrayList<>();
        for (long at = 0; at <= 20 * MINUTE; at += 10_000) {
            int level = tide.level(at);
            if (!levels.isEmpty()) {
                assertThat(Math.abs(level - levels.getLast())).isLessThanOrEqualTo(1);
            }
            levels.add(level);
        }
        assertThat(levels).containsSubsequence(0, 1, 2, 3, 2, 1, 0);
        assertThat(levels.stream().filter(level -> level == 3).count())
                .describedAs("high water lasts a while")
                .isGreaterThan(1);
    }

    @Test
    @DisplayName("A tide of no rise keeps still, and one too high or too quick is no tide")
    void bounds() {
        assertThat(AcidTide.STILL.moves()).isFalse();
        assertThat(AcidTide.STILL.level(10 * MINUTE)).isZero();
        assertThat(new AcidTide(1, Duration.ofMinutes(1)).moves()).isTrue();
        assertThat(new AcidTide(16, Duration.ofMinutes(2)).level(MINUTE)).isEqualTo(16);
        assertThatThrownBy(() -> new AcidTide(17, Duration.ofMinutes(20))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AcidTide(-1, Duration.ofMinutes(20))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AcidTide(1, Duration.ofSeconds(59))).isInstanceOf(IllegalArgumentException.class);
    }
}
