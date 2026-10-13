package com.uxplima.uxmskyblock.core.domain.stranger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The border of the StrangerRealms land holds the furthest island with room, within the operator's limits. */
class TheRealmBorderHoldsEveryIslandTest {

    @Test
    @DisplayName("The border reaches past the furthest island by the margin on both sides, and grows with it")
    void itHoldsTheFurthest() {
        RealmBorder border = new RealmBorder(100, 500, 100_000);

        assertThat(border.size(0)).isEqualTo(500);
        assertThat(border.size(-40)).isEqualTo(500);
        assertThat(border.size(5_120)).isEqualTo(10_440);
        assertThat(border.size(10_240)).isEqualTo(20_680);
        assertThat(border.size(Integer.MAX_VALUE)).isEqualTo(100_000);
    }

    @Test
    @DisplayName("Limits that hold nothing are refused")
    void badLimits() {
        assertThatThrownBy(() -> new RealmBorder(-1, 10, 20)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RealmBorder(1, 0, 20)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RealmBorder(1, 30, 20)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @org.junit.jupiter.api.DisplayName(
            "A width for each player widens the border on top of the islands, within the maximum")
    void byPlayers() {
        RealmBorder border = new RealmBorder(100, 500, 12_000, 200);

        org.assertj.core.api.Assertions.assertThat(border.size(5_120, 0)).isEqualTo(10_440);
        org.assertj.core.api.Assertions.assertThat(border.size(5_120, 3)).isEqualTo(11_040);
        org.assertj.core.api.Assertions.assertThat(border.size(5_120, 50)).isEqualTo(12_000);
        org.assertj.core.api.Assertions.assertThat(border.size(0, 1)).isEqualTo(500);
        org.assertj.core.api.Assertions.assertThat(border.size(5_120, -4)).isEqualTo(10_440);
        org.assertj.core.api.Assertions.assertThat(border.byPlayers()).isTrue();
        org.assertj.core.api.Assertions.assertThat(RealmBorder.SHIPPED.byPlayers())
                .isFalse();
        org.assertj.core.api.Assertions.assertThat(new RealmBorder(1, 2, 3)).isEqualTo(new RealmBorder(1, 2, 3, 0));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new RealmBorder(1, 2, 3, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
