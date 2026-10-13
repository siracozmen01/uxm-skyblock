package com.uxplima.uxmskyblock.bukkit.acid;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Item;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.ItemStack;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

/**
 * With a sea between islands, every chunk its world makes is laid with the acid sea, all of that world's water is acid,
 * on an island or not, and the tide moves it around the players sailing it.
 */
class TheSeaRunsBetweenIslandsTest extends MockBukkitHarness {

    private static final int SURFACE = 70;
    private static final long SECOND = 1_000_000_000L;
    private static final AcidIslandConfiguration.Between OPEN =
            new AcidIslandConfiguration.Between(true, "acid", SURFACE);
    private static final AcidIslandConfiguration.Sea SEA = new AcidIslandConfiguration.Sea(
            2,
            4,
            20,
            "SANDSTONE",
            AcidIslandConfiguration.Vents.NONE,
            0,
            new AcidIslandConfiguration.Bed(List.of("TUFF"), 2, 0));

    private final Map<IslandId, Integer> stored = new HashMap<>();
    private final AtomicLong nanos = new AtomicLong();

    @SuppressWarnings("NullAway.Init")
    private World acid;

    @SuppressWarnings("NullAway.Init")
    private World other;

    @SuppressWarnings("NullAway.Init")
    private IslandProtectionListener islands;

    @SuppressWarnings("NullAway.Init")
    private AcidIslandService service;

    @SuppressWarnings("NullAway.Init")
    private SchedulerPort scheduler;

    @SuppressWarnings("NullAway.Init")
    private PlayerMock player;

    @BeforeEach
    void setUpTheWorlds() {
        acid = server.addSimpleWorld("acid");
        other = server.addSimpleWorld("other");
        islands = new IslandProtectionListener(mock(IslandStoragePort.class), new IslandAccessService());
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
        scheduler = mock(SchedulerPort.class);
        doAnswer(call -> {
                    call.getArgument(3, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .onRegion(anyString(), anyInt(), anyInt(), any(Runnable.class));
        player = createPlayer("Sailor");
        player.setGameMode(GameMode.SURVIVAL);
    }

    @Test
    @DisplayName("A chunk the sea's world makes is laid with the sea, wall to wall, and no other chunk is")
    void eachNewChunkIsSea() {
        AcidOpenSea open = new AcidOpenSea(new AcidSeaStart(service, scheduler, SEA), OPEN);
        acid.getBlockAt(5, SURFACE, 5).setType(Material.STONE);

        open.onChunkLoad(new ChunkLoadEvent(acid.getChunkAt(0, 0), true));

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                if (x == 5 && z == 5) {
                    continue;
                }
                assertThat(acid.getBlockAt(x, SURFACE, z).getType()).isEqualTo(Material.WATER);
                assertThat(acid.getBlockAt(x, SURFACE - 3, z).getType()).isEqualTo(Material.WATER);
                assertThat(acid.getBlockAt(x, SURFACE - 4, z).getType()).isEqualTo(Material.TUFF);
                assertThat(acid.getBlockAt(x, SURFACE - 5, z).getType()).isEqualTo(Material.TUFF);
                assertThat(acid.getBlockAt(x, SURFACE - 6, z).getType()).isEqualTo(Material.AIR);
                assertThat(acid.getBlockAt(x, SURFACE + 1, z).getType()).isEqualTo(Material.AIR);
            }
        }
        assertThat(acid.getBlockAt(5, SURFACE, 5).getType())
                .describedAs("what stands is kept")
                .isEqualTo(Material.STONE);

        open.onChunkLoad(new ChunkLoadEvent(acid.getChunkAt(1, 0), false));
        assertThat(acid.getBlockAt(16, SURFACE, 0).getType())
                .describedAs("a chunk made before")
                .isEqualTo(Material.AIR);
        open.onChunkLoad(new ChunkLoadEvent(other.getChunkAt(0, 0), true));
        assertThat(other.getBlockAt(0, SURFACE, 0).getType())
                .describedAs("another world")
                .isEqualTo(Material.AIR);
        new AcidOpenSea(new AcidSeaStart(service, scheduler, SEA), AcidIslandConfiguration.Between.SHIPPED)
                .onChunkLoad(new ChunkLoadEvent(acid.getChunkAt(2, 0), true));
        assertThat(acid.getBlockAt(32, SURFACE, 0).getType())
                .describedAs("while it is off")
                .isEqualTo(Material.AIR);
    }

    @Test
    @DisplayName("All of the sea's world's water burns and dissolves, on no island at all; another world's does not")
    void theWholeWorldIsAcid() {
        AcidHazard open = hazard(OPEN);
        AcidHazard shut = hazard(AcidIslandConfiguration.Between.SHIPPED);
        swimIn(acid);

        assertThat(open.check(player)).isEqualTo(2.0);
        assertThat(open.onAcidIsland(new Location(acid, 0, SURFACE, 0))).isTrue();
        assertThat(shut.check(player))
                .describedAs("with no sea between islands")
                .isZero();
        swimIn(other);
        assertThat(open.check(player)).isZero();

        AcidSeaLife life = life(OPEN);
        acid.getBlockAt(3, SURFACE, 3).setType(Material.WATER);
        Item item = acid.dropItem(new Location(acid, 3.5, SURFACE + 0.2, 3.5), new ItemStack(Material.DIRT));
        player.teleport(new Location(acid, 3.5, SURFACE + 3, 3.5));
        life.around(player);
        nanos.addAndGet(11 * SECOND);
        life.around(player);
        assertThat(item.isValid()).isFalse();
    }

    @Test
    @DisplayName("Fumes rise from a vent of the open sea, read at that sea's surface")
    void ventsOfTheOpenSea() {
        AcidIslandConfiguration.Sea vented = new AcidIslandConfiguration.Sea(
                2, 4, 20, "SANDSTONE", new AcidIslandConfiguration.Vents(0.01, 3, List.of("nausea:0:6")), 0);
        AcidHazard open = new AcidHazard(
                service,
                islands,
                scheduler,
                config(vented, OPEN, AcidIslandConfiguration.Tide.STILL),
                InteractionEffects.none(),
                new InteractionEffectPlayer());
        acid.getBlockAt(2, SURFACE - 4, 0).setType(AcidSeaStart.VENT);
        swimIn(acid);

        open.check(player);

        assertThat(player.getPotionEffect(org.bukkit.potion.PotionEffectType.NAUSEA))
                .isNotNull();
    }

    @Test
    @DisplayName("The tide moves the open sea around a player sailing it, and a chunk as it loads")
    void theTideMovesTheOpenSea() {
        AtomicLong millis = new AtomicLong();
        doAnswer(call -> {
                    call.getArgument(1, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .onEntity(any(PlayerUuid.class), any(Runnable.class));
        AcidTideRunner tide = new AcidTideRunner(
                service,
                islands,
                scheduler,
                config(
                        SEA,
                        OPEN,
                        new AcidIslandConfiguration.Tide(
                                new AcidTide(1, Duration.ofMinutes(20)), Duration.ofSeconds(10))),
                InteractionEffects.none(),
                new InteractionEffectPlayer(),
                Messages.bundled(),
                millis::get,
                (world, chunkX, chunkZ) -> true);
        for (int x = -40; x <= 40; x += 8) {
            acid.getBlockAt(x, SURFACE, 0).setType(Material.WATER);
            acid.getBlockAt(x, SURFACE - 1, 0).setType(Material.WATER);
            other.getBlockAt(x, SURFACE, 0).setType(Material.WATER);
            other.getBlockAt(x, SURFACE - 1, 0).setType(Material.WATER);
        }
        player.teleport(new Location(acid, 0.5, SURFACE + 2, 0.5));

        tide.round();
        millis.set(10 * 60_000L);
        tide.round();

        assertThat(acid.getBlockAt(0, SURFACE + 1, 0).getType()).isEqualTo(Material.WATER);
        assertThat(acid.getBlockAt(32, SURFACE + 1, 0).getType())
                .describedAs("as far as a sea reaches")
                .isEqualTo(Material.WATER);
        assertThat(acid.getBlockAt(-40, SURFACE + 1, 0).getType())
                .describedAs("past it")
                .isEqualTo(Material.AIR);

        acid.getBlockAt(8, SURFACE + 1, 0).setType(Material.AIR);
        tide.round();
        assertThat(acid.getBlockAt(8, SURFACE + 1, 0).getType())
                .describedAs("the same level is not laid again")
                .isEqualTo(Material.AIR);
        acid.getBlockAt(8, SURFACE + 1, 0).setType(Material.WATER);

        acid.getBlockAt(-40, SURFACE + 1, 0).setType(Material.AIR);
        tide.onChunkLoad(new ChunkLoadEvent(acid.getChunkAt(-3, 0), false));
        assertThat(acid.getBlockAt(-40, SURFACE + 1, 0).getType())
                .describedAs("as it loads")
                .isEqualTo(Material.WATER);

        // Sailing on, the chunks brought are remembered for this level only, and forgotten as it moves on.
        // Twenty-five chunks around the player, and the one that loaded.
        int around = tide.rememberedOpen() - 1;
        for (int x = 1000; x <= 9000; x += 1000) {
            player.teleport(new Location(acid, x + 0.5, SURFACE + 2, 0.5));
            tide.round();
        }
        assertThat(tide.rememberedOpen()).isEqualTo(around * 10 + 1);
        assertThat(around).isEqualTo(25);
        millis.set(0);
        tide.round();
        assertThat(tide.rememberedOpen()).describedAs("a new level").isEqualTo(around);

        player.teleport(new Location(other, 0.5, SURFACE + 2, 0.5));
        tide.round();
        tide.onChunkLoad(new ChunkLoadEvent(other.getChunkAt(0, 0), false));
        assertThat(other.getBlockAt(0, SURFACE + 1, 0).getType())
                .describedAs("another world")
                .isEqualTo(Material.AIR);
    }

    @Test
    @DisplayName("The sea between islands is read from the file, off as shipped, and one with no world is off")
    void read() throws Exception {
        assertThat(load("sea { between { enabled = true, world = \" acid \", surface = 64 } }"))
                .isEqualTo(new AcidIslandConfiguration.Between(true, "acid", 64));
        assertThat(load("enabled = true")).isEqualTo(AcidIslandConfiguration.Between.SHIPPED);
        assertThat(AcidIslandConfiguration.Between.SHIPPED.enabled()).isFalse();
        assertThat(load("sea { between { enabled = true } }")).isEqualTo(AcidIslandConfiguration.Between.SHIPPED);
        assertThat(OPEN.runsThrough("acid")).isTrue();
        assertThat(OPEN.runsThrough("other")).isFalse();
        assertThat(new AcidIslandConfiguration.Between(false, "acid", 64).runsThrough("acid"))
                .isFalse();
    }

    private static AcidIslandConfiguration.Between load(String written) throws Exception {
        return AcidIslandConfiguration.load(HoconConfigurationLoader.builder().buildAndLoadString(written))
                .between();
    }

    private void swimIn(World world) {
        world.getBlockAt(0, SURFACE, 0).setType(Material.WATER);
        world.getBlockAt(0, SURFACE + 1, 0).setType(Material.WATER);
        player.teleport(new Location(world, 0.5, SURFACE, 0.5));
    }

    private AcidHazard hazard(AcidIslandConfiguration.Between between) {
        return new AcidHazard(
                service,
                islands,
                scheduler,
                config(SEA, between, AcidIslandConfiguration.Tide.STILL),
                InteractionEffects.none(),
                new InteractionEffectPlayer());
    }

    private AcidSeaLife life(AcidIslandConfiguration.Between between) {
        return new AcidSeaLife(
                service,
                islands,
                config(SEA, between, AcidIslandConfiguration.Tide.STILL),
                InteractionEffects.none(),
                new InteractionEffectPlayer(),
                nanos::get,
                entity -> true,
                Messages.bundled(),
                (table, at, looter) -> List.of(),
                item -> null);
    }

    private static AcidIslandConfiguration config(
            AcidIslandConfiguration.Sea sea,
            AcidIslandConfiguration.Between between,
            AcidIslandConfiguration.Tide tide) {
        return new AcidIslandConfiguration(
                true,
                sea,
                new AcidRules(2.0, 1.0, true, Duration.ofSeconds(1)),
                List.of(),
                List.of(),
                new AcidIslandConfiguration.Purification(true, true),
                AcidIslandConfiguration.SeaLife.SHIPPED,
                AcidIslandConfiguration.Armour.NONE,
                AcidIslandConfiguration.CleanWater.NONE,
                AcidIslandConfiguration.Look.SHIPPED,
                AcidIslandConfiguration.Buried.NONE,
                tide,
                AcidIslandConfiguration.Offerings.NONE,
                between);
    }
}
