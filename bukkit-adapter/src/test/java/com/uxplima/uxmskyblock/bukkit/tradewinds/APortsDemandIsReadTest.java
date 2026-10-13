package com.uxplima.uxmskyblock.bukkit.tradewinds;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZoneId;

import com.uxplima.uxmskyblock.bukkit.config.TradeWindsConfiguration;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.tradewinds.DailyDemand;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

/** How much more each port pays for the goods it wants today is read from {@code daily-demand}. */
class APortsDemandIsReadTest extends MockBukkitHarness {

    private static DailyDemand read(String written) throws Exception {
        return TradeWindsConfiguration.load(HoconConfigurationLoader.builder().buildAndLoadString(written))
                .demand();
    }

    @Test
    @DisplayName("The goods, the bonus and the zone are read, and a demand that cannot be is the shipped one")
    void read() throws Exception {
        assertThat(read("daily-demand { goods = 2, bonus = 0.5, zone = \"Europe/Istanbul\" }"))
                .isEqualTo(new DailyDemand(2, 0.5, ZoneId.of("Europe/Istanbul")));
        assertThat(read("daily-demand { goods = 0 }"))
                .describedAs("an operator turns it off")
                .isEqualTo(new DailyDemand(0, 0.25, ZoneId.of("UTC")));
        assertThat(read("daily-demand { zone = \"Atlantis/Deep\" }")).isEqualTo(TradeWindsConfiguration.SHIPPED_DEMAND);
        assertThat(read("daily-demand { goods = 70 }")).isEqualTo(TradeWindsConfiguration.SHIPPED_DEMAND);
        assertThat(read("daily-demand { bonus = -1 }")).isEqualTo(TradeWindsConfiguration.SHIPPED_DEMAND);
        assertThat(read("enabled = true")).isEqualTo(TradeWindsConfiguration.SHIPPED_DEMAND);
    }
}
