package com.uxplima.uxmskyblock.core.domain.wall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;

import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkPos;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A wall stands on every side of an open chunk whose neighbour is closed, and around an island's square, on the
 * planes between blocks.
 */
class AWallStandsWhereTheGroundEndsTest {

    @Test
    @DisplayName("One open chunk has a wall on each of its four sides, on the planes around its sixteen blocks")
    void oneChunkIsWalledOnFourSides() {
        ChunkPos chunk = new ChunkPos(2, -1);

        assertThat(WallOutline.of(chunk, Set.of(chunk)))
                .containsExactlyInAnyOrder(
                        new WallSegment(WallSegment.Side.NORTH, -16, 32, 48),
                        new WallSegment(WallSegment.Side.SOUTH, 0, 32, 48),
                        new WallSegment(WallSegment.Side.WEST, 32, -16, 0),
                        new WallSegment(WallSegment.Side.EAST, 48, -16, 0));
    }

    @Test
    @DisplayName("Two open chunks side by side have no wall between them, and an L of three has eight walls")
    void openNeighboursShareNoWall() {
        ChunkPos a = new ChunkPos(0, 0);
        ChunkPos b = new ChunkPos(1, 0);
        ChunkPos c = new ChunkPos(1, 1);

        assertThat(WallOutline.of(a, Set.of(a, b)))
                .extracting(WallSegment::side)
                .doesNotContain(WallSegment.Side.EAST)
                .hasSize(3);
        assertThat(WallOutline.of(Set.of(a, b, c))).hasSize(8);
        assertThat(WallOutline.of(c, Set.of(a, b)))
                .describedAs("a closed chunk stands no wall of its own")
                .isEmpty();
    }

    @Test
    @DisplayName("An island's square is walled on its edges, each side cut into stretches no longer than asked")
    void anIslandIsWalledInStretches() {
        IslandBounds bounds = IslandBounds.fromCenterAndRadius(0, 0, 12);

        var walls = WallOutline.of(bounds, 16);

        assertThat(walls).hasSize(8);
        assertThat(walls)
                .filteredOn(wall -> wall.side() == WallSegment.Side.NORTH)
                .containsExactly(
                        new WallSegment(WallSegment.Side.NORTH, -12, -12, 4),
                        new WallSegment(WallSegment.Side.NORTH, -12, 4, 13));
        assertThat(walls)
                .filteredOn(wall -> wall.side() == WallSegment.Side.EAST)
                .extracting(WallSegment::line)
                .containsOnly(13);
        assertThat(walls).allMatch(wall -> wall.length() <= 16);
        assertThatThrownBy(() -> WallOutline.of(bounds, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("A stretch runs forward, and a north or south wall runs along x")
    void aStretchRunsForward() {
        assertThatThrownBy(() -> new WallSegment(WallSegment.Side.WEST, 0, 5, 5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(WallSegment.Side.NORTH.alongX()).isTrue();
        assertThat(WallSegment.Side.SOUTH.alongX()).isTrue();
        assertThat(WallSegment.Side.EAST.alongX()).isFalse();
        assertThat(WallSegment.Side.WEST.alongX()).isFalse();
    }
}
