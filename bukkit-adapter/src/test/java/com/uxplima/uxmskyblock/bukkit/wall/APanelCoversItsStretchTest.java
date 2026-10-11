package com.uxplima.uxmskyblock.bukkit.wall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;

import com.uxplima.uxmskyblock.core.domain.wall.WallSegment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A stretch of wall is drawn in pieces from its bottom to its top, each standing just inside the ground it closes,
 * stretched over the whole stretch.
 */
class APanelCoversItsStretchTest {

    private static final WallSegment NORTH = new WallSegment(WallSegment.Side.NORTH, 32, 16, 32);
    private static final WallSegment EAST = new WallSegment(WallSegment.Side.EAST, 48, -16, 0);

    @Test
    @DisplayName("A colour wall is two panels back to back per piece, in the middle of the stretch, just inside")
    void aColourWallIsTwoSided() {
        List<PanelShape.Panel> panels = PanelShape.of(NORTH, WallStyle.Kind.COLOUR, 0, 128, 64);

        assertThat(panels).hasSize(4);
        assertThat(panels).extracting(PanelShape.Panel::yaw).containsExactly(0f, 180f, 0f, 180f);
        assertThat(panels).extracting(PanelShape.Panel::y).containsExactly(0.0, 0.0, 64.0, 64.0);
        PanelShape.Panel first = panels.getFirst();
        assertThat(first.x()).isEqualTo(24.0);
        assertThat(first.z()).isCloseTo(32.0 + PanelShape.INSIDE, within(1e-9));
        assertThat(first.scaleX() * PanelShape.TEXT_WIDTH)
                .describedAs("as wide as the stretch")
                .isEqualTo(16f);
        assertThat(first.scaleY() * PanelShape.TEXT_HEIGHT)
                .describedAs("as tall as a piece")
                .isEqualTo(64f);
        assertThat(first.height()).isEqualTo(64f);
        assertThat(first.width()).isGreaterThan(16f);
    }

    @Test
    @DisplayName("A wall along z faces west and east, stands just inside its x, and the last piece stops at the top")
    void aWallAlongZ() {
        List<PanelShape.Panel> panels = PanelShape.of(EAST, WallStyle.Kind.COLOUR, 0, 100, 64);

        assertThat(panels).extracting(PanelShape.Panel::yaw).containsExactly(90f, -90f, 90f, -90f);
        assertThat(panels.getFirst().x()).isCloseTo(48.0 - PanelShape.INSIDE, within(1e-9));
        assertThat(panels.getFirst().z()).isEqualTo(-8.0);
        assertThat(panels.get(2).scaleY() * PanelShape.TEXT_HEIGHT).isEqualTo(36f);
    }

    @Test
    @DisplayName("A block wall is one thin box per piece, its corner moved back onto the stretch's start and plane")
    void aBlockWallIsAThinBox() {
        PanelShape.Panel north =
                PanelShape.of(NORTH, WallStyle.Kind.BLOCK, 0, 64, 64).getFirst();

        assertThat(north.x() + north.moveX()).isCloseTo(16.0, within(1e-5));
        assertThat(north.z() + north.moveZ() + PanelShape.THICKNESS / 2).isCloseTo(32.0, within(1e-5));
        assertThat(north.scaleX()).isEqualTo(16f);
        assertThat(north.scaleZ()).isEqualTo(PanelShape.THICKNESS);

        PanelShape.Panel east =
                PanelShape.of(EAST, WallStyle.Kind.BLOCK, 0, 64, 64).getFirst();

        assertThat(east.x() + east.moveX() + PanelShape.THICKNESS / 2).isCloseTo(48.0, within(1e-5));
        assertThat(east.z() + east.moveZ()).isCloseTo(-16.0, within(1e-5));
        assertThat(east.scaleZ()).isEqualTo(16f);
        assertThat(east.scaleX()).isEqualTo(PanelShape.THICKNESS);
        assertThat(PanelShape.of(EAST, WallStyle.Kind.BLOCK, -64, 320, 64)).hasSize(6);
    }

    @Test
    @DisplayName("Every entity stands in the chunk of the ground it closes")
    void everyEntityStandsInItsChunk() {
        for (WallSegment wall : List.of(
                new WallSegment(WallSegment.Side.NORTH, 0, 0, 16),
                new WallSegment(WallSegment.Side.SOUTH, 16, 0, 16),
                new WallSegment(WallSegment.Side.WEST, 0, 0, 16),
                new WallSegment(WallSegment.Side.EAST, 16, 0, 16))) {
            for (WallStyle.Kind kind : WallStyle.Kind.values()) {
                assertThat(PanelShape.of(wall, kind, 0, 64, 64))
                        .describedAs("%s %s", wall.side(), kind)
                        .allSatisfy(panel -> {
                            assertThat(Math.floorDiv((int) Math.floor(panel.x()), 16))
                                    .isZero();
                            assertThat(Math.floorDiv((int) Math.floor(panel.z()), 16))
                                    .isZero();
                        });
            }
        }
    }
}
