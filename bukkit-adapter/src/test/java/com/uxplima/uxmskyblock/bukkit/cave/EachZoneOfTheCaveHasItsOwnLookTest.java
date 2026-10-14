package com.uxplima.uxmskyblock.bukkit.cave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Biome;

import com.uxplima.uxmskyblock.bukkit.config.CaveBlockConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.PresetConfiguration;
import com.uxplima.uxmskyblock.bukkit.schematic.IslandStart;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.cave.CavePlan;
import com.uxplima.uxmskyblock.core.domain.cave.OreTable;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.preset.StarterPreset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.world.WorldMock;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

/**
 * A CaveBlock island's cave is split into zones round the island, each with its own look: its biome, the blocks its
 * floors and ceilings are covered in, what stands on them and hangs from them, and, for a deep zone, the deep rock.
 */
class EachZoneOfTheCaveHasItsOwnLookTest extends MockBukkitHarness {

    private static final int CENTER = 5_000;
    private static final int Y = 99;
    private static final CavePlan.Shape SHAPE = new CavePlan.Shape(16, 10, 8, 3, 6, 14, 2, 12, 6);
    private static final Map<String, CaveBlockConfiguration.Palette> ROCK = Map.of(
            CaveBlockConfiguration.OVERWORLD,
            new CaveBlockConfiguration.Palette("STONE", OreTable.NONE, "DEEPSLATE", 100, OreTable.NONE),
            CaveBlockConfiguration.NETHER,
            CaveBlockConfiguration.Palette.plain("NETHERRACK", OreTable.NONE));
    private static final CaveBlockConfiguration.Zone MOSSY = new CaveBlockConfiguration.Zone(
            "lush_caves",
            false,
            table("MOSS_BLOCK:1"),
            table("MOSS_CARPET:1"),
            table("CLAY:1"),
            table("POINTED_DRIPSTONE:1"),
            List.of(CaveBlockConfiguration.OVERWORLD));
    private static final CaveBlockConfiguration.Zone DEEP = new CaveBlockConfiguration.Zone(
            "deep_dark",
            true,
            table("SCULK:1"),
            table("SCULK_SENSOR:1"),
            table("SCULK_CATALYST:1"),
            OreTable.NONE,
            List.of(CaveBlockConfiguration.OVERWORLD));
    private static final Set<Material> MOSSY_LOOK =
            EnumSet.of(Material.MOSS_BLOCK, Material.MOSS_CARPET, Material.CLAY, Material.POINTED_DRIPSTONE);
    private static final Set<Material> DEEP_LOOK =
            EnumSet.of(Material.SCULK, Material.SCULK_SENSOR, Material.SCULK_CATALYST, Material.DEEPSLATE);

    @SuppressWarnings("NullAway.Init")
    private SchedulerPort scheduler;

    @BeforeEach
    void setUpTheRegions() {
        scheduler = mock(SchedulerPort.class);
        doAnswer(call -> {
                    call.getArgument(3, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .onRegion(anyString(), anyInt(), anyInt(), any(Runnable.class));
    }

    private static OreTable table(String... written) {
        return new OreTable(List.of(written).stream().map(OreTable.Ore::parse).toList());
    }

    private CavernStart cavern(Map<String, CaveBlockConfiguration.Zone> zones) {
        return new CavernStart(
                scheduler, new CaveBlockConfiguration(true, SHAPE, "BEDROCK", ROCK, Map.of(), zones, Map.of()));
    }

    @Test
    @DisplayName(
            "Each zone dresses its own share of the cave: its floors, ceilings, rock and biome, the room left bare")
    void eachZoneHasItsLook() {
        World world = server.addSimpleWorld("caves");
        cavern(Map.of("a_mossy", MOSSY, "b_deep", DEEP))
                .apply(new IslandStart(world, IslandId.of(UUID.randomUUID()), CENTER, Y, CENTER, preset()));

        int mossy = 0;
        int deep = 0;
        for (int x = CENTER - 15; x <= CENTER + 15; x++) {
            for (int z = CENTER - 15; z <= CENTER + 15; z++) {
                boolean hasMossy = false;
                boolean hasDeep = false;
                boolean hasStone = false;
                for (int y = Y - 9; y <= Y + 7; y++) {
                    Material type = world.getBlockAt(x, y, z).getType();
                    hasMossy |= MOSSY_LOOK.contains(type);
                    hasDeep |= DEEP_LOOK.contains(type);
                    hasStone |= type == Material.STONE;
                    Material above = world.getBlockAt(x, y + 1, z).getType();
                    Material below = world.getBlockAt(x, y - 1, z).getType();
                    if (type == Material.MOSS_CARPET) {
                        assertThat(below)
                                .describedAs("a carpet stands on the moss floor")
                                .isEqualTo(Material.MOSS_BLOCK);
                    }
                    if (type == Material.MOSS_BLOCK) {
                        assertThat(above)
                                .describedAs("a moss floor has its carpet")
                                .isEqualTo(Material.MOSS_CARPET);
                    }
                    // A gap one block high is taken by the carpet of the floor beneath it, and a block both floor
                    // and ceiling keeps the floor's cover.
                    if (type == Material.CLAY) {
                        assertThat(below)
                                .describedAs("a ceiling has its dripstone")
                                .isIn(Material.POINTED_DRIPSTONE, Material.MOSS_CARPET);
                    }
                    if (type == Material.POINTED_DRIPSTONE) {
                        assertThat(above).isIn(Material.CLAY, Material.MOSS_BLOCK);
                    }
                    if (type == Material.SCULK_SENSOR) {
                        assertThat(below).isEqualTo(Material.SCULK);
                    }
                    if (type == Material.SCULK) {
                        assertThat(above).isEqualTo(Material.SCULK_SENSOR);
                    }
                    if (type == Material.SCULK_CATALYST) {
                        assertThat(below)
                                .describedAs("the deep zone's ceiling opens below")
                                .isIn(Material.AIR, Material.SCULK_SENSOR);
                    }
                }
                int dx = x - CENTER;
                int dz = z - CENTER;
                assertThat(hasMossy && hasDeep)
                        .describedAs("one zone to a column")
                        .isFalse();
                if (dx * dx + dz * dz <= 25) {
                    assertThat(hasMossy || hasDeep)
                            .describedAs("the room and round it are bare")
                            .isFalse();
                    assertThat(world.getBiome(x, Y + 2, z)).isNotIn(Biome.LUSH_CAVES, Biome.DEEP_DARK);
                }
                if (hasDeep) {
                    deep++;
                    assertThat(hasStone)
                            .describedAs("the deep zone is deep rock")
                            .isFalse();
                    assertThat(world.getBiome(x, Y, z)).isEqualTo(Biome.DEEP_DARK);
                }
                if (hasMossy) {
                    mossy++;
                    assertThat(world.getBiome(x, Y, z)).isEqualTo(Biome.LUSH_CAVES);
                    assertThat(world.getBiome(x, Y + 8, z))
                            .describedAs("the shell keeps its own")
                            .isNotEqualTo(Biome.LUSH_CAVES);
                }
            }
        }
        assertThat(mossy).isPositive();
        assertThat(deep).isPositive();
    }

    @Test
    @DisplayName("A zone stays out of the dimensions it does not list, and no zones leave the rock bare")
    void zonesKeepToTheirDimensions() {
        WorldMock nether = server.addSimpleWorld("caves_nether");
        nether.setEnvironment(World.Environment.NETHER);
        cavern(Map.of("a_mossy", MOSSY, "b_deep", DEEP))
                .apply(new IslandStart(nether, IslandId.of(UUID.randomUUID()), CENTER, 70, CENTER, preset()));
        World bare = server.addSimpleWorld("caves_bare");
        cavern(Map.of()).apply(new IslandStart(bare, IslandId.of(UUID.randomUUID()), CENTER, Y, CENTER, preset()));
        for (int x = CENTER - 15; x <= CENTER + 15; x++) {
            for (int z = CENTER - 15; z <= CENTER + 15; z++) {
                for (int y = -9; y <= 7; y++) {
                    assertThat(nether.getBlockAt(x, 70 + y, z).getType())
                            .isNotIn(MOSSY_LOOK)
                            .isNotIn(DEEP_LOOK);
                    assertThat(bare.getBlockAt(x, Y + y, z).getType())
                            .isNotIn(MOSSY_LOOK)
                            .isNotIn(DEEP_LOOK);
                }
            }
        }
    }

    @Test
    @DisplayName("A column's zone is its share of the round, turned; none within the room's reach")
    void theRoundIsShared() {
        CavernStart.Look one =
                new CavernStart.Look(null, false, OreTable.NONE, OreTable.NONE, OreTable.NONE, OreTable.NONE);
        CavernStart.Look two =
                new CavernStart.Look(null, true, OreTable.NONE, OreTable.NONE, OreTable.NONE, OreTable.NONE);
        CavernStart.Zoning zoning = new CavernStart.Zoning(List.of(one, two), 0, 5);
        assertThat(zoning.at(0, 0)).isNull();
        assertThat(zoning.at(3, 4)).describedAs("on the room's reach").isNull();
        assertThat(zoning.at(10, 1)).isSameAs(one);
        assertThat(zoning.at(-10, 1)).isSameAs(one);
        assertThat(zoning.at(-10, -1)).isSameAs(two);
        assertThat(zoning.at(10, -1)).isSameAs(two);
        CavernStart.Zoning turned = new CavernStart.Zoning(List.of(one, two), Math.PI, 5);
        assertThat(turned.at(10, 1)).isSameAs(two);
        assertThat(turned.at(-10, -1)).isSameAs(one);
        CavernStart.Zoning quarter = new CavernStart.Zoning(List.of(one, two), Math.PI / 2, 5);
        assertThat(quarter.at(10, 1)).describedAs("turned a quarter on").isSameAs(two);
        assertThat(quarter.at(-1, 10)).isSameAs(one);
        assertThat(new CavernStart.Zoning(List.of(), 0, 5).at(10, 1)).isNull();
    }

    @Test
    @DisplayName("The zones are read from the file, by dimension and in the order of their names")
    void theFile() throws Exception {
        CaveBlockConfiguration shipped = CaveBlockConfiguration.load(
                HoconConfigurationLoader.builder().buildAndLoadString(resource("modules/caveblock.conf")));
        assertThat(shipped.zones()).isEqualTo(CaveBlockConfiguration.SHIPPED_ZONES);
        assertThat(shipped.zonesOf(CaveBlockConfiguration.OVERWORLD))
                .containsExactly(
                        CaveBlockConfiguration.SHIPPED_ZONES.get("deep_dark"),
                        CaveBlockConfiguration.SHIPPED_ZONES.get("dripstone"),
                        CaveBlockConfiguration.SHIPPED_ZONES.get("lush"));
        assertThat(shipped.zonesOf(CaveBlockConfiguration.NETHER)).isEmpty();

        CaveBlockConfiguration written = CaveBlockConfiguration.load(HoconConfigurationLoader.builder()
                .buildAndLoadString("zones { lush { dimensions = [] }, Crystal { biome = \" Minecraft:Plains \","
                        + " floor = [\"AMETHYST_BLOCK:0.5\"], dimensions = [\" The_Nether \"] },"
                        + " dripstone { floor = [\"DRIPSTONE_BLOCK:2\"] } }"));
        assertThat(written.zones()).containsKeys("lush", "deep_dark", "crystal").doesNotContainKey("dripstone");
        assertThat(written.zonesOf(CaveBlockConfiguration.OVERWORLD))
                .containsExactly(CaveBlockConfiguration.SHIPPED_ZONES.get("deep_dark"));
        assertThat(written.zonesOf(CaveBlockConfiguration.NETHER))
                .containsExactly(new CaveBlockConfiguration.Zone(
                        "minecraft:plains",
                        false,
                        table("AMETHYST_BLOCK:0.5"),
                        OreTable.NONE,
                        OreTable.NONE,
                        OreTable.NONE,
                        List.of(CaveBlockConfiguration.NETHER)));
    }

    private StarterPreset preset() {
        try {
            return PresetConfiguration.load(
                            HoconConfigurationLoader.builder().buildAndLoadString(resource("modules/presets.conf")))
                    .catalogue()
                    .findById("caveblock")
                    .orElseThrow();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String resource(String path) throws Exception {
        try (InputStream in = Objects.requireNonNull(getClass().getClassLoader().getResourceAsStream(path))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
