package com.uxplima.uxmskyblock.bukkit.boundary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import org.bukkit.WorldBorder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A client draws a border standing still in blue, one growing in green and one shrinking in red. A coloured border
 * stands at the island's edge and moves by a block over sixty years.
 */
class ABorderIsDrawnInItsColourTest extends com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness {

    @Test
    @DisplayName("A player's island border is drawn in the colour they picked, and blue before they pick one")
    @SuppressWarnings({"deprecation", "removal"})
    void theAdapterDrawsThePlayersColour() {
        // MockBukkit has no per-player border, so this player keeps the one it is handed.
        var drawn = new java.util.ArrayList<WorldBorder>();
        var ada = new org.mockbukkit.mockbukkit.entity.PlayerMock(server, "Ada") {
            @Override
            public void setWorldBorder(@org.jspecify.annotations.Nullable WorldBorder border) {
                drawn.add(border);
            }
        };
        server.addPlayer(ada);
        var scheduler = mock(com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort.class);
        org.mockito.Mockito.doAnswer(call -> {
                    call.getArgument(1, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .onEntity(
                        org.mockito.ArgumentMatchers.any(com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid.class),
                        org.mockito.ArgumentMatchers.any(Runnable.class));
        WorldBorder first = mock(WorldBorder.class);
        WorldBorder second = mock(WorldBorder.class);
        var made = new java.util.ArrayDeque<>(java.util.List.of(first, second));
        var adapter = new WorldBorderPacketAdapter(scheduler, made::pop);
        var uuid = new com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid(ada.getUniqueId());

        adapter.sendWorldBorder(uuid, 0, 0, 25, 0.0, 0L);
        org.mockito.Mockito.verify(first).setSize(51.0);
        org.mockito.Mockito.verify(first, org.mockito.Mockito.never())
                .setSize(org.mockito.ArgumentMatchers.anyDouble(), org.mockito.ArgumentMatchers.anyLong());

        BorderChoice.keep(ada, BorderColour.RED);
        adapter.sendWorldBorder(uuid, 0, 0, 25, 0.0, 0L);
        org.mockito.Mockito.verify(second).setSize(50.0, BorderColour.FOREVER);
        assertThat(drawn).containsExactly(first, second);
    }

    @Test
    @DisplayName("Blue stands still, green grows and red shrinks, each from the island's own width")
    @SuppressWarnings({"deprecation", "removal"})
    void eachColourMovesItsOwnWay() {
        WorldBorder blue = mock(WorldBorder.class);
        BorderColour.BLUE.paint(blue, 51.0);
        org.mockito.Mockito.verify(blue).setSize(51.0);
        verifyNoMoreInteractions(blue);

        WorldBorder green = mock(WorldBorder.class);
        BorderColour.GREEN.paint(green, 51.0);
        var grows = inOrder(green);
        grows.verify(green).setSize(51.0);
        grows.verify(green).setSize(52.0, BorderColour.FOREVER);

        WorldBorder red = mock(WorldBorder.class);
        BorderColour.RED.paint(red, 51.0);
        var shrinks = inOrder(red);
        shrinks.verify(red).setSize(51.0);
        shrinks.verify(red).setSize(50.0, BorderColour.FOREVER);
    }

    @Test
    @DisplayName("A colour is read in any case, and a word that names none is no colour")
    void aColourIsReadByName() {
        assertThat(BorderColour.of("Green")).contains(BorderColour.GREEN);
        assertThat(BorderColour.of(" red ")).contains(BorderColour.RED);
        assertThat(BorderColour.of("purple")).isEmpty();
        assertThat(BorderColour.FOREVER)
                .describedAs("decades, so the border never visibly moves")
                .isGreaterThan(60L * 365 * 24 * 3600);
    }
}
