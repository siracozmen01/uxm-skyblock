package com.uxplima.uxmskyblock.bukkit.world;

import static org.assertj.core.api.Assertions.assertThat;

import org.bukkit.Material;
import org.bukkit.World;

import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.domain.gamemode.GameModeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A player arrives on a new island standing: on ground that does not hurt, with room for feet and
 * head, at the height nearest the one planned. On generated land the planned height was inside a hill
 * or over a drop.
 */
class APlayerArrivesStandingTest extends MockBukkitHarness {

    private static final int X = 40;
    private static final int Z = -40;

    @SuppressWarnings("NullAway.Init")
    private World world;

    @BeforeEach
    void setUpWorld() {
        world = server.addSimpleWorld("boxed");
        fill(world.getMinHeight(), world.getMaxHeight() - 1, Material.AIR);
    }

    @Test
    @DisplayName("A planned height already safe is kept")
    void aSafeHeightIsKept() {
        fill(60, 99, Material.STONE);

        assertThat(SafeArrival.standingY(world, X, 100, Z)).hasValue(100);
    }

    @Test
    @DisplayName("Over a drop the player arrives on the ground below, and inside a hill on its top")
    void overADropAndInsideAHill() {
        fill(60, 80, Material.STONE);
        assertThat(SafeArrival.standingY(world, X, 100, Z)).hasValue(81);

        fill(60, 120, Material.STONE);
        assertThat(SafeArrival.standingY(world, X, 100, Z)).hasValue(121);
    }

    @Test
    @DisplayName("In a cave the nearest floor is chosen over the roof far above")
    void theNearestFloorWins() {
        fill(60, world.getMaxHeight() - 1, Material.STONE);
        fill(95, 97, Material.AIR);

        assertThat(SafeArrival.standingY(world, X, 100, Z)).hasValue(95);
    }

    @Test
    @DisplayName("Lava or magma underfoot and water at the head are not safe, and empty air has no answer")
    void hazardsAreRefused() {
        fill(60, 79, Material.STONE);
        world.getBlockAt(X, 80, Z).setType(Material.LAVA);
        assertThat(SafeArrival.safe(world, X, 81, Z)).isFalse();
        world.getBlockAt(X, 80, Z).setType(Material.MAGMA_BLOCK);
        assertThat(SafeArrival.safe(world, X, 81, Z))
                .describedAs("solid, and it burns")
                .isFalse();

        world.getBlockAt(X, 80, Z).setType(Material.STONE);
        world.getBlockAt(X, 82, Z).setType(Material.WATER);
        assertThat(SafeArrival.safe(world, X, 81, Z)).isFalse();

        fill(world.getMinHeight(), world.getMaxHeight() - 1, Material.AIR);
        assertThat(SafeArrival.standingY(world, X, 100, Z)).isEmpty();
    }

    @Test
    @DisplayName("Under water, water is room to arrive in and to make, and lava still is not")
    void waterIsRoomUnderWater() {
        fill(60, 79, Material.STONE);
        fill(80, 89, Material.WATER);
        world.getBlockAt(X, 90, Z).setType(Material.STONE);

        assertThat(SafeArrival.standingY(world, X, 80, Z))
                .describedAs("on land, water is no room")
                .hasValue(91);
        assertThat(SafeArrival.standingY(world, X, 80, Z, true)).hasValue(80);
        world.getBlockAt(X, 81, Z).setType(Material.LAVA);
        assertThat(SafeArrival.safe(world, X, 80, Z, true)).isFalse();

        fill(world.getMinHeight(), world.getMaxHeight() - 1, Material.SANDSTONE);
        SafeArrival.makeRoom(world, X, 101, Z, true);
        assertThat(world.getBlockAt(X, 101, Z).getType()).isEqualTo(Material.WATER);
        assertThat(world.getBlockAt(X, 102, Z).getType()).isEqualTo(Material.WATER);
        assertThat(SafeArrival.safe(world, X, 101, Z, true)).isTrue();
        assertThat(GameModeType.POSEIDON.playedUnderwater()).isTrue();
        assertThat(GameModeType.ACID_ISLAND.playedUnderwater()).isFalse();
    }

    @Test
    @DisplayName("Nobody arrives above the world's logical height, the Nether's bedrock roof")
    void notAboveTheRoof() {
        World nether = org.mockito.Mockito.spy(world);
        org.mockito.Mockito.when(nether.getLogicalHeight()).thenReturn(80);
        int roof = nether.getMinHeight() + 80;
        fill(60, roof - 1, Material.STONE);

        assertThat(SafeArrival.safe(nether, X, roof, Z)).isFalse();
        assertThat(SafeArrival.standingY(nether, X, 70, Z)).isEmpty();
        assertThat(SafeArrival.standingY(world, X, 70, Z))
                .describedAs("where play goes on above it, the top of the rock is fine")
                .hasValue(roof);
    }

    @Test
    @DisplayName("Where rock reaches past the search, room is cleared for feet and head and the ground is kept")
    void roomIsMadeInSolidRock() {
        fill(world.getMinHeight(), world.getMaxHeight() - 1, Material.NETHERRACK);
        world.getBlockAt(X, 100, Z).setType(Material.GRASS_BLOCK);
        assertThat(SafeArrival.standingY(world, X, 101, Z)).isEmpty();

        SafeArrival.makeRoom(world, X, 101, Z);

        assertThat(SafeArrival.safe(world, X, 101, Z)).isTrue();
        assertThat(world.getBlockAt(X, 100, Z).getType()).isEqualTo(Material.GRASS_BLOCK);
        assertThat(world.getBlockAt(X, 103, Z).getType()).isEqualTo(Material.NETHERRACK);
    }

    @Test
    @DisplayName("A centre in the sea arrives on the nearest ground of its own chunk, never of the chunk beside it")
    void aCentreInTheSeaArrivesOnItsShore() {
        int floor = 40;
        int surface = 62;
        clearTheChunkAndItsNeighbour();
        column(X, Z, floor, floor, Material.STONE);
        column(X, Z, floor + 1, surface, Material.WATER);
        // Land at the far corner of the chunk, and nearer land just over its edge in the next chunk.
        column(47, -33, floor, 70, Material.STONE);
        column(31, -40, floor, 70, Material.STONE);

        assertThat(SafeArrival.standingY(world, X, 101, Z))
                .describedAs("the sea's own column has nowhere to stand")
                .isEmpty();
        assertThat(SafeArrival.standingInChunk(world, X, 101, Z, false)).hasValue(new SafeArrival.Spot(47, 71, -33));
        assertThat(SafeArrival.standingInChunk(world, X, 101, Z, true))
                .describedAs("under water, the sea floor of the centre is the place")
                .hasValue(new SafeArrival.Spot(X, floor + 1, Z));
    }

    @Test
    @DisplayName("A chunk that is all sea arrives at the top of the water, not high above it")
    void anAllSeaChunkArrivesOnTheWater() {
        clearTheChunkAndItsNeighbour();
        column(X, Z, 40, 40, Material.STONE);
        column(X, Z, 41, 62, Material.WATER);

        assertThat(SafeArrival.standingInChunk(world, X, 101, Z, false)).isEmpty();
        assertThat(SafeArrival.onTheWater(world, X, Z)).hasValue(63);
        column(X, Z, 63, 63, Material.STONE);
        assertThat(SafeArrival.onTheWater(world, X, Z))
                .describedAs("a top that is not water")
                .isEmpty();
    }

    /** The chunk the test column stands in, 32 to 47 by -48 to -33, and the column at x 31 beside it, all air. */
    private void clearTheChunkAndItsNeighbour() {
        for (int x = 31; x <= 47; x++) {
            for (int z = -48; z <= -33; z++) {
                column(x, z, world.getMinHeight(), world.getMaxHeight() - 1, Material.AIR);
            }
        }
    }

    private void column(int x, int z, int fromY, int toY, Material material) {
        for (int y = fromY; y <= toY; y++) {
            world.getBlockAt(x, y, z).setType(material);
        }
    }

    private void fill(int fromY, int toY, Material material) {
        for (int y = fromY; y <= toY; y++) {
            world.getBlockAt(X, y, Z).setType(material);
        }
    }
}
