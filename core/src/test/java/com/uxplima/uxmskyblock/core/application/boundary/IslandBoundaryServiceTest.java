package com.uxplima.uxmskyblock.core.application.boundary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IslandBoundaryServiceTest {

    private WorldBorderPacketPort worldBorderPort;
    private IslandBoundaryService boundaryService;

    @BeforeEach
    void setUp() {
        worldBorderPort = mock(WorldBorderPacketPort.class);
        boundaryService = new IslandBoundaryService(worldBorderPort);
    }

    @Test
    @DisplayName("perimeter viewer state toggles correctly and tracks active viewers")
    void testPerimeterToggling() {
        PlayerUuid player = new PlayerUuid(UUID.randomUUID());

        assertThat(boundaryService.isPerimeterActive(player)).isFalse();

        boolean enabled = boundaryService.togglePerimeter(player);
        assertThat(enabled).isTrue();
        assertThat(boundaryService.isPerimeterActive(player)).isTrue();
        assertThat(boundaryService.activePerimeterViewers()).contains(player);

        boolean disabled = boundaryService.togglePerimeter(player);
        assertThat(disabled).isFalse();
        assertThat(boundaryService.isPerimeterActive(player)).isFalse();
        assertThat(boundaryService.activePerimeterViewers()).doesNotContain(player);

        boundaryService.enablePerimeter(player);
        assertThat(boundaryService.isPerimeterActive(player)).isTrue();

        boundaryService.disablePerimeter(player);
        assertThat(boundaryService.isPerimeterActive(player)).isFalse();
    }

    @Test
    @DisplayName("Hiding the border takes the wall away and keeps it away on every island, and showing it draws it"
            + " where the player stands")
    void theBorderIsHiddenAndShown() {
        PlayerUuid player = new PlayerUuid(UUID.randomUUID());
        IslandBounds home = IslandBounds.fromCenterAndRadius(0, 0, 50);
        IslandBounds next = IslandBounds.fromCenterAndRadius(5120, 0, 50);
        boundaryService.handlePlayerEnterIsland(player, home);
        org.mockito.Mockito.clearInvocations(worldBorderPort);

        assertThat(boundaryService.toggleBorder(player)).isFalse();
        verify(worldBorderPort).resetWorldBorder(player);
        boundaryService.handlePlayerExitIsland(player);
        boundaryService.handlePlayerEnterIsland(player, next);
        boundaryService.handleIslandExpand(player, next, IslandBounds.fromCenterAndRadius(5120, 0, 75), 0L);
        org.mockito.Mockito.verifyNoMoreInteractions(worldBorderPort);
        assertThat(boundaryService.isBorderHidden(player)).isTrue();

        assertThat(boundaryService.toggleBorder(player)).isTrue();
        verify(worldBorderPort).sendWorldBorder(player, 5120, 0, 75, 0.0, 0L);
        assertThat(boundaryService.isBorderHidden(player)).isFalse();
    }

    @Test
    @DisplayName("A border drawn again is drawn where the player stands, and not at all while hidden or off an island")
    void theBorderIsDrawnAgain() {
        PlayerUuid player = new PlayerUuid(UUID.randomUUID());
        boundaryService.redrawBorder(player);
        org.mockito.Mockito.verifyNoInteractions(worldBorderPort);

        boundaryService.handlePlayerEnterIsland(player, IslandBounds.fromCenterAndRadius(0, 0, 50));
        boundaryService.redrawBorder(player);
        verify(worldBorderPort, org.mockito.Mockito.times(2)).sendWorldBorder(player, 0, 0, 50, 0.0, 0L);

        boundaryService.toggleBorder(player);
        boundaryService.redrawBorder(player);
        verify(worldBorderPort, org.mockito.Mockito.times(2)).sendWorldBorder(player, 0, 0, 50, 0.0, 0L);
    }

    @Test
    @DisplayName("A player marked hidden as they join is drawn no border, and one who left is forgotten")
    void aHiddenBorderOutlivesNothingButTheSession() {
        PlayerUuid player = new PlayerUuid(UUID.randomUUID());
        boundaryService.hideBorder(player);

        boundaryService.handlePlayerEnterIsland(player, IslandBounds.fromCenterAndRadius(0, 0, 50));
        org.mockito.Mockito.verifyNoInteractions(worldBorderPort);

        boundaryService.forget(player);
        assertThat(boundaryService.isBorderHidden(player)).isFalse();
        assertThat(boundaryService.toggleBorder(player))
                .describedAs("forgotten, so the first switch hides it and nothing is drawn where no island is known")
                .isFalse();
        org.mockito.Mockito.verifyNoInteractions(worldBorderPort);
    }

    @Test
    @DisplayName("spillover detection identifies breaches out of boundary accurately")
    void testSpilloverDetection() {
        IslandBounds bounds = IslandBounds.fromCenterAndRadius(0, 0, 50);

        // Inside to inside -> not spillover
        assertThat(boundaryService.isSpillover(bounds, 10, 10, 20, 20)).isFalse();

        // Inside to outside -> spillover!
        assertThat(boundaryService.isSpillover(bounds, 50, 0, 51, 0)).isTrue();
        assertThat(boundaryService.isSpillover(bounds, -50, 0, -51, 0)).isTrue();
        assertThat(boundaryService.isSpillover(bounds, 0, 50, 0, 51)).isTrue();
        assertThat(boundaryService.isSpillover(bounds, 0, -50, 0, -51)).isTrue();

        // Outside to outside -> not spillover
        assertThat(boundaryService.isSpillover(bounds, 60, 60, 70, 70)).isFalse();

        // Outside to inside -> not spillover (inward flow)
        assertThat(boundaryService.isSpillover(bounds, 60, 0, 50, 0)).isFalse();
    }

    @Test
    @DisplayName("isWithinBounds returns correct containment result")
    void testIsWithinBounds() {
        IslandBounds bounds = IslandBounds.fromCenterAndRadius(100, 200, 25);

        assertThat(boundaryService.isWithinBounds(bounds, 100, 200)).isTrue();
        assertThat(boundaryService.isWithinBounds(bounds, 75, 200)).isTrue();
        assertThat(boundaryService.isWithinBounds(bounds, 125, 225)).isTrue();
        assertThat(boundaryService.isWithinBounds(bounds, 74, 200)).isFalse();
        assertThat(boundaryService.isWithinBounds(bounds, 100, 226)).isFalse();
    }

    @Test
    @DisplayName("calculatePerimeterPoints generates complete closed loop coordinates")
    void testCalculatePerimeterPoints() {
        IslandBounds bounds = IslandBounds.fromCenterAndRadius(0, 0, 10);
        List<IslandBoundaryPoint> points = boundaryService.calculatePerimeterPoints(bounds, 64.0, 5);

        assertThat(points).isNotEmpty();
        assertThat(points).allMatch(p -> p.y() == 64.0);
        // Min X is -10, max X is +11 (with block padding)
        assertThat(points).anyMatch(p -> p.x() == -10.0 && p.z() == -10.0);
        assertThat(points).anyMatch(p -> p.x() == 11.0);
    }

    @Test
    @DisplayName("player entering island dispatches world border update to port")
    void testPlayerEnterIsland() {
        PlayerUuid player = new PlayerUuid(UUID.randomUUID());
        IslandBounds bounds = IslandBounds.fromCenterAndRadius(500, 500, 100);

        boundaryService.handlePlayerEnterIsland(player, bounds);

        verify(worldBorderPort).sendWorldBorder(player, 500, 500, 100.0, 0.0, 0L);
    }

    @Test
    @DisplayName("player exiting island resets world border on port")
    void testPlayerExitIsland() {
        PlayerUuid player = new PlayerUuid(UUID.randomUUID());

        boundaryService.handlePlayerExitIsland(player);

        verify(worldBorderPort).resetWorldBorder(player);
    }

    @Test
    @DisplayName("island expand dispatches animated transition to port")
    void testIslandExpand() {
        PlayerUuid player = new PlayerUuid(UUID.randomUUID());
        IslandBounds oldBounds = IslandBounds.fromCenterAndRadius(0, 0, 50);
        IslandBounds newBounds = IslandBounds.fromCenterAndRadius(0, 0, 75);

        boundaryService.handleIslandExpand(player, oldBounds, newBounds, 3000L);

        verify(worldBorderPort).sendWorldBorder(player, 0, 0, 75.0, 50.0, 3000L);
    }

    @Test
    @DisplayName("null arguments throw NullPointerException")
    @SuppressWarnings("NullAway")
    void testNullGuards() {
        assertThatThrownBy(() -> boundaryService.togglePerimeter(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> boundaryService.isSpillover(null, 0, 0, 0, 0))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(
                        () -> boundaryService.handlePlayerEnterIsland(null, IslandBounds.fromCenterAndRadius(0, 0, 10)))
                .isInstanceOf(NullPointerException.class);
    }
}
