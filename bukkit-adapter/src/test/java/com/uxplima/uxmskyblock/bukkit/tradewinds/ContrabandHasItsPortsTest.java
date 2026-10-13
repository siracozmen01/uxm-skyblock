package com.uxplima.uxmskyblock.bukkit.tradewinds;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;

import com.uxplima.uxmskyblock.bukkit.config.TradeWindsConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.TradeWindsContraband;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.tradewinds.Port;
import com.uxplima.uxmskyblock.core.application.tradewinds.SeaHazards;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

/**
 * {@code modules/tradewinds.conf} marks goods as contraband, names the ports that take them, and gives every other
 * port its customs; a lawful port never buys contraband, whatever its market lists.
 */
class ContrabandHasItsPortsTest extends MockBukkitHarness {

    private static TradeWindsConfiguration load(String written) throws Exception {
        return TradeWindsConfiguration.load(HoconConfigurationLoader.builder().buildAndLoadString(written));
    }

    @Test
    @DisplayName("Contraband is read with the ports that take it, each lawful port's customs and the standing lost")
    void read() throws Exception {
        TradeWindsConfiguration config = load("""
                contraband { items = [ "gunpowder", "TNT", "NOT_AN_ITEM", "AIR" ], standing-lost = 900 }
                ports = [
                    { id = "den", takes-contraband = true,
                      market = [ { item = "GUNPOWDER", lot = 8, pays = 50, asks = 80 } ] }
                    { id = "bay", customs = 0.4,
                      market = [ { item = "GUNPOWDER", lot = 8, pays = 50, asks = 80 },
                                 { item = "TNT", lot = 1, pays = 500 },
                                 { item = "WHEAT", lot = 16, pays = 10, asks = 20 } ] }
                    { id = "cove", customs = 1.5, market = [ { item = "WHEAT", lot = 16, pays = 10, asks = 20 } ] }
                    { id = "ghost", voyage-seconds = -5, takes-contraband = true, customs = 0.5, market = [] }
                ]
                """);

        assertThat(config.contraband())
                .isEqualTo(
                        new TradeWindsContraband(Set.of("GUNPOWDER", "TNT"), Set.of("den"), Map.of("bay", 0.4), 900));
        assertThat(config.port("den").orElseThrow().goods())
                .describedAs("a port that takes contraband buys it as written")
                .containsExactly(new Port.Good("GUNPOWDER", 8, 50, 80));
        assertThat(config.port("bay").orElseThrow().goods())
                .describedAs("a lawful port keeps selling it, and buys none")
                .containsExactly(new Port.Good("GUNPOWDER", 8, 0, 80), new Port.Good("WHEAT", 16, 10, 20));
        assertThat(config.contraband().atSea())
                .isEqualTo(new SeaHazards.Customs(Set.of("GUNPOWDER", "TNT"), Map.of("bay", 0.4), 900));
        assertThat(load("""
                        contraband { standing-lost = -5 }
                        ports = [ { id = "bay", market = [ { item = "WHEAT", lot = 1, pays = 1, asks = 2 } ] } ]
                        """).contraband()).isEqualTo(TradeWindsContraband.NONE);
        assertThat(load("enabled = true").contraband())
                .describedAs("a file that writes no ports has the shipped contraband")
                .isEqualTo(TradeWindsConfiguration.SHIPPED_CONTRABAND);
    }

    @Test
    @DisplayName("The shipped ports sell gunpowder at Saltmarsh, buy it at Ironhaven, and keep customs at Emerald Bay")
    void shipped() {
        TradeWindsConfiguration shipped = TradeWindsConfiguration.defaultConfiguration();

        assertThat(shipped.contraband().takers()).containsExactlyInAnyOrder("saltmarsh", "ironhaven");
        assertThat(shipped.port("saltmarsh").orElseThrow().good("GUNPOWDER"))
                .get()
                .satisfies(good -> {
                    assertThat(good.sold()).isTrue();
                    assertThat(good.bought()).isFalse();
                });
        assertThat(shipped.port("ironhaven").orElseThrow().good("GUNPOWDER"))
                .get()
                .extracting(Port.Good::bought)
                .isEqualTo(true);
        assertThat(shipped.port("emerald-bay").orElseThrow().good("GUNPOWDER")).isEmpty();
        assertThat(shipped.contraband().customs()).containsOnlyKeys("emerald-bay");
    }
}
