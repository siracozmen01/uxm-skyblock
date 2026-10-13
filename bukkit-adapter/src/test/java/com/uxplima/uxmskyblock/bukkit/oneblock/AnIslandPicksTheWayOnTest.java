package com.uxplima.uxmskyblock.bukkit.oneblock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
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

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import com.uxplima.uxmlib.menu.runtime.MenuContext;
import com.uxplima.uxmlib.menu.spec.ClickKind;
import com.uxplima.uxmskyblock.bukkit.config.OneBlockConfiguration;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffectPlayer;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffects;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.menu.MenuRow;
import com.uxplima.uxmskyblock.bukkit.menu.ShippedTemplates;
import com.uxplima.uxmskyblock.bukkit.menu.SkyblockMenuEngine;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.island.IslandAccessService;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.oneblock.OneBlockBranchPort;
import com.uxplima.uxmskyblock.core.application.oneblock.OneBlockProgressPort;
import com.uxplima.uxmskyblock.core.application.oneblock.OneBlockRewardPort;
import com.uxplima.uxmskyblock.core.application.oneblock.OneBlockService;
import com.uxplima.uxmskyblock.core.application.oneblock.OneBlockWaitPort;
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
 * An island at the end of a OneBlock phase that branches waits there until it picks the way on, in a menu file or,
 * when the time to pick runs out, by taking the first; the pick is stored and the island goes that way.
 */
class AnIslandPicksTheWayOnTest extends MockBukkitHarness {

    @TempDir
    @SuppressWarnings("NullAway.Init")
    Path dataDir;

    /** A clock the test moves. */
    private static final class Hands extends Clock {
        Instant now = Instant.parse("2026-10-13T10:00:00Z");

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final Hands clock = new Hands();
    private final List<Runnable> regionTasks = new ArrayList<>();
    private final List<OneBlockBranchPort.Pick> kept = new ArrayList<>();
    private final Map<IslandId, OneBlockProgressPort.OneBlockIsland> stored = new java.util.HashMap<>();
    private World world;
    private PlayerMock player;
    private Island island;
    private OneBlockConfiguration config;
    private OneBlockService service;
    private OneBlockBranches branches;
    private OneBlockListener listener;

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
        config = OneBlockConfiguration.load(HoconConfigurationLoader.builder().buildAndLoadString("""
                        choose-within = "30s"
                        phases = [ {
                            name = "plains"
                            length = 2
                            next = [ "cave", "forest", "void" ]
                            blocks { GRASS_BLOCK = 1 }
                        }, {
                            name = "cave"
                            length = 2
                            next = []
                            blocks { STONE = 1 }
                        }, {
                            name = "forest"
                            length = 3
                            blocks { OAK_LOG = 1 }
                        } ]
                        """));
        service = new OneBlockService(
                memory(),
                OneBlockRewardPort.NONE,
                OneBlockWaitPort.NONE,
                branchPort(),
                config.phases(),
                new SplittableRandom(3));
        service.start(island.id(), 0, 100, 0);
        SchedulerPort scheduler = mock(SchedulerPort.class);
        doAnswer(call -> regionTasks.add(call.getArgument(3, Runnable.class)))
                .when(scheduler)
                .onRegion(anyString(), anyInt(), anyInt(), any(Runnable.class));
        doAnswer(call -> {
                    call.getArgument(0, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .async(any(Runnable.class));
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
        branches = new OneBlockBranches(() -> config, service, scheduler, Messages.bundled(), clock);
        listener.useBranches(branches);
    }

    private Block breakIt() {
        Block block = world.getBlockAt(0, 100, 0);
        listener.onBlockBreak(new BlockBreakEvent(block, player));
        block.setType(Material.AIR);
        List<Runnable> due = new ArrayList<>(regionTasks);
        regionTasks.clear();
        due.forEach(Runnable::run);
        return block;
    }

    private List<String> heard() {
        List<String> lines = new ArrayList<>();
        for (Component line = player.nextComponentMessage(); line != null; line = player.nextComponentMessage()) {
            lines.add(PlainTextComponentSerializer.plainText().serialize(line));
        }
        return lines;
    }

    private long broken() {
        return service.island(island.id()).orElseThrow().blocksBroken();
    }

    @Test
    @DisplayName("The ways on are read, a way to no phase is left out, and the time to pick is the file's")
    void read() throws Exception {
        assertThat(config.phases().optionsAfter("plains")).containsExactly("cave", "forest");
        assertThat(config.phases().next()).containsEntry("cave", List.of());
        assertThat(config.chooseWithin()).isEqualTo(Duration.ofSeconds(30));
        for (String written : List.of("", "choose-within = \"soon\"\n")) {
            OneBlockConfiguration plain = OneBlockConfiguration.load(HoconConfigurationLoader.builder()
                    .buildAndLoadString(
                            written + "phases = [ { name = \"plains\", length = 2, blocks { DIRT = 1 } } ]"));
            assertThat(plain.chooseWithin())
                    .describedAs("left out, or no time: %s", written)
                    .isEqualTo(Duration.ofSeconds(60));
        }
    }

    @Test
    @DisplayName("At the end of a phase that branches the break waits for a pick, and the ways are said")
    void waitsForAPick() {
        world.getBlockAt(0, 100, 0).setType(Material.GRASS_BLOCK);
        breakIt();
        heard();

        Block block = breakIt();

        assertThat(broken()).isEqualTo(1);
        assertThat(block.getType()).isEqualTo(Material.GRASS_BLOCK);
        assertThat(heard())
                .hasSize(3)
                .anySatisfy(line -> assertThat(line).contains("0:30"))
                .anySatisfy(line -> assertThat(line).contains("cave"))
                .anySatisfy(line -> assertThat(line).contains("forest"));
    }

    @Test
    @DisplayName("A pick in the window takes that way, stores it, and the next break goes into it")
    void picked() {
        SkyblockMenuEngine engine = ShippedTemplates.engineWith(dataDir, "oneblock-branch.conf");
        branches.useMenuEngine(engine);
        breakIt();
        List<MenuRow> rows = branches.rows(player, island.id(), "plains", List.of("cave", "forest"));
        MenuContext forest = MenuContext.of(player, null, 0).withEntry(rows.get(1));
        assertThat(ShippedTemplates.lore(
                        ShippedTemplates.renderer(engine, Messages.bundled()),
                        ShippedTemplates.template("oneblock-branch.conf", "ways"),
                        forest))
                .contains("forest")
                .contains("3 breaks")
                .doesNotContain("<entry_");
        heard();

        ShippedTemplates.click(engine, OneBlockBranches.PICK, forest, player, ClickKind.LEFT, "");

        assertThat(kept).containsExactly(new OneBlockBranchPort.Pick(island.id(), "plains", "forest"));
        assertThat(heard()).anySatisfy(line -> assertThat(line).contains("forest"));
        Block block = breakIt();
        assertThat(broken()).isEqualTo(2);
        assertThat(block.getType()).isEqualTo(Material.OAK_LOG);
        OneBlockPanel panel = new OneBlockPanel(
                service,
                mock(IslandStoragePort.class),
                mock(SchedulerPort.class),
                Messages.bundled(),
                id -> Optional.empty());
        assertThat(panel.placeholder(player, island.id().value(), "phase_id")).isEqualTo("forest");
        assertThat(panel.placeholder(player, island.id().value(), "phase_count"))
                .describedAs("the way the island picked: plains and forest")
                .isEqualTo("2");
    }

    @Test
    @DisplayName("With no pick in time the first way is taken at the next break, and that break counts")
    void taken() {
        breakIt();
        breakIt();
        clock.now = clock.now.plusSeconds(29);
        breakIt();
        assertThat(broken()).isEqualTo(1);
        heard();

        clock.now = clock.now.plusSeconds(1);
        Block block = breakIt();

        assertThat(broken()).isEqualTo(2);
        assertThat(block.getType()).isEqualTo(Material.STONE);
        assertThat(kept).containsExactly(new OneBlockBranchPort.Pick(island.id(), "plains", "cave"));
        assertThat(heard()).anySatisfy(line -> assertThat(line).contains("No way was picked"));
    }

    private OneBlockBranchPort branchPort() {
        return new OneBlockBranchPort() {
            @Override
            public List<Pick> findAll() {
                return List.copyOf(kept);
            }

            @Override
            public List<Pick> find(IslandId islandId) {
                return List.copyOf(kept);
            }

            @Override
            public void pick(Pick pick) {
                kept.add(pick);
            }

            @Override
            public void clear(IslandId islandId) {
                kept.clear();
            }
        };
    }

    private OneBlockProgressPort memory() {
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
