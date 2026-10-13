package com.uxplima.uxmskyblock.persistence.tradewinds;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.time.Instant;

import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.application.tradewinds.MarketPricesPort.Pressure;

/** What every engine must do with the push trade left on each TradeWinds price. */
final class MarketPricesStorageScenario {

    private MarketPricesStorageScenario() {}

    static void run(Database database) throws SQLException {
        SqlMarketPricesAdapter prices = new SqlMarketPricesAdapter(database.dataSource());
        Instant at = Instant.ofEpochMilli(1_760_000_000_123L);

        assertThat(prices.find("saltmarsh", "WHEAT")).isEmpty();
        assertThat(prices.keep(new Pressure("saltmarsh", "WHEAT", 16.25, at, 1)))
                .isTrue();
        assertThat(prices.keep(new Pressure("saltmarsh", "WHEAT", 3, at, 1)))
                .describedAs("a second first row is refused")
                .isFalse();
        assertThat(prices.find("saltmarsh", "WHEAT")).contains(new Pressure("saltmarsh", "WHEAT", 16.25, at, 1));

        assertThat(prices.keep(new Pressure("saltmarsh", "WHEAT", -8.5, at.plusSeconds(60), 2)))
                .isTrue();
        assertThat(prices.keep(new Pressure("saltmarsh", "WHEAT", 99, at.plusSeconds(61), 2)))
                .describedAs("written over the version before only once")
                .isFalse();
        assertThat(prices.keep(new Pressure("saltmarsh", "WHEAT", 99, at.plusSeconds(61), 9)))
                .describedAs("never over a version that is not there")
                .isFalse();
        assertThat(prices.find("saltmarsh", "WHEAT"))
                .contains(new Pressure("saltmarsh", "WHEAT", -8.5, at.plusSeconds(60), 2));

        assertThat(prices.keep(new Pressure("ironhaven", "WHEAT", 4, at, 1))).isTrue();
        assertThat(prices.findAll())
                .containsExactlyInAnyOrder(
                        new Pressure("saltmarsh", "WHEAT", -8.5, at.plusSeconds(60), 2),
                        new Pressure("ironhaven", "WHEAT", 4, at, 1));
    }
}
