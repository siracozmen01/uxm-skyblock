package com.uxplima.uxmskyblock.bukkit.oneblock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.UUID;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.block.BlockBreakEvent;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import com.uxplima.uxmlib.menu.runtime.MenuContext;
import com.uxplima.uxmlib.menu.spec.ClickKind;
import com.uxplima.uxmskyblock.bukkit.config.OneBlockConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.OneBlockDisplay;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffectPlayer;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffects;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.menu.ShippedTemplates;
import com.uxplima.uxmskyblock.bukkit.menu.SkyblockMenuEngine;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.island.IslandAccessService;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.oneblock.OneBlockProgressPort;
import com.uxplima.uxmskyblock.core.application.oneblock.OneBlockService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

/**
 * A OneBlock breaker sees their island's progress where the server shows it, a boss bar that goes once they stop
 * breaking, until they switch it to the action bar or nowhere; their choice is kept with them.
 */
class ABreakerSeesTheirProgressTest extends MockBukkitHarness {

    @TempDir
    @SuppressWarnings("NullAway.Init")
    Path dataDir;

    private final List<Runnable> regionTasks = new ArrayList<>();
    private final List<Runnable> later = new ArrayList<>();
    private final List<Duration> waits = new ArrayList<>();
    private World world;
    private PlayerMock player;
    private Island island;
    private OneBlockConfiguration config;
    private OneBlockProgressBars bars;
    private OneBlockListener listener;
    private SchedulerPort scheduler;

    @BeforeEach
    void setUp() throws Exception {
        world = server.addSimpleWorld("skyblock");
        player = createPlayer("Breaker");
        island = Island.create(
                IslandId.of(UUID.randomUUID()),
                IslandBounds.fromCenterAndRadius(0, 0, 50),
                new PlayerUuid(player.getUniqueId()),
                new ProfileId(player.getUniqueId()),
                Instant.now());
        config = load("progress { linger = \"4s\" }");
        scheduler = mock(SchedulerPort.class);
        doAnswer(call -> regionTasks.add(call.getArgument(3, Runnable.class)))
                .when(scheduler)
                .onRegion(anyString(), anyInt(), anyInt(), any(Runnable.class));
        doAnswer(call -> {
                    waits.add(call.getArgument(0, Duration.class));
                    return later.add(call.getArgument(1, Runnable.class));
                })
                .when(scheduler)
                .asyncAfter(any(Duration.class), any(Runnable.class));
        doAnswer(call -> {
                    call.getArgument(1, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .onEntity(any(UUID.class), any(Runnable.class));
        OneBlockService service = new OneBlockService(memory(), config.phases(), new SplittableRandom(1));
        service.start(island.id(), 0, 100, 0);
        IslandProtectionListener islands =
                new IslandProtectionListener(mock(IslandStoragePort.class), new IslandAccessService());
        islands.cacheIsland(island);
        listener = new OneBlockListener(
                service,
                islands,
                scheduler,
                Messages.bundled(),
                new InteractionEffects(Map.of()),
                new InteractionEffectPlayer(null, Messages.bundled()));
        bars = new OneBlockProgressBars(() -> config, scheduler, Messages.bundled());
        listener.useBars(bars);
    }

    private static OneBlockConfiguration load(String progress) throws Exception {
        return OneBlockConfiguration.load(HoconConfigurationLoader.builder().buildAndLoadString(progress + """

                        phases = [ { name = "meadow", length = 10, blocks { DIRT = 1 } } ]
                        """));
    }

    private void breakIt() {
        Block block = world.getBlockAt(0, 100, 0);
        block.setType(Material.DIRT);
        listener.onBlockBreak(new BlockBreakEvent(block, player));
        regionTasks.clear();
    }

    private void linger() {
        List<Runnable> due = new ArrayList<>(later);
        later.clear();
        due.forEach(Runnable::run);
    }

    private List<BossBar> shown() {
        List<BossBar> shown = new ArrayList<>();
        player.activeBossBars().forEach(shown::add);
        return shown;
    }

    private static String text(Component line) {
        return PlainTextComponentSerializer.plainText().serialize(line);
    }

    @Test
    @DisplayName("A boss bar shows the phase and how far into it, and goes once the breaker stops breaking")
    void bossBar() {
        breakIt();
        breakIt();

        assertThat(shown()).singleElement().satisfies(bar -> {
            assertThat(text(bar.name())).contains("meadow").contains("2/10");
            assertThat(bar.progress()).isEqualTo(0.2f);
        });
        assertThat(waits).containsOnly(Duration.ofSeconds(4));
        List<Runnable> first = new ArrayList<>(later.subList(0, 1));
        later.remove(0);
        first.forEach(Runnable::run);
        assertThat(shown()).describedAs("a break came after the first").hasSize(1);

        linger();
        assertThat(shown()).isEmpty();
    }

    @Test
    @DisplayName("A breaker switches it to the action bar, then nowhere, then back, and the choice is kept with them")
    void switched() {
        breakIt();
        bars.switchFor(player);
        assertThat(shown()).describedAs("a switch takes the boss bar away").isEmpty();
        assertThat(text(player.nextComponentMessage())).contains("action bar");

        breakIt();
        assertThat(shown()).isEmpty();
        assertThat(text(player.nextActionBar())).contains("meadow").contains("2/10");

        bars.switchFor(player);
        breakIt();
        assertThat(player.nextActionBar()).isNull();
        assertThat(shown()).isEmpty();
        assertThat(bars.shownTo(player)).isEqualTo(OneBlockDisplay.Bar.NONE);

        bars.switchFor(player);
        assertThat(bars.shownTo(player)).isEqualTo(OneBlockDisplay.Bar.BOSSBAR);
    }

    @Test
    @DisplayName("The panel says where it shows, and its button switches it")
    void panel() {
        SkyblockMenuEngine engine = ShippedTemplates.engineWith(dataDir, "island-oneblock.conf");
        OneBlockPanel panel = new OneBlockPanel(
                new OneBlockService(memory(), config.phases(), new SplittableRandom(1)),
                mock(IslandStoragePort.class),
                scheduler,
                Messages.bundled(),
                id -> Optional.empty());
        panel.useBars(bars);
        panel.useMenuEngine(engine);
        assertThat(panel.values(player, 0)).containsEntry("bar", "on a boss bar");
        assertThat(panel.values(null, 0)).containsEntry("bar", "");

        ShippedTemplates.click(
                engine, OneBlockPanel.SWITCH_BAR, MenuContext.of(player, null, 0), player, ClickKind.LEFT, "");

        assertThat(bars.shownTo(player)).isEqualTo(OneBlockDisplay.Bar.ACTIONBAR);
        assertThat(panel.values(player, 0)).containsEntry("bar", "on the action bar");
    }

    @Test
    @DisplayName("The server's choice and how long the boss bar stays are read; what cannot be is the shipped one")
    void read() throws Exception {
        assertThat(config.display().linger()).isEqualTo(Duration.ofSeconds(4));
        OneBlockConfiguration action = load("progress { bar = \"actionbar\", line = false }");
        assertThat(action.display().bar()).isEqualTo(OneBlockDisplay.Bar.ACTIONBAR);
        assertThat(action.display().line()).isFalse();
        assertThat(load("progress { bar = \"sky\", linger = \"soon\" }").display())
                .isEqualTo(OneBlockDisplay.SHIPPED);
        assertThat(load("").display()).isEqualTo(OneBlockDisplay.SHIPPED);

        config = action;
        breakIt();
        assertThat(player.nextActionBar())
                .describedAs("the server's choice, for a player who made none")
                .isNotNull();
    }

    private OneBlockProgressPort memory() {
        Map<IslandId, OneBlockProgressPort.OneBlockIsland> stored = new java.util.HashMap<>();
        return new OneBlockProgressPort() {
            @Override
            public void start(IslandId islandId, int x, int y, int z) {
                stored.put(islandId, new OneBlockIsland(islandId, x, y, z, 0));
            }

            @Override
            public List<OneBlockIsland> findAll() {
                return List.copyOf(stored.values());
            }

            @Override
            public Optional<OneBlockIsland> find(IslandId islandId) {
                return Optional.ofNullable(stored.get(islandId));
            }

            @Override
            public void addBreaks(IslandId islandId, long breaks) {}

            @Override
            public void setBreaks(IslandId islandId, long count) {}
        };
    }
}
