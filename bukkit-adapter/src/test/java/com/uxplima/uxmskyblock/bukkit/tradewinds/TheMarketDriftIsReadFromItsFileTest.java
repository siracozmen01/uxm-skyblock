package com.uxplima.uxmskyblock.bukkit.tradewinds;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

import com.uxplima.uxmskyblock.bukkit.config.TradeWindsConfiguration;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.tradewinds.PriceDrift;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

/** How a port's trade moves its prices is the operator's: read from market-drift, the shipped one when it is not. */
class TheMarketDriftIsReadFromItsFileTest extends MockBukkitHarness {

    private static PriceDrift drift(String written) throws Exception {
        return TradeWindsConfiguration.load(HoconConfigurationLoader.builder().buildAndLoadString(written))
                .drift();
    }

    @Test
    @DisplayName("The file's per-item, floor, ceiling and half-life are read, and a drift switched off moves nothing")
    void read() throws Exception {
        assertThat(drift("market-drift { per-item = 0.02, floor = 0.8, ceiling = 2, half-life = \"30m\" }"))
                .isEqualTo(new PriceDrift(0.02, 0.8, 2, Duration.ofMinutes(30)));
        assertThat(drift("market-drift { enabled = false, per-item = 0.02 }")).isEqualTo(PriceDrift.NONE);
        assertThat(drift("")).isEqualTo(TradeWindsConfiguration.SHIPPED_DRIFT);
    }

    @Test
    @DisplayName("A drift that cannot be is the shipped one, and a half-life that is no time is the shipped one's")
    void wrong() throws Exception {
        assertThat(drift("market-drift { floor = 0 }")).isEqualTo(TradeWindsConfiguration.SHIPPED_DRIFT);
        assertThat(drift("market-drift { per-item = 0.02, half-life = \"soon\" }"))
                .isEqualTo(new PriceDrift(0.02, 0.5, 1.5, TradeWindsConfiguration.SHIPPED_DRIFT.halfLife()));
    }

    @Test
    @DisplayName("The shipped file writes the shipped drift")
    void shipped() throws Exception {
        try (InputStream in =
                Objects.requireNonNull(getClass().getClassLoader().getResourceAsStream("modules/tradewinds.conf"))) {
            assertThat(drift(new String(in.readAllBytes(), StandardCharsets.UTF_8)))
                    .isEqualTo(TradeWindsConfiguration.SHIPPED_DRIFT);
        }
    }
}
