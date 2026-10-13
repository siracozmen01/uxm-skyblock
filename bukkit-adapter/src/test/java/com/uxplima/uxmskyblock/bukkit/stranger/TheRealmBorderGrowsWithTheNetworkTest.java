package com.uxplima.uxmskyblock.bukkit.stranger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldBorder;

import com.uxplima.uxmskyblock.bukkit.config.StrangerRealmsConfiguration;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.stranger.StrangerRealmsPort;
import com.uxplima.uxmskyblock.core.application.stranger.StrangerRealmsService;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.stranger.RealmBorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The border of the StrangerRealms land holds the furthest StrangerRealms island the network has, read
 * off the shared database, and moves only when it must, in the worlds it is set in.
 */
class TheRealmBorderGrowsWithTheNetworkTest {

    private final Set<IslandId> stranger = new HashSet<>();
    private final WorldBorder border = mock(WorldBorder.class);
    private final World realms = mock(World.class);
    private int reach = 5_120;
    private boolean databaseDown;

    @SuppressWarnings("NullAway.Init")
    private SchedulerPort scheduler;

    @SuppressWarnings("NullAway.Init")
    private RealmBorders borders;

    @SuppressWarnings("NullAway.Init")
    private StrangerRealmsService service;

    @BeforeEach
    void setUpBorder() {
        stranger.add(IslandId.of(UUID.randomUUID()));
        service = new StrangerRealmsService(new StrangerRealmsPort() {
            @Override
            public Set<IslandId> findAll() {
                return Set.copyOf(stranger);
            }

            @Override
            public boolean exists(IslandId islandId) {
                return stranger.contains(islandId);
            }

            @Override
            public void add(IslandId islandId) {
                stranger.add(islandId);
            }

            @Override
            public int farthestReach() {
                if (databaseDown) {
                    throw new IllegalStateException("the database is gone");
                }
                return reach;
            }
        });
        scheduler = mock(SchedulerPort.class);
        doAnswer(call -> {
                    call.getArgument(0, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .async(any(Runnable.class));
        doAnswer(call -> {
                    call.getArgument(0, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .onGlobal(any(Runnable.class));
        when(realms.getWorldBorder()).thenReturn(border);
        when(border.getCenter()).thenReturn(new Location(realms, 0, 0, 0));
        when(border.getSize()).thenReturn(60_000_000.0);
        borders = new RealmBorders(
                service,
                scheduler,
                new StrangerRealmsConfiguration.Border(
                        true,
                        Duration.ofMinutes(1),
                        16,
                        -16,
                        new RealmBorder(100, 500, 100_000),
                        Duration.ofSeconds(10),
                        List.of()),
                () -> List.of("realms", "not_loaded"),
                name -> name.equals("realms") ? realms : null,
                () -> {
                    asked++;
                    return players;
                });
    }

    private int players;
    private int asked;

    @Test
    @DisplayName("The border is centred on the grid and grows to hold the furthest island, over the transition")
    void itHoldsTheFurthest() {
        borders.round();

        verify(border).setCenter(16, -16);
        verify(border).changeSize(10_440, 200);
    }

    @Test
    @DisplayName(
            "By players, the border widens for each player on the network and narrows as they leave, never below the islands")
    void byPlayers() {
        RealmBorders byPlayers = new RealmBorders(
                service,
                scheduler,
                new StrangerRealmsConfiguration.Border(
                        true,
                        Duration.ofMinutes(1),
                        16,
                        -16,
                        new RealmBorder(100, 500, 12_000, 200),
                        Duration.ofSeconds(10),
                        List.of()),
                () -> List.of("realms"),
                name -> name.equals("realms") ? realms : null,
                () -> players);
        players = 3;
        byPlayers.round();
        verify(border).changeSize(11_040, 200);

        players = 0;
        byPlayers.round();
        verify(border).changeSize(10_440, 200);

        players = 50;
        byPlayers.round();
        verify(border).changeSize(12_000, 200);
    }

    @Test
    @DisplayName("The width for each player is read from the file, none as shipped")
    void perPlayerIsRead() throws Exception {
        assertThat(StrangerRealmsConfiguration.load(
                                org.spongepowered.configurate.hocon.HoconConfigurationLoader.builder()
                                        .buildAndLoadString("border { per-player = 64 }"))
                        .border()
                        .rule()
                        .perPlayer())
                .isEqualTo(64);
        assertThat(StrangerRealmsConfiguration.load(
                                org.spongepowered.configurate.hocon.HoconConfigurationLoader.builder()
                                        .buildAndLoadString(new String(
                                                TheRealmBorderGrowsWithTheNetworkTest.class
                                                        .getResourceAsStream("/modules/strangerrealms.conf")
                                                        .readAllBytes(),
                                                java.nio.charset.StandardCharsets.UTF_8)))
                        .border()
                        .rule())
                .isEqualTo(RealmBorder.SHIPPED);
    }

    @Test
    @DisplayName("By islands alone, nobody is counted")
    void byIslandsCountsNobody() {
        players = 40;
        borders.round();
        verify(border).changeSize(10_440, 200);
        assertThat(asked).isZero();
    }

    @Test
    @DisplayName("A border off the centre on one side only is centred again")
    void oneSideOffIsCentred() {
        when(border.getCenter()).thenReturn(new Location(realms, 16, 0, 40));

        borders.round();

        verify(border).setCenter(16, -16);
    }

    @Test
    @DisplayName("A border already where it belongs is not moved, and a database that cannot be read moves nothing")
    void itMovesOnlyWhenItMust() {
        when(border.getCenter()).thenReturn(new Location(realms, 16, 0, -16));
        when(border.getSize()).thenReturn(10_440.4);
        borders.round();
        verify(border, never()).setCenter(anyDouble(), anyDouble());
        verify(border, never()).changeSize(anyDouble(), anyLong());

        databaseDown = true;
        when(border.getSize()).thenReturn(1.0);
        borders.round();
        verify(border, never()).changeSize(anyDouble(), anyLong());
    }

    @Test
    @DisplayName("The beat reads at once and then on the operator's beat")
    void theBeat() {
        borders.start();

        verify(scheduler)
                .repeatGlobal(
                        any(Runnable.class),
                        any(Duration.class),
                        org.mockito.ArgumentMatchers.eq(Duration.ofMinutes(1)));
        assertThat(reach).isEqualTo(5_120);
    }
}
