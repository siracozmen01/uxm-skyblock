package com.uxplima.uxmskyblock.core.application.tradewinds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A port's prices move with its trade: selling a good there lowers what it pays and asks for it, buying raises
 * both, within the operator's bounds, and the push fades by half every half life back towards the written prices.
 */
class APortsPricesMoveWithItsTradeTest {

    private static final Instant NOW = Instant.parse("2026-10-13T12:00:00Z");
    private static final Port.Good WHEAT = new Port.Good("WHEAT", 10, 100, 200);
    private static final Port BAY = new Port("bay", 0, List.of(WHEAT));
    private static final PriceDrift DRIFT = new PriceDrift(0.01, 0.5, 1.5, Duration.ofHours(1));
    private static final Port.Good AT_REST = new Port.Good("WHEAT", 10, 90, 220);

    /** Storage in a map, which refuses a write over any version but the last, as the table does. */
    private static final class Kept implements MarketPricesPort {
        final Map<String, Pressure> rows = new HashMap<>();
        int refuseNext;

        @Override
        public Optional<Pressure> find(String portId, String item) {
            return Optional.ofNullable(rows.get(portId + "/" + item));
        }

        @Override
        public List<Pressure> findAll() {
            return List.copyOf(rows.values());
        }

        @Override
        public boolean keep(Pressure next) {
            if (refuseNext > 0) {
                refuseNext--;
                return false;
            }
            Pressure held = rows.get(next.portId() + "/" + next.item());
            long at = held == null ? 0 : held.version();
            if (next.version() != at + 1) {
                return false;
            }
            rows.put(next.portId() + "/" + next.item(), next);
            return true;
        }
    }

    private static final class Ticking extends Clock {
        Instant now = NOW;

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    @DisplayName("Each item sold moves both prices down by its share, each bought up, within the floor and ceiling")
    void factor() {
        assertThat(DRIFT.priced(WHEAT, 0))
                .describedAs(
                        "a lot is paid at the price it leaves when sold, and asked at the one it leaves when bought")
                .isEqualTo(AT_REST);
        assertThat(DRIFT.priced(WHEAT, 20)).isEqualTo(new Port.Good("WHEAT", 10, 70, 180));
        assertThat(DRIFT.priced(WHEAT, -20)).isEqualTo(new Port.Good("WHEAT", 10, 110, 260));
        assertThat(DRIFT.priced(WHEAT, -10))
                .describedAs("bought back to rest")
                .isEqualTo(new Port.Good("WHEAT", 10, 100, 240));
        assertThat(DRIFT.priced(new Port.Good("WHEAT", 10, 20, 50), 25).pays())
                .describedAs("20 times 0.65 is 13, though the arithmetic makes it a hair less")
                .isEqualTo(13);
        assertThat(DRIFT.priced(WHEAT, 1_000)).describedAs("the floor").isEqualTo(new Port.Good("WHEAT", 10, 50, 100));
        assertThat(DRIFT.priced(WHEAT, -1_000))
                .describedAs("the ceiling")
                .isEqualTo(new Port.Good("WHEAT", 10, 150, 300));
        assertThat(DRIFT.priced(new Port.Good("WHEAT", 1, 3, 4), 5))
                .describedAs("paid rounds down and asked rounds up, so it still asks more than it pays")
                .isEqualTo(new Port.Good("WHEAT", 1, 2, 4));
        assertThat(DRIFT.priced(new Port.Good("SILK", 1, 0, 3), 900)).isEqualTo(new Port.Good("SILK", 1, 0, 2));
        assertThat(DRIFT.faded(16, Duration.ofHours(2))).isEqualTo(4);
        assertThat(DRIFT.faded(16, Duration.ZERO)).isEqualTo(16);
        assertThat(PriceDrift.NONE.moves()).isFalse();
        assertThat(new PriceDrift(0.01, 1, 1, Duration.ofHours(1)).moves())
                .describedAs("bounds that let nothing move")
                .isFalse();
        assertThatThrownBy(() -> new PriceDrift(0.01, 0, 1.5, Duration.ofHours(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PriceDrift(0.01, 0.5, 0.9, Duration.ofHours(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PriceDrift(0.01, 0.5, 1.5, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("A lot bought and sold straight back, or sold and bought straight back, never comes out ahead")
    void noRoundTripPays() {
        PriceDrift steep = new PriceDrift(0.01, 0.1, 10, Duration.ofHours(1));
        for (int lot : new int[] {1, 10, 50, 100, 500}) {
            Port.Good good = new Port.Good("WHEAT", lot, 100, 101);
            for (double pressure = -1_000; pressure <= 1_000; pressure += 37) {
                long bought = steep.priced(good, pressure).asks();
                long soldBack = steep.priced(good, pressure - lot).pays();
                assertThat(soldBack)
                        .describedAs("bought at %s under %s, sold straight back", lot, pressure)
                        .isLessThan(bought);
                long sold = steep.priced(good, pressure).pays();
                long boughtBack = steep.priced(good, pressure + lot).asks();
                assertThat(boughtBack)
                        .describedAs("sold at %s under %s, bought straight back", lot, pressure)
                        .isGreaterThan(sold);
            }
        }
    }

    @Test
    @DisplayName("A trade is written at once, fades with time, and is read again by another node once stale")
    void trade() {
        Kept kept = new Kept();
        Ticking clock = new Ticking();
        MarketPrices prices = new MarketPrices(kept, DRIFT, clock, Duration.ofSeconds(5));
        MarketPrices other = new MarketPrices(kept, DRIFT, clock, Duration.ofSeconds(5));
        assertThat(other.priced(BAY, WHEAT)).isEqualTo(AT_REST);

        assertThat(prices.traded(BAY, WHEAT, 20)).isTrue();
        assertThat(prices.priced(BAY, WHEAT)).isEqualTo(new Port.Good("WHEAT", 10, 70, 180));
        assertThat(other.priced(BAY, WHEAT))
                .describedAs("another node keeps what it read until it is stale")
                .isEqualTo(AT_REST);

        clock.now = NOW.plus(Duration.ofHours(1));
        assertThat(other.priced(BAY, WHEAT)).describedAs("half faded").isEqualTo(new Port.Good("WHEAT", 10, 80, 200));
        assertThat(prices.traded(BAY, WHEAT, -10)).isTrue();
        assertThat(kept.find("bay", "WHEAT").orElseThrow().pressure()).isEqualTo(0);
        assertThat(kept.find("bay", "WHEAT").orElseThrow().version()).isEqualTo(2);

        kept.refuseNext = 2;
        assertThat(prices.traded(BAY, WHEAT, 5))
                .describedAs("written at the third try")
                .isTrue();
        kept.refuseNext = 3;
        assertThat(prices.traded(BAY, WHEAT, 5))
                .describedAs("given up after three")
                .isFalse();
        assertThat(kept.find("bay", "WHEAT").orElseThrow().pressure()).isEqualTo(5);
        assertThat(MarketPrices.none().priced(BAY, WHEAT)).isSameAs(WHEAT);
        assertThat(MarketPrices.none().traded(BAY, WHEAT, 4)).isTrue();
    }
}
