package com.uxplima.uxmskyblock.bukkit.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

class BankPayoutsConfigurationTest {

    private static BankPayoutsConfiguration read(String hocon) throws Exception {
        return BankPayoutsConfiguration.load(HoconConfigurationLoader.builder().buildAndLoadString(hocon));
    }

    @Test
    @DisplayName("The shipped bank.conf reads as the fallback: interest off, no refund")
    void theShippedFileIsTheFallback() throws Exception {
        String shipped;
        try (InputStream in = Objects.requireNonNull(getClass().getResourceAsStream("/modules/bank.conf"));
                BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            shipped = reader.lines().collect(Collectors.joining("\n"));
        }
        assertThat(read(shipped)).isEqualTo(BankPayoutsConfiguration.defaultConfiguration());
    }

    @Test
    @DisplayName("Every key is read, money as written, and a share past one or a bad interval falls back")
    void everyKeyIsRead() throws Exception {
        BankPayoutsConfiguration read = read("""
                bank {
                    interest { enabled = true, interval = "12h", percent = 0.5, active-within = "7d", max-per-payment = 250.5 }
                    delete-refund { share = 0.25 }
                }
                """);
        assertThat(read.interest().enabled()).isTrue();
        assertThat(read.interest().interval()).isEqualTo(Duration.ofHours(12));
        assertThat(read.interest().percent()).isEqualTo(0.5);
        assertThat(read.interest().activeWithin()).isEqualTo(Duration.ofDays(7));
        assertThat(read.interest().maxPerPaymentMinorUnits()).isEqualTo(25_050L);
        assertThat(read.deleteRefundShare()).isEqualTo(0.25);

        BankPayoutsConfiguration bad = read("bank { interest { interval = \"soon\" }, delete-refund { share = 3 } }");
        assertThat(bad.interest().interval()).isEqualTo(Duration.ofHours(24));
        assertThat(bad.deleteRefundShare()).isEqualTo(1.0);
    }
}
