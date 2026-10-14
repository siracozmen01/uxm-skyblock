package com.uxplima.uxmskyblock.bukkit.cave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Material;
import org.bukkit.World;

import com.uxplima.uxmskyblock.bukkit.config.CaveBlockConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.PresetConfiguration;
import com.uxplima.uxmskyblock.bukkit.schematic.IslandStart;
import com.uxplima.uxmskyblock.bukkit.schematic.StarterSchematicEngine;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.cave.CavePlan;
import com.uxplima.uxmskyblock.core.domain.cave.OreTable;
import com.uxplima.uxmskyblock.core.domain.dimension.DimensionId;
import com.uxplima.uxmskyblock.core.domain.gamemode.GameModeType;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.preset.StarterPreset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.world.WorldMock;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

/**
 * The shipped CaveBlock preset lays the starter platform and encloses it in rock: a shell all round,
 * rock and ores inside, and the room players arrive in carved out, each chunk on its own region's
 * thread and in the rock of its dimension.
 */
class ANewCaveBlockIslandIsInTheRockTest extends MockBukkitHarness {

    private static final int CENTER = 5_000;
    private static final int Y = 99;
    private static final CavePlan.Shape SHAPE = new CavePlan.Shape(12, 8, 8, 3, 2, 10, 1, 8, 6);
    private static final CaveBlockConfiguration CONFIG = new CaveBlockConfiguration(
            true,
            SHAPE,
            "BEDROCK",
            Map.of(
                    CaveBlockConfiguration.OVERWORLD,
                    new CaveBlockConfiguration.Palette(
                            "STONE",
                            new OreTable(
                                    List.of(new OreTable.Ore("COAL_ORE", 0.2), new OreTable.Ore("NOT_A_BLOCK", 0.1))),
                            "DEEPSLATE",
                            4,
                            new OreTable(List.of(new OreTable.Ore("DEEPSLATE_DIAMOND_ORE", 0.2)))),
                    CaveBlockConfiguration.NETHER,
                    CaveBlockConfiguration.Palette.plain("NETHERRACK", OreTable.NONE)),
            // The rock alone: the structures, zones and pools laid over it have tests of their own.
            Map.of(),
            Map.of(),
            Map.of());

    private final Set<String> regions = new HashSet<>();

    @SuppressWarnings("NullAway.Init")
    private StarterSchematicEngine engine;

    @BeforeEach
    void setUpEngine() {
        SchedulerPort scheduler = mock(SchedulerPort.class);
        doAnswer(call -> {
                    regions.add(call.getArgument(1) + "," + call.getArgument(2));
                    call.getArgument(3, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .onRegion(anyString(), anyInt(), anyInt(), any(Runnable.class));
        engine = new StarterSchematicEngine();
        engine.actions().register(new CavernStart(scheduler, CONFIG));
    }

    @Test
    @DisplayName("The island stands in a room carved out of rock and ore, closed in by a shell")
    void theIslandIsInTheRock() throws Exception {
        World world = server.addSimpleWorld("skyblock");
        // Blocks already there, in rock no carving reaches and in the shell: the rock fills only air.
        world.getBlockAt(CENTER + 11, Y, CENTER + 11).setType(Material.GOLD_BLOCK);
        world.getBlockAt(CENTER + SHAPE.radius(), Y + 3, CENTER).setType(Material.GOLD_BLOCK);
        engine.start(new IslandStart(world, IslandId.of(UUID.randomUUID()), CENTER, Y, CENTER, preset()))
                .join();

        assertThat(world.getBlockAt(CENTER + 11, Y, CENTER + 11).getType()).isEqualTo(Material.GOLD_BLOCK);
        assertThat(world.getBlockAt(CENTER + SHAPE.radius(), Y + 3, CENTER).getType())
                .isEqualTo(Material.GOLD_BLOCK);

        assertThat(world.getBlockAt(CENTER, Y, CENTER).getType())
                .describedAs("the platform is kept")
                .isEqualTo(Material.DEEPSLATE);
        assertThat(world.getBlockAt(CENTER, Y + 1, CENTER).getType()).isEqualTo(Material.AIR);
        assertThat(world.getBlockAt(CENTER, Y + 2, CENTER).getType()).isEqualTo(Material.AIR);
        assertThat(world.getBlockAt(CENTER + SHAPE.radius(), Y + 2, CENTER).getType())
                .isEqualTo(Material.BEDROCK);
        assertThat(world.getBlockAt(CENTER + 3, Y - SHAPE.below(), CENTER - 5).getType())
                .isEqualTo(Material.BEDROCK);
        assertThat(world.getBlockAt(CENTER - 4, Y + SHAPE.above(), CENTER + 2).getType())
                .isEqualTo(Material.BEDROCK);
        assertThat(world.getBlockAt(CENTER + SHAPE.radius() + 1, Y, CENTER).getType())
                .describedAs("nothing past the shell")
                .isEqualTo(Material.AIR);

        Map<Material, Integer> above = count(world, Y + 1, Y + SHAPE.above() - 1);
        Map<Material, Integer> deep = count(world, Y - SHAPE.below() + 1, Y - 5);
        assertThat(above).containsKeys(Material.STONE, Material.COAL_ORE, Material.AIR);
        assertThat(above).doesNotContainKeys(Material.DEEPSLATE, Material.DEEPSLATE_DIAMOND_ORE);
        assertThat(deep).containsKeys(Material.DEEPSLATE, Material.DEEPSLATE_DIAMOND_ORE);
        assertThat(deep).doesNotContainKeys(Material.STONE, Material.COAL_ORE);
        assertThat(regions)
                .describedAs("each chunk of the rock is laid on its own region's thread")
                .hasSize(9);
    }

    @Test
    @DisplayName("The same action encloses an island in the Nether in that dimension's own rock")
    void theNetherHasItsOwnRock() throws Exception {
        WorldMock nether = server.addSimpleWorld("skyblock_nether");
        nether.setEnvironment(World.Environment.NETHER);

        engine.build(
                        new IslandStart(nether, IslandId.of(UUID.randomUUID()), CENTER, 70, CENTER, preset()),
                        List.of(CavernStart.ACTION))
                .join();

        assertThat(count(nether, 62, 77)).containsKey(Material.NETHERRACK).doesNotContainKey(Material.STONE);
    }

    @Test
    @DisplayName("The preset plays CaveBlock, in rock in every dimension, and the shipped file reads as shipped")
    void thePresetAndTheFile() throws Exception {
        StarterPreset preset = preset();

        assertThat(preset.mode()).isEqualTo(GameModeType.CAVEBLOCK);
        assertThat(preset.start()).containsExactly(StarterPreset.PLATFORM, CavernStart.ACTION);
        assertThat(preset.dimensions()
                        .resolve(DimensionId.THE_NETHER)
                        .orElseThrow()
                        .actions())
                .contains(CavernStart.ACTION);
        assertThat(preset.dimensions()
                        .resolve(DimensionId.THE_END)
                        .orElseThrow()
                        .actions())
                .contains(CavernStart.ACTION);
        assertThat(shipped()
                        .startableWith(new StarterSchematicEngine().actions()::knowsAll)
                        .catalogue()
                        .findById("caveblock"))
                .describedAs("not offered where CaveBlock is off")
                .isEmpty();
        assertThat(CaveBlockConfiguration.load(
                        HoconConfigurationLoader.builder().buildAndLoadString(resource("modules/caveblock.conf"))))
                .isEqualTo(CaveBlockConfiguration.defaultConfiguration());
        CaveBlockConfiguration odd = CaveBlockConfiguration.load(HoconConfigurationLoader.builder()
                .buildAndLoadString("room { radius = 40 }\npalettes { the_end { ores = [\"END_STONE:2\"] } }"));
        assertThat(odd.shape()).isEqualTo(CavePlan.Shape.SHIPPED);
        assertThat(odd.palette(CaveBlockConfiguration.END))
                .isEqualTo(CaveBlockConfiguration.defaultConfiguration().palette(CaveBlockConfiguration.END));
        assertThat(odd.palette("myserver:mining_realm").rock()).isEqualTo("STONE");
    }

    private static Map<Material, Integer> count(World world, int fromY, int toY) {
        Map<Material, Integer> counted = new HashMap<>();
        for (int x = CENTER - SHAPE.radius() + 1; x < CENTER + SHAPE.radius(); x++) {
            for (int z = CENTER - SHAPE.radius() + 1; z < CENTER + SHAPE.radius(); z++) {
                for (int y = fromY; y <= toY; y++) {
                    counted.merge(world.getBlockAt(x, y, z).getType(), 1, Integer::sum);
                }
            }
        }
        return counted;
    }

    private StarterPreset preset() throws Exception {
        return shipped().catalogue().findById("caveblock").orElseThrow();
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
