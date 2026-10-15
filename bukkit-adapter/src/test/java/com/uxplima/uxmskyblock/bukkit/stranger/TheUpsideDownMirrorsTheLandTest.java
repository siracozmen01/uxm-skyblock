package com.uxplima.uxmskyblock.bukkit.stranger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Axis;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.Orientable;

import com.uxplima.uxmskyblock.bukkit.config.PresetConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.StrangerRealmsConfiguration;
import com.uxplima.uxmskyblock.bukkit.schematic.IslandStart;
import com.uxplima.uxmskyblock.bukkit.schematic.StarterSchematicEngine;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.stranger.StrangerRealmsPort;
import com.uxplima.uxmskyblock.core.application.stranger.StrangerRealmsService;
import com.uxplima.uxmskyblock.core.domain.dimension.DimensionId;
import com.uxplima.uxmskyblock.core.domain.gamemode.GameModeType;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.preset.StartTemplate;
import com.uxplima.uxmskyblock.core.domain.preset.StarterPreset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

/**
 * A StrangerRealms island's land is mirrored into the Upside Down when the island is made: block for
 * block, distressed by the operator's palette, over what the Nether had there, and only as far as the
 * radius reaches. Each chunk is read on its own region in the island's world and written on its own
 * region in the Upside Down.
 */
class TheUpsideDownMirrorsTheLandTest extends MockBukkitHarness {

    /** Every chunk is there at once, as MockBukkit has it: the mirror's own work is what these tests read. */
    private static final UpsideDownStart.ChunkLoads LOADED =
            (world, chunkX, chunkZ) -> java.util.concurrent.CompletableFuture.completedFuture(null);

    private static final int CENTER = 8;
    private static final int LOW = 60;
    private static final int HIGH = 72;
    private static final String UPSIDE_DOWN = "skyblock_nether";

    private final Set<IslandId> recorded = new HashSet<>();
    private final List<String> regions = new ArrayList<>();

    @SuppressWarnings("NullAway.Init")
    private World land;

    @SuppressWarnings("NullAway.Init")
    private World upsideDown;

    @SuppressWarnings("NullAway.Init")
    private StrangerRealmsService service;

    @SuppressWarnings("NullAway.Init")
    private SchedulerPort scheduler;

    @BeforeEach
    void setUpWorlds() {
        World realms = server.addSimpleWorld("realms");
        land = spy(realms);
        when(land.getMinHeight()).thenReturn(LOW);
        when(land.getMaxHeight()).thenReturn(HIGH);
        upsideDown = server.addSimpleWorld(UPSIDE_DOWN);
        service = new StrangerRealmsService(new StrangerRealmsPort() {
            @Override
            public Set<IslandId> findAll() {
                return Set.copyOf(recorded);
            }

            @Override
            public boolean exists(IslandId islandId) {
                return recorded.contains(islandId);
            }

            @Override
            public void add(IslandId islandId) {
                recorded.add(islandId);
            }

            @Override
            public int farthestReach() {
                return 0;
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
                    regions.add(call.getArgument(0) + ":" + call.getArgument(1) + "," + call.getArgument(2));
                    call.getArgument(3, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .onRegion(anyString(), anyInt(), anyInt(), any(Runnable.class));
    }

    @Test
    @DisplayName("The land is mirrored block for block and distressed, over the Nether that was there")
    void theLandIsMirrored() throws Exception {
        for (int x = -16; x < 48; x++) {
            for (int y = LOW; y < HIGH; y++) {
                upsideDown.getBlockAt(x, y, CENTER).setType(Material.NETHERRACK);
            }
        }
        land.getBlockAt(CENTER, 63, CENTER).setType(Material.STONE);
        land.getBlockAt(CENTER, 64, CENTER).setType(Material.GRASS_BLOCK);
        land.getBlockAt(CENTER + 1, 65, CENTER).setBlockData(axis(Material.OAK_LOG, Axis.X));
        land.getBlockAt(CENTER + 1, 66, CENTER).setType(Material.OAK_LEAVES);
        land.getBlockAt(CENTER + 2, 64, CENTER).setType(Material.WATER);
        IslandId island = IslandId.of(UUID.randomUUID());

        start(16, List.of("GRASS_BLOCK:MYCELIUM", "*_LEAVES:AIR", "*_LOG:STRIPPED_DARK_OAK_LOG", "BAD LINE"))
                .apply(new IslandStart(land, island, CENTER, 64, CENTER, preset()));

        assertThat(upsideDown.getBlockAt(CENTER, 64, CENTER).getType()).isEqualTo(Material.MYCELIUM);
        assertThat(upsideDown.getBlockAt(CENTER, 63, CENTER).getType())
                .describedAs("a block no rule matches is mirrored as it is")
                .isEqualTo(Material.STONE);
        assertThat(upsideDown.getBlockAt(CENTER + 1, 65, CENTER).getBlockData())
                .isInstanceOfSatisfying(Orientable.class, log -> {
                    assertThat(log.getMaterial()).isEqualTo(Material.STRIPPED_DARK_OAK_LOG);
                    assertThat(log.getAxis()).isEqualTo(Axis.X);
                });
        assertThat(upsideDown.getBlockAt(CENTER + 1, 66, CENTER).getType()).isEqualTo(Material.AIR);
        assertThat(upsideDown.getBlockAt(CENTER + 2, 64, CENTER).getType()).isEqualTo(Material.WATER);
        assertThat(upsideDown.getBlockAt(CENTER, 70, CENTER).getType())
                .describedAs("the Nether's rock where the land has air is cleared")
                .isEqualTo(Material.AIR);
        assertThat(upsideDown.getBlockAt(CENTER + 20, 70, CENTER).getType())
                .describedAs("the next chunk out, within the radius, is mirrored too")
                .isEqualTo(Material.AIR);
        assertThat(upsideDown.getBlockAt(CENTER + 36, 70, CENTER).getType())
                .describedAs("past the radius the Nether is left alone")
                .isEqualTo(Material.NETHERRACK);
        assertThat(service.isStranger(island)).isTrue();
        assertThat(regions)
                .describedAs("eight chunks read on their own regions, and all nine written on theirs")
                .hasSize(17)
                .doesNotContain("realms:0,0")
                .contains(UPSIDE_DOWN + ":0,0", "realms:1,1", UPSIDE_DOWN + ":-1,-1");
    }

    @Test
    @DisplayName("In the Upside Down itself the action lays nothing, and with no Upside Down loaded it only records")
    void nothingIsLaidTwice() throws Exception {
        upsideDown.getBlockAt(CENTER, 70, CENTER).setType(Material.NETHERRACK);
        IslandId island = IslandId.of(UUID.randomUUID());

        start(16, List.of()).apply(new IslandStart(upsideDown, island, CENTER, 64, CENTER, preset()));

        assertThat(upsideDown.getBlockAt(CENTER, 70, CENTER).getType()).isEqualTo(Material.NETHERRACK);
        assertThat(recorded).isEmpty();
        assertThat(regions).isEmpty();

        new UpsideDownStart(
                        service,
                        scheduler,
                        new StrangerRealmsConfiguration.UpsideDown(16, List.of()),
                        () -> "no_such_world",
                        LOADED)
                .apply(new IslandStart(land, island, CENTER, 64, CENTER, preset()));
        assertThat(recorded).containsExactly(island);
        assertThat(regions).isEmpty();
    }

    @Test
    @DisplayName("The Upside Down says it writes in the Nether, to the edge of the last chunk it mirrors")
    void itSaysWhereItWrites() throws Exception {
        IslandId island = IslandId.of(UUID.randomUUID());

        assertThat(start(16, List.of()).reach(new IslandStart(land, island, CENTER, 64, CENTER, preset())))
                .containsExactly(new com.uxplima.uxmskyblock.core.application.gamemode.CreationReach(UPSIDE_DOWN, 31));
        assertThat(start(16, List.of()).reach(new IslandStart(upsideDown, island, CENTER, 64, CENTER, preset())))
                .describedAs("laid in the Upside Down itself, it writes nothing past what the Nether lays")
                .isEmpty();
    }

    @Test
    @DisplayName("A palette rule that names no block leaves the block as it was")
    void anUnknownBlockIsKept() throws Exception {
        land.getBlockAt(CENTER, 64, CENTER).setType(Material.GRASS_BLOCK);

        start(0, List.of("GRASS_BLOCK:NOT_A_BLOCK"))
                .apply(new IslandStart(land, IslandId.of(UUID.randomUUID()), CENTER, 64, CENTER, preset()));

        assertThat(upsideDown.getBlockAt(CENTER, 64, CENTER).getType()).isEqualTo(Material.GRASS_BLOCK);
        assertThat(regions).containsExactly(UPSIDE_DOWN + ":0,0");
    }

    @Test
    @DisplayName("The shipped preset plays StrangerRealms on land of its own, with the Upside Down as its Nether")
    void thePreset() throws Exception {
        StarterPreset preset = shipped().catalogue().findById("stranger_realms").orElseThrow();

        assertThat(preset.mode()).isEqualTo(GameModeType.STRANGER_REALMS);
        assertThat(preset.world()).isEqualTo("realms");
        assertThat(preset.start()).containsExactly(UpsideDownStart.ACTION);
        assertThat(preset.dimensions().resolve(DimensionId.THE_NETHER))
                .get()
                .extracting(StartTemplate::actions)
                .isEqualTo(List.of(UpsideDownStart.ACTION));
        assertThat(shipped()
                        .startableWith(new StarterSchematicEngine().actions()::knowsAll)
                        .catalogue()
                        .findById("stranger_realms"))
                .describedAs("not offered where StrangerRealms is off")
                .isEmpty();
    }

    @Test
    @DisplayName("The shipped file reads as the shipped numbers, and a radius below zero falls back")
    void theShippedFile() throws Exception {
        StrangerRealmsConfiguration file = StrangerRealmsConfiguration.load(
                HoconConfigurationLoader.builder().buildAndLoadString(resource("modules/strangerrealms.conf")));
        StrangerRealmsConfiguration odd = StrangerRealmsConfiguration.load(
                HoconConfigurationLoader.builder().buildAndLoadString("enabled = false\nupside-down { radius = -1 }"));

        assertThat(file).isEqualTo(StrangerRealmsConfiguration.defaultConfiguration());
        assertThat(odd.enabled()).isFalse();
        assertThat(odd.upsideDown()).isEqualTo(StrangerRealmsConfiguration.UpsideDown.SHIPPED);
    }

    @Test
    @DisplayName("What a late mirror copies is the operator's list, the plain ground as shipped")
    void theLateCopyIsTheOperators() throws Exception {
        StrangerRealmsConfiguration narrow = StrangerRealmsConfiguration.load(HoconConfigurationLoader.builder()
                .buildAndLoadString("upside-down { late-copy = [\"STONE\", \"*_LOG\"] }"));

        assertThat(narrow.upsideDown().lateCopy()).containsExactly("STONE", "*_LOG");
        assertThat(StrangerRealmsConfiguration.UpsideDown.SHIPPED.lateCopy())
                .contains("STONE", "DIRT", "WATER")
                .doesNotContain("DIAMOND_ORE", "CHEST", "DIAMOND_BLOCK");
    }

    @Test
    @DisplayName("A chunk not loaded is loaded before it is read, and nothing is read or laid until it is")
    void aChunkIsLoadedBeforeItIsRead() {
        land.getBlockAt(CENTER, 64, CENTER).setType(Material.GRASS_BLOCK);
        upsideDown.getBlockAt(CENTER, 64, CENTER).setType(Material.NETHERRACK);
        java.util.concurrent.CompletableFuture<@org.jspecify.annotations.Nullable Void> landLoaded =
                new java.util.concurrent.CompletableFuture<>();
        List<String> asked = new ArrayList<>();
        UpsideDownStart mirror = new UpsideDownStart(
                service,
                scheduler,
                new StrangerRealmsConfiguration.UpsideDown(16, List.of()),
                () -> UPSIDE_DOWN,
                (world, chunkX, chunkZ) -> {
                    asked.add(world.getName() + ":" + chunkX + "," + chunkZ);
                    return world.getName().equals(UPSIDE_DOWN)
                            ? java.util.concurrent.CompletableFuture.completedFuture(null)
                            : landLoaded;
                });
        int chunk = CENTER >> 4;

        mirror.mirror(land, upsideDown, chunk, chunk);

        assertThat(upsideDown.getBlockAt(CENTER, 64, CENTER).getType())
                .describedAs("the land's chunk is still loading")
                .isEqualTo(Material.NETHERRACK);
        assertThat(asked).containsExactly(land.getName() + ":" + chunk + "," + chunk);

        landLoaded.complete(null);

        assertThat(upsideDown.getBlockAt(CENTER, 64, CENTER).getType()).isEqualTo(Material.GRASS_BLOCK);
        assertThat(asked)
                .containsExactly(land.getName() + ":" + chunk + "," + chunk, UPSIDE_DOWN + ":" + chunk + "," + chunk);
    }

    private UpsideDownStart start(int radius, List<String> palette) {
        return new UpsideDownStart(
                service,
                scheduler,
                new StrangerRealmsConfiguration.UpsideDown(radius, palette),
                () -> UPSIDE_DOWN,
                LOADED);
    }

    private static org.bukkit.block.data.BlockData axis(Material material, Axis axis) {
        Orientable data = (Orientable) material.createBlockData();
        data.setAxis(axis);
        return data;
    }

    private StarterPreset preset() throws Exception {
        return shipped().catalogue().findById("stranger_realms").orElseThrow();
    }

    private PresetConfiguration shipped() throws Exception {
        return PresetConfiguration.load(
                HoconConfigurationLoader.builder().buildAndLoadString(resource("modules/presets.conf")));
    }

    private String resource(String path) throws Exception {
        try (InputStream in = Objects.requireNonNull(getClass().getClassLoader().getResourceAsStream(path))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
