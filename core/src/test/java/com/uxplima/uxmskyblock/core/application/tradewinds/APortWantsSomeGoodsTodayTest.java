package com.uxplima.uxmskyblock.core.application.tradewinds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Each port wants some of the goods it buys more on a given day, the same on every node and after every restart,
 * and pays more for them while it does.
 */
class APortWantsSomeGoodsTodayTest {

    private static final Instant NOON = Instant.parse("2026-10-13T12:00:00Z");
    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final Port BAY = new Port(
            "bay",
            60,
            List.of(
                    new Port.Good("WHEAT", 16, 100, 200),
                    new Port.Good("PUMPKIN", 8, 300, 400),
                    new Port.Good("GLASS", 16, 50, 60),
                    new Port.Good("SILK", 4, 0, 900)));

    @Test
    @DisplayName("A port wants so many of the goods it buys, the same all day, and others on other days")
    void wanted() {
        DailyDemand one = new DailyDemand(1, 0.25, UTC);

        Set<String> today = one.wanted(BAY, NOON);
        assertThat(today).hasSize(1).isSubsetOf("WHEAT", "PUMPKIN", "GLASS");
        assertThat(one.wanted(BAY, NOON.minus(Duration.ofHours(11)))).isEqualTo(today);
        assertThat(one.wanted(BAY, NOON.plus(Duration.ofHours(11)))).isEqualTo(today);
        Set<String> seen = new HashSet<>();
        for (int day = 0; day < 30; day++) {
            seen.addAll(one.wanted(BAY, NOON.plus(Duration.ofDays(day))));
        }
        assertThat(seen)
                .describedAs("each good is wanted some day")
                .containsExactlyInAnyOrder("WHEAT", "PUMPKIN", "GLASS");
        assertThat(new DailyDemand(5, 0.25, UTC).wanted(BAY, NOON))
                .describedAs("never a good it does not buy")
                .containsExactlyInAnyOrder("WHEAT", "PUMPKIN", "GLASS");
        assertThat(today)
                .describedAs("the draw of a port and a day stays the same from one release to the next")
                .containsExactly("GLASS");
        assertThat(new DailyDemand(2, 0.25, UTC).wanted(BAY, NOON.plus(Duration.ofDays(1))))
                .containsExactlyInAnyOrder("PUMPKIN", "WHEAT");
        assertThat(one.wanted(new Port("bay", 60, BAY.goods().reversed()), NOON))
                .describedAs("whatever order the file writes the goods in")
                .isEqualTo(today);
        assertThat(new DailyDemand(0, 0.25, UTC).wanted(BAY, NOON)).isEmpty();
        assertThat(new DailyDemand(2, 0, UTC).wanted(BAY, NOON)).isEmpty();
        assertThat(new DailyDemand(2, 0.25, UTC).wanted(new Port("cove", 60, BAY.goods()), NOON))
                .describedAs("another port draws its own")
                .hasSize(2);
    }

    @Test
    @DisplayName("A day begins at midnight in the zone the operator names")
    void zone() {
        DailyDemand utc = new DailyDemand(1, 0.25, UTC);
        DailyDemand east = new DailyDemand(1, 0.25, ZoneId.of("Etc/GMT-2"));
        Instant lateEvening = Instant.parse("2026-10-13T23:00:00Z");

        assertThat(east.wanted(BAY, lateEvening))
                .describedAs("already the next day two hours east")
                .isEqualTo(utc.wanted(BAY, NOON.plus(Duration.ofDays(1))));
        boolean anyDiffers = false;
        for (int day = 0; day < 30 && !anyDiffers; day++) {
            Instant evening = lateEvening.plus(Duration.ofDays(day));
            anyDiffers = !east.wanted(BAY, evening).equals(utc.wanted(BAY, evening));
        }
        assertThat(anyDiffers).isTrue();
    }

    @Test
    @DisplayName("A good wanted today is paid the bonus more, never as much as the port asks")
    void priced() {
        DailyDemand all = new DailyDemand(3, 0.25, UTC);
        Port.Good wheat = BAY.goods().get(0);
        Port.Good glass = BAY.goods().get(2);
        Port.Good silk = BAY.goods().get(3);

        assertThat(all.priced(BAY, wheat, NOON)).isEqualTo(new Port.Good("WHEAT", 16, 125, 200));
        assertThat(all.priced(BAY, glass, NOON))
                .describedAs("62 would be more than it asks")
                .isEqualTo(new Port.Good("GLASS", 16, 59, 60));
        assertThat(all.priced(BAY, silk, NOON)).isSameAs(silk);
        assertThat(new DailyDemand(3, 0.25, UTC).priced(BAY, new Port.Good("TAR", 1, 10, 20), NOON))
                .describedAs("a good the port does not list")
                .isEqualTo(new Port.Good("TAR", 1, 10, 20));
        assertThat(new DailyDemand(3, 0.1, UTC)
                        .priced(BAY, new Port.Good("WHEAT", 16, 70, 0), NOON)
                        .pays())
                .describedAs("70 and a tenth is 77, and nothing caps a port that does not sell it")
                .isEqualTo(77);
        assertThat(DailyDemand.NONE.priced(BAY, wheat, NOON)).isSameAs(wheat);
        assertThat(new DailyDemand(3, 0.15, UTC)
                        .priced(BAY, new Port.Good("WHEAT", 16, 100, 0), NOON)
                        .pays())
                .describedAs("100 and fifteen percent is 115, though the arithmetic makes it a hair less")
                .isEqualTo(115);
    }

    @Test
    @DisplayName("A port wants from 0 to 64 goods a day, for a bonus from 0 to 10 times the price")
    void bounds() {
        assertThatThrownBy(() -> new DailyDemand(-1, 0.1, UTC)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DailyDemand(65, 0.1, UTC)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DailyDemand(1, -0.1, UTC)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DailyDemand(1, 10.5, UTC)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new DailyDemand(64, 10, UTC).goods()).isEqualTo(64);
    }
}
