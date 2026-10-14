package com.uxplima.uxmskyblock.bukkit.brix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import com.mojang.brigadier.CommandDispatcher;
import com.uxplima.uxmskyblock.bukkit.command.IslandCommandTree;
import com.uxplima.uxmskyblock.bukkit.config.BrixConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.HomeConfiguration;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.schematic.StarterSchematicEngine;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.bank.IslandBankService;
import com.uxplima.uxmskyblock.core.application.biome.BiomeModificationPort;
import com.uxplima.uxmskyblock.core.application.brix.BrixPlotsPort;
import com.uxplima.uxmskyblock.core.application.brix.BrixService;
import com.uxplima.uxmskyblock.core.application.flag.IslandFlagService;
import com.uxplima.uxmskyblock.core.application.island.CreateIslandUseCase;
import com.uxplima.uxmskyblock.core.application.island.IslandAccessService;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.leaderboard.IslandLeaderboardService;
import com.uxplima.uxmskyblock.core.application.preset.StarterPresetCatalog;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.upgrade.IslandUpgradeStoragePort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * {@code /is plot reset}: a plot's owner, saying so twice within the half minute, has everything built in its bounds
 * cleared and its ground laid again under its spawn; anyone there is sent to the spawn.
 */
class APlotIsResetToItsGroundTest extends MockBukkitHarness {

    private static final int TOP = 100;
    private static final BrixConfiguration.Ground GROUND =
            new BrixConfiguration.Ground(3, List.of(Material.GRASS_BLOCK, Material.DIRT, Material.BEDROCK));

    private final Set<IslandId> plots = new HashSet<>();
    private final Instant[] now = {Instant.parse("2026-10-14T12:00:00Z")};
    private boolean spawnKnown = true;
    private String spawnWorld = "plots";

    @SuppressWarnings("NullAway.Init")
    private IslandProtectionListener islands;

    @SuppressWarnings("NullAway.Init")
    private World world;

    @SuppressWarnings("NullAway.Init")
    private Island plot;

    @SuppressWarnings("NullAway.Init")
    private CommandDispatcher<CommandSourceStack> dispatcher;

    @SuppressWarnings("NullAway.Init")
    private PlayerMock owner;

    @SuppressWarnings("NullAway.Init")
    private PlayerMock mate;

    @BeforeEach
    void setUpThePlot() {
        world = server.addSimpleWorld("plots");
        owner = createMovingPlayer("Owner");
        mate = createMovingPlayer("Mate");
        plot = Island.create(
                IslandId.of(UUID.randomUUID()),
                IslandBounds.fromCenterAndRadius(8, 8, 6),
                new PlayerUuid(owner.getUniqueId()),
                new ProfileId(UUID.randomUUID()),
                Instant.now());
        islands = new IslandProtectionListener(mock(IslandStoragePort.class), new IslandAccessService());
        islands.cacheIsland(plot, "plots");
        Island second = Island.create(
                IslandId.of(UUID.randomUUID()),
                IslandBounds.fromCenterAndRadius(108, 8, 6),
                new PlayerUuid(owner.getUniqueId()),
                new ProfileId(UUID.randomUUID()),
                Instant.now());
        islands.cacheIsland(second, "plots");
        BrixService service = new BrixService(new BrixPlotsPort() {
            @Override
            public Set<IslandId> findAll() {
                return Set.copyOf(plots);
            }

            @Override
            public boolean exists(IslandId islandId) {
                return plots.contains(islandId);
            }

            @Override
            public void add(IslandId islandId) {
                plots.add(islandId);
            }
        });
        service.start(plot.id());
        service.start(second.id());
        SchedulerPort scheduler = mock(SchedulerPort.class);
        doAnswer(call -> {
                    call.getArgument(0, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .async(any());
        doAnswer(call -> {
                    call.getArgument(1, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .onEntity(any(PlayerUuid.class), any(Runnable.class));
        doAnswer(call -> {
                    call.getArgument(3, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .onRegion(anyString(), anyInt(), anyInt(), any(Runnable.class));
        Clock clock = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now[0];
            }
        };
        PlotReset reset = new PlotReset(
                service,
                islands,
                id -> spawnKnown && id.equals(plot.id())
                        ? Optional.of(
                                new IslandLocation(plot.id(), spawnWorld, plot.bounds(), 8.5, TOP + 1, 8.5, 90, 0))
                        : Optional.empty(),
                scheduler,
                GROUND,
                Messages.bundled(),
                clock);
        IslandCommandTree tree = new IslandCommandTree(
                mock(CreateIslandUseCase.class),
                mock(IslandLocationService.class),
                mock(IslandFlagService.class),
                mock(IslandBankService.class),
                mock(IslandUpgradeStoragePort.class),
                mock(IslandLeaderboardService.class),
                mock(BiomeModificationPort.class),
                new StarterPresetCatalog(),
                mock(StarterSchematicEngine.class),
                islands,
                mock(PlayerSessionCoordinator.class),
                scheduler,
                Messages.bundled(),
                HomeConfiguration.defaults(),
                ServerNodeId.of("node-1"),
                "world");
        tree.usePlotReset(reset);
        dispatcher = new CommandDispatcher<>();
        dispatcher.register(tree.buildRoot());
        owner.teleport(new Location(world, 8.5, 120, 8.5));
        mate.teleport(new Location(world, 4.5, 110, 12.5));
    }

    private void run(Player player, String line) throws Exception {
        CommandSourceStack source = mock(CommandSourceStack.class);
        org.mockito.Mockito.when(source.getSender()).thenReturn(player);
        dispatcher.execute(line, source);
    }

    private static String said(PlayerMock player) {
        StringBuilder all = new StringBuilder();
        Component next;
        while ((next = player.nextComponentMessage()) != null) {
            all.append(PlainTextComponentSerializer.plainText().serialize(next)).append('\n');
        }
        return all.toString();
    }

    private void build() {
        world.getBlockAt(10, 110, 10).setType(Material.STONE);
        world.getBlockAt(2, 60, 14).setType(Material.GOLD_BLOCK);
        world.getBlockAt(13, TOP, 13).setType(Material.OAK_PLANKS);
        world.getBlockAt(20, 110, 8).setType(Material.DIAMOND_BLOCK);
        world.spawn(new Location(world, 9.5, 111, 9.5), ArmorStand.class);
    }

    @Test
    @DisplayName(
            "Said twice, everything in the bounds is cleared, the ground laid under the spawn, and those there sent to it")
    void reset() throws Exception {
        build();
        said(owner);
        run(owner, "island plot reset");
        assertThat(said(owner)).contains("/is plot reset confirm");
        assertThat(world.getBlockAt(10, 110, 10).getType())
                .describedAs("nothing cleared yet")
                .isEqualTo(Material.STONE);
        now[0] = now[0].plusSeconds(20);
        run(owner, "island plot reset confirm");
        assertThat(said(owner)).contains("being cleared");

        assertThat(world.getBlockAt(10, 110, 10).getType()).isEqualTo(Material.AIR);
        assertThat(world.getBlockAt(2, 60, 14).getType())
                .describedAs("the corner of the bounds")
                .isEqualTo(Material.AIR);
        assertThat(world.getBlockAt(13, TOP, 13).getType())
                .describedAs("in the bounds but past the ground")
                .isEqualTo(Material.AIR);
        assertThat(world.getBlockAt(20, 110, 8).getType())
                .describedAs("past the bounds")
                .isEqualTo(Material.DIAMOND_BLOCK);
        assertThat(world.getBlockAt(8, world.getMinHeight(), 8).getType())
                .describedAs("down to the floor")
                .isEqualTo(Material.AIR);
        for (int x = 5; x <= 11; x++) {
            assertThat(world.getBlockAt(x, TOP, 11).getType()).isEqualTo(Material.GRASS_BLOCK);
            assertThat(world.getBlockAt(x, TOP - 1, 5).getType()).isEqualTo(Material.DIRT);
            assertThat(world.getBlockAt(x, TOP - 2, 8).getType()).isEqualTo(Material.BEDROCK);
            assertThat(world.getBlockAt(x, TOP + 1, 8).getType()).isEqualTo(Material.AIR);
        }
        // The ground reaches three from the centre at 8: from 5 to 11 each way, and no further.
        for (int[] edge : new int[][] {{5, 8}, {11, 8}, {8, 5}, {8, 11}}) {
            assertThat(world.getBlockAt(edge[0], TOP, edge[1]).getType()).isEqualTo(Material.GRASS_BLOCK);
        }
        for (int[] past : new int[][] {{4, 8}, {12, 8}, {8, 4}, {8, 12}}) {
            assertThat(world.getBlockAt(past[0], TOP, past[1]).getType())
                    .describedAs("past the ground's radius at %s", java.util.Arrays.toString(past))
                    .isEqualTo(Material.AIR);
        }
        assertThat(world.getEntitiesByClass(ArmorStand.class)).isEmpty();
        assertThat(owner.getLocation().getY()).describedAs("sent to the spawn").isEqualTo(TOP + 1);
        assertThat(mate.getLocation().getBlockX()).isEqualTo(8);
        assertThat(owner.getLocation().getYaw()).isEqualTo(90);
    }

    @Test
    @DisplayName(
            "Only the owner resets, only on a plot, only having asked within the half minute, and only with a spawn")
    void notReset() throws Exception {
        build();
        said(owner);
        said(mate);
        run(owner, "island plot reset confirm");
        assertThat(said(owner)).contains("No reset of this plot was asked");

        run(owner, "island plot reset");
        now[0] = now[0].plus(PlotReset.CONFIRM_WITHIN).plusSeconds(1);
        run(owner, "island plot reset confirm");
        assertThat(said(owner)).contains("No reset of this plot was asked");

        run(owner, "island plot reset");
        owner.teleport(new Location(world, 108.5, 120, 8.5));
        run(owner, "island plot reset confirm");
        assertThat(said(owner)).describedAs("asked of another plot").contains("No reset of this plot was asked");
        owner.teleport(new Location(world, 8.5, 120, 8.5));

        run(mate, "island plot reset");
        assertThat(said(mate)).contains("Only this plot's owner");

        spawnKnown = false;
        run(owner, "island plot reset");
        run(owner, "island plot reset confirm");
        assertThat(said(owner)).contains("could not be reset");
        spawnKnown = true;

        run(owner, "island plot reset");
        run(owner, "island plot reset confirm");
        assertThat(said(owner)).describedAs("asked once, done once").contains("being cleared");
        build();
        run(owner, "island plot reset confirm");
        assertThat(said(owner)).contains("No reset of this plot was asked");

        // The owner stands where the plot's bounds are cached in another world: the plot's own world is cleared.
        World elsewhere = server.addSimpleWorld("plots_nether");
        islands.cacheIsland(plot, "plots_nether");
        elsewhere.getBlockAt(10, 110, 10).setType(Material.NETHERRACK);
        owner.teleport(new Location(elsewhere, 8.5, 120, 8.5));
        run(owner, "island plot reset");
        run(owner, "island plot reset confirm");
        assertThat(said(owner)).contains("being cleared");
        assertThat(elsewhere.getBlockAt(10, 110, 10).getType())
                .describedAs("the world the owner stands in is not the plot's")
                .isEqualTo(Material.NETHERRACK);
        assertThat(world.getBlockAt(10, 110, 10).getType()).isEqualTo(Material.AIR);
        islands.cacheIsland(plot, "plots");
        build();

        spawnWorld = "gone";
        owner.teleport(new Location(world, 8.5, 120, 8.5));
        run(owner, "island plot reset");
        run(owner, "island plot reset confirm");
        assertThat(said(owner)).describedAs("a plot whose world is not loaded").contains("could not be reset");
        spawnWorld = "plots";

        owner.teleport(new Location(world, 100, 120, 100));
        run(owner, "island plot reset");
        assertThat(said(owner)).contains("Brix plot");
        assertThat(world.getBlockAt(10, 110, 10).getType())
                .describedAs("nothing cleared since")
                .isEqualTo(Material.STONE);
        assertThat(Duration.ofSeconds(30)).isEqualTo(PlotReset.CONFIRM_WITHIN);
    }
}
