package com.uxplima.uxmskyblock.bukkit.acid;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.event.world.ChunkLoadEvent;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import com.uxplima.uxmlib.condition.action.ActionList;
import com.uxplima.uxmskyblock.bukkit.config.AcidIslandConfiguration;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffectPlayer;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffects;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.acid.AcidIslandService;
import com.uxplima.uxmskyblock.core.application.acid.AcidIslandsPort;
import com.uxplima.uxmskyblock.core.application.island.IslandAccessService;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.hazard.AcidRules;
import com.uxplima.uxmskyblock.core.domain.hazard.AcidTide;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

/**
 * The acid tide raises the sea of an AcidIsland island a player is on and lowers it again, over the sea alone, chunk
 * by chunk while each is loaded, and the players on it hear it move and are told as it turns.
 */
class TheAcidTideMovesTheSeaTest extends MockBukkitHarness {

    private static final int Y = 100;
    private static final long MINUTE = 60_000L;
    private static final int RADIUS = 20;

    private final Map<IslandId, Integer> stored = new HashMap<>();
    private final AtomicLong now = new AtomicLong();
    private final List<String> fired = new ArrayList<>();
    private final Set<String> unloaded = new HashSet<>();

    @SuppressWarnings("NullAway.Init")
    private World world;

    @SuppressWarnings("NullAway.Init")
    private IslandProtectionListener islands;

    @SuppressWarnings("NullAway.Init")
    private AcidIslandService service;

    @SuppressWarnings("NullAway.Init")
    private SchedulerPort scheduler;

    @SuppressWarnings("NullAway.Init")
    private PlayerMock player;

    @SuppressWarnings("NullAway.Init")
    private AcidTideRunner tide;

    @BeforeEach
    void setUpTheSea() {
        world = server.addSimpleWorld("skyblock");
        Island acid = island(0);
        islands = new IslandProtectionListener(mock(IslandStoragePort.class), new IslandAccessService());
        islands.cacheIsland(acid, "skyblock");
        islands.cacheIsland(island(1000), "skyblock");
        service = new AcidIslandService(new AcidIslandsPort() {
            @Override
            public Map<IslandId, Integer> findAll() {
                return Map.copyOf(stored);
            }

            @Override
            public java.util.OptionalInt find(IslandId islandId) {
                Integer level = stored.get(islandId);
                return level == null ? java.util.OptionalInt.empty() : java.util.OptionalInt.of(level);
            }

            @Override
            public void add(IslandId islandId, int seaLevel) {
                stored.putIfAbsent(islandId, seaLevel);
            }
        });
        service.add(acid.id(), Y);
        for (int x = 8 - RADIUS; x <= 8 + RADIUS; x++) {
            for (int z = 8 - RADIUS; z <= 8 + RADIUS; z++) {
                world.getBlockAt(x, Y - 1, z).setType(Material.WATER);
                world.getBlockAt(x, Y, z).setType(Material.WATER);
            }
        }
        // The island stands in the sea, and a pond on it holds water at the sea's surface.
        world.getBlockAt(8, Y, 8).setType(Material.STONE);
        world.getBlockAt(9, Y - 1, 8).setType(Material.STONE);
        world.getBlockAt(10, Y, 8).setType(Material.BUBBLE_COLUMN);
        // Sea further out than the radius is no sea of this island.
        world.getBlockAt(8 + RADIUS + 2, Y, 8).setType(Material.WATER);
        world.getBlockAt(8 + RADIUS + 2, Y - 1, 8).setType(Material.WATER);
        scheduler = mock(SchedulerPort.class);
        doAnswer(call -> {
                    call.getArgument(3, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .onRegion(anyString(), anyInt(), anyInt(), any(Runnable.class));
        doAnswer(call -> {
                    call.getArgument(1, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .onEntity(any(PlayerUuid.class), any(Runnable.class));
        server.getCommandMap().register("probe", new org.bukkit.command.Command("probe") {
            @Override
            public boolean execute(org.bukkit.command.CommandSender sender, String label, String[] args) {
                fired.add(String.join(" ", args));
                return true;
            }
        });
        tide = runner(
                new AcidIslandConfiguration.Tide(new AcidTide(2, Duration.ofMinutes(20)), Duration.ofSeconds(10)));
        player = createPlayer("Swimmer");
        player.teleport(new Location(world, 8.5, Y + 1, 8.5));
    }

    @Test
    @DisplayName("The tide raises water over the sea alone, a block at a time, and takes it away as it falls")
    void theTideRisesAndFalls() {
        tide.round();
        assertThat(world.getBlockAt(0, Y + 1, 0).getType())
                .describedAs("low tide")
                .isEqualTo(Material.AIR);
        assertThat(fired).describedAs("the first look moves nothing").isEmpty();

        world.getBlockAt(2, Y + 1, 0).setType(Material.GLASS);
        now.set(10 * MINUTE);
        tide.round();

        assertThat(world.getBlockAt(2, Y + 1, 0).getType())
                .describedAs("a block in the way is kept")
                .isEqualTo(Material.GLASS);
        assertThat(world.getBlockAt(0, Y + 1, 0).getType()).isEqualTo(Material.WATER);
        assertThat(world.getBlockAt(0, Y + 2, 0).getType()).isEqualTo(Material.WATER);
        assertThat(world.getBlockAt(0, Y + 3, 0).getType())
                .describedAs("no higher than the rise")
                .isEqualTo(Material.AIR);
        assertThat(world.getBlockAt(10, Y + 1, 0 + 8).getType())
                .describedAs("over a vent")
                .isEqualTo(Material.WATER);
        assertThat(world.getBlockAt(8, Y + 1, 8).getType())
                .describedAs("not on the island")
                .isEqualTo(Material.AIR);
        assertThat(world.getBlockAt(9, Y + 1, 8).getType())
                .describedAs("not over a pond")
                .isEqualTo(Material.AIR);
        assertThat(world.getBlockAt(8 + RADIUS + 2, Y + 1, 8).getType())
                .describedAs("not past the sea")
                .isEqualTo(Material.AIR);
        assertThat(world.getBlockAt(8 + RADIUS, Y + 1, 8 - RADIUS).getType())
                .describedAs("the sea's corner")
                .isEqualTo(Material.WATER);
        assertThat(fired).containsExactly("rising 2");
        assertThat(said()).containsExactly("TIDE ▶ The acid tide is rising. Keep to high ground.");

        // The same level is not laid again: a block taken out stays out until the tide moves.
        world.getBlockAt(0, Y + 2, 0).setType(Material.STONE);
        world.getBlockAt(1, Y + 2, 0).setType(Material.AIR);
        tide.round();
        assertThat(world.getBlockAt(1, Y + 2, 0).getType()).isEqualTo(Material.AIR);

        now.set(15 * MINUTE);
        tide.round();
        assertThat(world.getBlockAt(0, Y + 1, 0).getType()).isEqualTo(Material.WATER);
        assertThat(world.getBlockAt(1, Y + 2, 0).getType()).isEqualTo(Material.AIR);
        assertThat(world.getBlockAt(0, Y + 2, 0).getType())
                .describedAs("what a player built is kept")
                .isEqualTo(Material.STONE);
        assertThat(fired).last().isEqualTo("falling 1");
        assertThat(said()).containsExactly("TIDE ▶ The acid tide is falling.");

        now.set(19 * MINUTE);
        tide.round();
        assertThat(world.getBlockAt(0, Y + 1, 0).getType()).isEqualTo(Material.AIR);
        assertThat(fired).last().isEqualTo("falling 0");
        assertThat(said()).describedAs("told only as it turns").isEmpty();
    }

    @Test
    @DisplayName("A chunk not loaded is left, and brought to the tide as it loads; another mode's sea never moves")
    void chunksAsTheyLoad() {
        unloaded.add("1,0");
        now.set(10 * MINUTE);
        tide.round();
        assertThat(world.getBlockAt(16, Y + 1, 0).getType()).isEqualTo(Material.AIR);
        assertThat(world.getBlockAt(15, Y + 1, 0).getType()).isEqualTo(Material.WATER);

        tide.onChunkLoad(new ChunkLoadEvent(world.getChunkAt(1, 0), false));
        assertThat(world.getBlockAt(16, Y + 1, 0).getType()).isEqualTo(Material.WATER);

        world.getBlockAt(1008, Y, 8).setType(Material.WATER);
        world.getBlockAt(1008, Y - 1, 8).setType(Material.WATER);
        tide.onChunkLoad(new ChunkLoadEvent(world.getChunkAt(63, 0), false));
        player.teleport(new Location(world, 1008.5, Y + 1, 8.5));
        now.set(0);
        tide.round();
        fired.clear();
        now.set(10 * MINUTE);
        tide.round();
        assertThat(world.getBlockAt(1008, Y + 1, 8).getType()).isEqualTo(Material.AIR);
        assertThat(fired)
                .describedAs("nobody on another mode's island hears the tide")
                .isEmpty();

        runner(AcidIslandConfiguration.Tide.STILL).onChunkLoad(new ChunkLoadEvent(world.getChunkAt(1, 0), false));
        now.set(0);
        tide.onChunkLoad(new ChunkLoadEvent(world.getChunkAt(1, 0), false));
        assertThat(world.getBlockAt(16, Y + 1, 0).getType())
                .describedAs("low tide as it loads")
                .isEqualTo(Material.AIR);
    }

    @Test
    @DisplayName("A tide that moves beats as often as written, and a still one does not beat at all")
    void theBeat() throws Exception {
        AutoCloseable stop = () -> {};
        when(scheduler.repeatGlobal(any(Runnable.class), any(Duration.class), any(Duration.class)))
                .thenReturn(stop);

        assertThat(tide.start()).isSameAs(stop);
        verify(scheduler)
                .repeatGlobal(
                        any(Runnable.class),
                        org.mockito.ArgumentMatchers.eq(Duration.ofSeconds(10)),
                        org.mockito.ArgumentMatchers.eq(Duration.ofSeconds(10)));

        SchedulerPort quiet = mock(SchedulerPort.class);
        new AcidTideRunner(
                        service,
                        islands,
                        quiet,
                        config(AcidIslandConfiguration.Tide.STILL),
                        InteractionEffects.none(),
                        new InteractionEffectPlayer(),
                        Messages.bundled(),
                        now::get,
                        (in, chunkX, chunkZ) -> true)
                .start()
                .close();
        verify(quiet, never()).repeatGlobal(any(Runnable.class), any(Duration.class), any(Duration.class));
    }

    @Test
    @DisplayName("The tide is read from the file, and one that cannot be is the shipped one")
    void theTideIsRead() throws Exception {
        assertThat(load("sea { tide { rise = 3, period = \"30m\", check-every = \"5s\" } }"))
                .isEqualTo(new AcidIslandConfiguration.Tide(
                        new AcidTide(3, Duration.ofMinutes(30)), Duration.ofSeconds(5)));
        assertThat(load("enabled = true")).isEqualTo(AcidIslandConfiguration.Tide.SHIPPED);
        assertThat(load("sea { tide { rise = 17 } }")).isEqualTo(AcidIslandConfiguration.Tide.SHIPPED);
        assertThat(load("sea { tide { check-every = \"500ms\" } }")).isEqualTo(AcidIslandConfiguration.Tide.SHIPPED);
        assertThat(load("sea { tide { rise = 0 } }").tide().moves()).isFalse();
    }

    private static AcidIslandConfiguration.Tide load(String written) throws Exception {
        return AcidIslandConfiguration.load(HoconConfigurationLoader.builder().buildAndLoadString(written))
                .tide();
    }

    private List<String> said() {
        List<String> lines = new ArrayList<>();
        for (var next = player.nextComponentMessage(); next != null; next = player.nextComponentMessage()) {
            lines.add(PlainTextComponentSerializer.plainText().serialize(next));
        }
        return lines;
    }

    private AcidTideRunner runner(AcidIslandConfiguration.Tide written) {
        return new AcidTideRunner(
                service,
                islands,
                scheduler,
                config(written),
                new InteractionEffects(Map.of(
                        "acid-tide-rising", ActionList.parse(List.of("[console] probe rising %level%")),
                        "acid-tide-falling", ActionList.parse(List.of("[console] probe falling %level%")))),
                new InteractionEffectPlayer(),
                Messages.bundled(),
                now::get,
                (in, chunkX, chunkZ) -> !unloaded.contains(chunkX + "," + chunkZ));
    }

    private static AcidIslandConfiguration config(AcidIslandConfiguration.Tide written) {
        return new AcidIslandConfiguration(
                true,
                new AcidIslandConfiguration.Sea(2, 4, RADIUS, "SANDSTONE"),
                new AcidRules(2.0, 1.0, true, Duration.ofSeconds(1)),
                List.of(),
                List.of(),
                new AcidIslandConfiguration.Purification(true, true),
                AcidIslandConfiguration.SeaLife.NONE,
                AcidIslandConfiguration.Armour.NONE,
                AcidIslandConfiguration.CleanWater.NONE,
                AcidIslandConfiguration.Look.SHIPPED,
                AcidIslandConfiguration.Buried.NONE,
                written,
                AcidIslandConfiguration.Offerings.NONE,
                AcidIslandConfiguration.Between.SHIPPED);
    }

    private static Island island(int centreX) {
        return Island.create(
                IslandId.of(UUID.randomUUID()),
                IslandBounds.fromCenterAndRadius(centreX + 8, 8, 40),
                new PlayerUuid(UUID.randomUUID()),
                new ProfileId(UUID.randomUUID()),
                Instant.now());
    }
}
