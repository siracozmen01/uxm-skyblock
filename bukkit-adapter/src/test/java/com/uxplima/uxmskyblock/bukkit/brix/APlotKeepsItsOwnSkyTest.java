package com.uxplima.uxmskyblock.bukkit.brix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.WeatherType;
import org.bukkit.World;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import com.mojang.brigadier.CommandDispatcher;
import com.uxplima.uxmskyblock.bukkit.command.IslandCommandTree;
import com.uxplima.uxmskyblock.bukkit.config.BrixConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.HomeConfiguration;
import com.uxplima.uxmskyblock.bukkit.creative.InventoryCodec;
import com.uxplima.uxmskyblock.bukkit.creative.SealedInventory;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffectPlayer;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffects;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.schematic.StarterSchematicEngine;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.bank.IslandBankService;
import com.uxplima.uxmskyblock.core.application.biome.BiomeModificationPort;
import com.uxplima.uxmskyblock.core.application.brix.BrixPlotsPort;
import com.uxplima.uxmskyblock.core.application.brix.BrixService;
import com.uxplima.uxmskyblock.core.application.brix.BrixSkies;
import com.uxplima.uxmskyblock.core.application.brix.BrixSkiesPort;
import com.uxplima.uxmskyblock.core.application.flag.IslandFlagService;
import com.uxplima.uxmskyblock.core.application.island.CreateIslandUseCase;
import com.uxplima.uxmskyblock.core.application.island.IslandAccessService;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.leaderboard.IslandLeaderboardService;
import com.uxplima.uxmskyblock.core.application.preset.StarterPresetCatalog;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.upgrade.IslandUpgradeStoragePort;
import com.uxplima.uxmskyblock.core.domain.brix.PlotSky;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * A plot's team sets the time and weather everyone standing on their plot sees, with {@code /is plot time} and
 * {@code /is plot weather}; off the plot each player has the world's sky back.
 */
class APlotKeepsItsOwnSkyTest extends MockBukkitHarness {

    private final Set<IslandId> plots = new HashSet<>();
    private final Map<IslandId, PlotSky> kept = new HashMap<>();

    @SuppressWarnings("NullAway.Init")
    private World world;

    @SuppressWarnings("NullAway.Init")
    private Island plot;

    @SuppressWarnings("NullAway.Init")
    private Island notAPlot;

    @SuppressWarnings("NullAway.Init")
    private BrixModes modes;

    @SuppressWarnings("NullAway.Init")
    private BrixSkies skies;

    @SuppressWarnings("NullAway.Init")
    private CommandDispatcher<CommandSourceStack> dispatcher;

    @SuppressWarnings("NullAway.Init")
    private PlayerMock builder;

    @SuppressWarnings("NullAway.Init")
    private PlayerMock visitor;

    @BeforeEach
    void setUpThePlot() {
        world = server.addSimpleWorld("plots");
        builder = createPlayer("Builder");
        visitor = createPlayer("Visitor");
        plot = island(0, builder.getUniqueId());
        IslandProtectionListener islands =
                new IslandProtectionListener(mock(IslandStoragePort.class), new IslandAccessService());
        islands.cacheIsland(plot, "plots");
        notAPlot = island(1000, UUID.randomUUID());
        islands.cacheIsland(notAPlot, "plots");
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
        skies = new BrixSkies(new BrixSkiesPort() {
            @Override
            public Map<IslandId, PlotSky> findAll() {
                return Map.copyOf(kept);
            }

            @Override
            public void save(IslandId islandId, PlotSky sky) {
                if (sky.isWorld()) {
                    kept.remove(islandId);
                } else {
                    kept.put(islandId, sky);
                }
            }
        });
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
        modes = new BrixModes(
                service,
                islands,
                scheduler,
                BrixConfiguration.Modes.SHIPPED,
                new SealedInventory(
                        new InventoryCodec() {
                            @Override
                            public byte[] write(ItemStack[] items) {
                                return new byte[0];
                            }

                            @Override
                            public ItemStack[] read(byte[] bytes) {
                                return new ItemStack[0];
                            }
                        },
                        "brix"),
                Messages.bundled(),
                InteractionEffects.none(),
                new InteractionEffectPlayer(),
                skies);
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
        tree.usePlotSky(new PlotSkyCommands(service, skies, islands, scheduler, Messages.bundled()));
        dispatcher = new CommandDispatcher<>();
        dispatcher.register(tree.buildRoot());
        builder.teleport(new Location(world, 8.5, 101, 8.5));
        visitor.teleport(new Location(world, 10.5, 101, 10.5));
        builder.setGameMode(GameMode.SURVIVAL);
        visitor.setGameMode(GameMode.SURVIVAL);
    }

    private void run(PlayerMock player, String line) throws Exception {
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

    @Test
    @DisplayName("The team sets the time and weather, everyone on the plot sees them, and off it the world's")
    void theTeamSetsTheSky() throws Exception {
        modes.check(builder);
        modes.check(visitor);
        said(builder);
        assertThat(builder.isPlayerTimeRelative()).isTrue();

        run(builder, "island plot time midnight");
        run(builder, "island plot weather rain");
        assertThat(said(builder)).contains("midnight").contains("rain");
        assertThat(kept).containsEntry(plot.id(), new PlotSky(18_000, PlotSky.Weather.RAIN));

        modes.check(visitor);
        assertThat(visitor.isPlayerTimeRelative()).isFalse();
        assertThat(visitor.getPlayerTimeOffset()).isEqualTo(18_000);
        assertThat(visitor.getPlayerWeather()).isEqualTo(WeatherType.DOWNFALL);

        run(builder, "island plot time 6000");
        run(builder, "island plot weather clear");
        modes.check(visitor);
        assertThat(visitor.getPlayerTimeOffset()).isEqualTo(6000);
        assertThat(visitor.getPlayerWeather()).isEqualTo(WeatherType.CLEAR);

        // A sky kept for an island that is no plot, as one a plot since erased left, shows nowhere.
        skies.set(notAPlot.id(), new PlotSky(1000, PlotSky.Weather.RAIN));
        run(builder, "island plot time world");
        modes.check(visitor);
        assertThat(visitor.isPlayerTimeRelative())
                .describedAs("the world's time back")
                .isTrue();
        assertThat(visitor.getPlayerWeather())
                .describedAs("the weather still the plot's")
                .isEqualTo(WeatherType.CLEAR);

        visitor.teleport(new Location(world, 1008.5, 101, 8.5));
        modes.check(visitor);
        assertThat(visitor.isPlayerTimeRelative()).isTrue();
        assertThat(visitor.getPlayerWeather())
                .describedAs("off the plot, the world's")
                .isNull();

        run(builder, "island plot weather world");
        assertThat(kept).describedAs("all the world's again: nothing kept").doesNotContainKey(plot.id());
    }

    @Test
    @DisplayName("Only the team sets the sky, only on a plot, and only a time or weather there is")
    void notSet() throws Exception {
        said(visitor);
        run(visitor, "island plot time noon");
        assertThat(said(visitor)).contains("team");
        run(builder, "island plot time teatime");
        run(builder, "island plot time 24000");
        run(builder, "island plot weather hail");
        String told = said(builder);
        assertThat(told)
                .contains("teatime is no time")
                .contains("24000 is no time")
                .contains("hail is no weather");
        builder.teleport(new Location(world, 1008.5, 101, 8.5));
        run(builder, "island plot time noon");
        assertThat(said(builder)).describedAs("an island that is no plot").contains("Brix plot");
        builder.teleport(new Location(world, 500, 101, 500));
        run(builder, "island plot time noon");
        assertThat(said(builder)).contains("Brix plot");
        assertThat(kept).isEmpty();
    }

    @Test
    @DisplayName("A time or weather set by something else is left alone, and one the plot set is undone at the quit")
    void othersAreLeftAlone() throws Exception {
        visitor.teleport(new Location(world, 500, 101, 500));
        visitor.setPlayerTime(3000, false);
        visitor.setPlayerWeather(WeatherType.DOWNFALL);
        modes.check(visitor);
        assertThat(visitor.getPlayerTimeOffset()).describedAs("another's time").isEqualTo(3000);
        assertThat(visitor.getPlayerWeather()).isEqualTo(WeatherType.DOWNFALL);

        skies.set(plot.id(), new PlotSky(13_000, PlotSky.Weather.CLEAR));
        modes.check(builder);
        assertThat(builder.getPlayerTimeOffset()).isEqualTo(13_000);
        builder.teleport(new Location(world, 500, 101, 500));
        modes.check(builder);
        assertThat(builder.isPlayerTimeRelative())
                .describedAs("off the plot, the world's time")
                .isTrue();
        builder.setPlayerTime(3000, false);
        modes.check(builder);
        assertThat(builder.getPlayerTimeOffset())
                .describedAs("a time set by another once off the plot is theirs")
                .isEqualTo(3000);
        builder.teleport(new Location(world, 8.5, 101, 8.5));
        modes.check(builder);
        modes.onQuit(new PlayerQuitEvent(builder, Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED));
        assertThat(builder.isPlayerTimeRelative()).isTrue();
        assertThat(builder.getPlayerWeather()).isNull();
    }

    @Test
    @DisplayName("A time is written as a word or ticks, a weather as clear, rain or world")
    void theWords() {
        assertThat(List.of("day", "noon", "sunset", "night", "midnight", "sunrise", "world", "0", "23999"))
                .extracting(word -> PlotSky.timeOf(word).orElseThrow())
                .containsExactly(1000, 6000, 12_000, 13_000, 18_000, 23_000, -1, 0, 23_999);
        assertThat(PlotSky.timeOf("-1")).isEmpty();
        assertThat(PlotSky.timeOf("24000")).isEmpty();
        assertThat(PlotSky.weatherOf(" Clear ")).contains(PlotSky.Weather.CLEAR);
        assertThat(PlotSky.weatherOf("storm")).contains(PlotSky.Weather.RAIN);
        assertThat(PlotSky.weatherOf("world")).contains(PlotSky.Weather.WORLD);
        assertThat(new PlotSky(-1, PlotSky.Weather.CLEAR).isWorld()).isFalse();
        assertThat(PlotSky.WORLD.isWorld()).isTrue();
    }

    private static Island island(int centreX, UUID owner) {
        return Island.create(
                IslandId.of(UUID.randomUUID()),
                IslandBounds.fromCenterAndRadius(centreX + 8, 8, 40),
                new PlayerUuid(owner),
                new ProfileId(UUID.randomUUID()),
                Instant.now());
    }
}
