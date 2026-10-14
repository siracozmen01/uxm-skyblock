package com.uxplima.uxmskyblock.core.domain.cave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** An ore may begin some way down, and the ores of a rock may be counted richer than written. */
class OresLieByDepthTest {

    private static final OreTable TABLE =
            new OreTable(List.of(new OreTable.Ore("COAL_ORE", 0.1), new OreTable.Ore("DIAMOND_ORE", 0.1, 10)));

    @Test
    @DisplayName("An ore is written with the depth it begins at, or none for every depth")
    void written() {
        assertThat(OreTable.Ore.parse(" diamond_ore : 0.002 : 16 "))
                .isEqualTo(new OreTable.Ore("DIAMOND_ORE", 0.002, 16));
        assertThat(OreTable.Ore.parse("COAL_ORE:0.01")).isEqualTo(new OreTable.Ore("COAL_ORE", 0.01, 0));
        for (String bad : List.of("COAL_ORE:0.1:-1", "COAL_ORE:0.1:deep", "COAL_ORE:0.1:2:3", "COAL_ORE")) {
            assertThatThrownBy(() -> OreTable.Ore.parse(bad))
                    .describedAs(bad)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("Above the depth an ore begins at it is not found, and from there on it is")
    void byDepth() {
        assertThat(TABLE.pick(0.05, 5, 1)).contains("COAL_ORE");
        assertThat(TABLE.pick(0.05, -4, 1)).describedAs("above the arrival too").contains("COAL_ORE");
        assertThat(TABLE.pick(0.15, -4, 1)).isEmpty();
        assertThat(TABLE.pick(0.15, 9, 1)).describedAs("the diamond not begun").isEmpty();
        assertThat(TABLE.pick(0.15, 10, 1)).contains("DIAMOND_ORE");
        assertThat(TABLE.pick(0.25, 10, 1)).isEmpty();
        assertThat(TABLE.pick(0.15))
                .describedAs("a roll with no depth counts every ore")
                .contains("DIAMOND_ORE");
    }

    @Test
    @DisplayName("Scaled, the ores take more of the rock, and never more than all of it")
    void scaled() {
        assertThat(TABLE.pick(0.35, 10, 2)).contains("DIAMOND_ORE");
        assertThat(TABLE.pick(0.19, 10, 2)).contains("COAL_ORE");
        assertThat(TABLE.pick(0.45, 10, 2)).isEmpty();
        assertThat(TABLE.pick(0.15, 5, 2)).describedAs("scaled, the coal alone").contains("COAL_ORE");
        assertThat(TABLE.pick(0.25, 5, 2)).isEmpty();
        // Ten times would be two in all: scaled back to one, half coal and half diamond.
        assertThat(TABLE.pick(0.49, 10, 10)).contains("COAL_ORE");
        assertThat(TABLE.pick(0.51, 10, 10)).contains("DIAMOND_ORE");
        assertThat(TABLE.pick(0.999, 10, 10)).contains("DIAMOND_ORE");
    }
}
