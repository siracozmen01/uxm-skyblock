package com.uxplima.uxmskyblock.bukkit.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A period written in a file is read in the unit it names, and one in a unit it does not know is not read. */
class APeriodIsReadInItsUnitTest {

    private static final Duration FALLBACK = Duration.ofMinutes(7);

    @Test
    @DisplayName("Milliseconds, seconds, minutes, hours and days, and a bare number in seconds")
    void eachUnit() {
        assertThat(AcidIslandConfiguration.durationOf("250ms", FALLBACK)).isEqualTo(Duration.ofMillis(250));
        assertThat(AcidIslandConfiguration.durationOf("1.5s", FALLBACK)).isEqualTo(Duration.ofMillis(1500));
        assertThat(AcidIslandConfiguration.durationOf("30m", FALLBACK)).isEqualTo(Duration.ofMinutes(30));
        assertThat(AcidIslandConfiguration.durationOf("24h", FALLBACK)).isEqualTo(Duration.ofHours(24));
        assertThat(AcidIslandConfiguration.durationOf("3d", FALLBACK)).isEqualTo(Duration.ofDays(3));
        assertThat(AcidIslandConfiguration.durationOf(" 0.5D ", FALLBACK)).isEqualTo(Duration.ofHours(12));
        assertThat(AcidIslandConfiguration.durationOf("90", FALLBACK)).isEqualTo(Duration.ofSeconds(90));
    }

    @Test
    @DisplayName("Nothing written, a word, or a unit not known gives the fallback, never a number of seconds")
    void whatIsNotRead() {
        assertThat(AcidIslandConfiguration.durationOf("", FALLBACK)).isEqualTo(FALLBACK);
        assertThat(AcidIslandConfiguration.durationOf("soon", FALLBACK)).isEqualTo(FALLBACK);
        assertThat(AcidIslandConfiguration.durationOf("3f", FALLBACK)).isEqualTo(FALLBACK);
        assertThat(AcidIslandConfiguration.durationOf("2w", FALLBACK)).isEqualTo(FALLBACK);
    }
}
