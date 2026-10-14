package com.uxplima.uxmskyblock.bukkit.grid;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.Chest;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemStack;

import com.uxplima.uxmlib.schematic.Schematic;
import com.uxplima.uxmskyblock.bukkit.config.PresetConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.SkyGridConfiguration;
import com.uxplima.uxmskyblock.bukkit.poseidon.WreckTemplate;
import com.uxplima.uxmskyblock.bukkit.schematic.IslandStart;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.grid.GridLayout;
import com.uxplima.uxmskyblock.core.domain.grid.GridPalette;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

/**
 * A SkyGrid's chests are filled from its palette's loot table, its blocks stand in its palette's biome, and now and
 * then a block of it is a small structure instead, laid only where it fits between one block and the next inside one
 * chunk.
 */
class TheGridHoldsLootBiomesAndSmallStructuresTest extends MockBukkitHarness {

    private static final int CENTER = 5_000;
    private static final int Y = 100;
    private static final int RADIUS = 12;
    private static final GridLayout LAYOUT = new GridLayout(4, RADIUS, 4, 4);

    private final List<String> tables = new ArrayList<>();

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

    /** A room three wide and two high: a chest in its middle, a spawner in a corner, air in another. */
    private static WreckTemplate room(int side, int high) {
        return room(side, side, high);
    }

    private static WreckTemplate room(int wide, int long_, int high) {
        List<WreckTemplate.Piece> pieces = new ArrayList<>();
        for (int x = 0; x < wide; x++) {
            for (int z = 0; z < long_; z++) {
                for (int y = 0; y < high; y++) {
                    Material type = Material.MOSSY_COBBLESTONE;
                    if (x == 1 && z == 1 && y == 0) {
                        type = Material.CHEST;
                    } else if (x == 0 && z == 0 && y == 0) {
                        type = Material.SPAWNER;
                    } else if (x == 2 && z == 2 && y == 0) {
                        type = Material.AIR;
                    }
                    pieces.add(new WreckTemplate.Piece(x, y, z, type.createBlockData(), null));
                }
            }
        }
        return new WreckTemplate(wide, long_, pieces);
    }

    private SkyGridStart start(String blocks, String biome, String loot, List<SkyGridConfiguration.Mini> structures) {
        SkyGridConfiguration config = new SkyGridConfiguration(
                true,
                LAYOUT,
                "GRASS_BLOCK",
                2,
                List.of(new SkyGridConfiguration.Loot("BREAD", 1, 1)),
                Map.of(
                        SkyGridConfiguration.OVERWORLD,
                        new SkyGridConfiguration.Palette(
                                new GridPalette(List.of(blocks.split(",")).stream()
                                        .map(GridPalette.Entry::parse)
                                        .toList()),
                                List.of("BLAZE"),
                                biome,
                                loot,
                                structures)));
        Map<String, WreckTemplate> held = Map.of(
                "room", room(3, 2),
                "wide", room(4, 3, 2),
                "long", room(3, 4, 2),
                "tall", room(3, 4),
                "edge", room(3, 3),
                "pair", room(2, 2));
        return new SkyGridStart(
                scheduler, config, key -> Optional.ofNullable(held.get(key)), (inventory, table, random, at) -> {
                    tables.add(table);
                    if (!table.equals("grid")) {
                        return false;
                    }
                    inventory.setItem(0, new ItemStack(Material.DIAMOND));
                    return true;
                });
    }

    private IslandStart island(World world, UUID id) {
        return new IslandStart(world, IslandId.of(id), CENTER, Y, CENTER, preset());
    }

    @Test
    @DisplayName(
            "A block is a structure where one fits between it and the next inside its chunk, and a block elsewhere")
    void structuresWhereTheyFit() {
        World world = server.addSimpleWorld("grid");
        start("STONE:1", "", "grid", List.of(new SkyGridConfiguration.Mini("room", 1)))
                .apply(island(world, UUID.randomUUID()));
        int laid = 0;
        int kept = 0;
        for (int dx = -RADIUS; dx <= RADIUS; dx += 4) {
            for (int dz = -RADIUS; dz <= RADIUS; dz += 4) {
                for (int dy = -4; dy <= 4; dy += 4) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }
                    int x = CENTER + dx;
                    int z = CENTER + dz;
                    boolean fits = (x - 1) >> 4 == (x + 1) >> 4
                            && (z - 1) >> 4 == (z + 1) >> 4
                            && Math.abs(dx) + 1 <= RADIUS
                            && Math.abs(dz) + 1 <= RADIUS;
                    Material at = world.getBlockAt(x, Y + dy, z).getType();
                    if (fits) {
                        laid++;
                        assertThat(at).describedAs("%s %s %s", dx, dy, dz).isEqualTo(Material.CHEST);
                        assertThat(((Chest) world.getBlockAt(x, Y + dy, z).getState())
                                        .getBlockInventory()
                                        .getItem(0))
                                .isEqualTo(new ItemStack(Material.DIAMOND));
                        assertThat(((CreatureSpawner) world.getBlockAt(x - 1, Y + dy, z - 1)
                                                .getState())
                                        .getSpawnedType())
                                .isEqualTo(EntityType.BLAZE);
                        assertThat(world.getBlockAt(x + 1, Y + dy, z + 1).getType())
                                .describedAs("the template's air leaves air")
                                .isEqualTo(Material.AIR);
                        assertThat(world.getBlockAt(x, Y + dy + 1, z).getType()).isEqualTo(Material.MOSSY_COBBLESTONE);
                    } else {
                        kept++;
                        assertThat(at).describedAs("%s %s %s", dx, dy, dz).isEqualTo(Material.STONE);
                    }
                }
            }
        }
        assertThat(laid).isPositive();
        assertThat(kept).isPositive();
        assertThat(world.getBlockAt(CENTER, Y, CENTER).getType())
                .describedAs("the block players arrive on is never a structure")
                .isEqualTo(Material.GRASS_BLOCK);

        for (String big : List.of("wide", "long", "tall")) {
            World too = server.addSimpleWorld("grid_" + big);
            start("STONE:1", "", "grid", List.of(new SkyGridConfiguration.Mini(big, 1)))
                    .apply(island(too, UUID.randomUUID()));
            for (int dx = -RADIUS; dx <= RADIUS; dx += 4) {
                for (int dz = -RADIUS; dz <= RADIUS; dz += 4) {
                    if (dx != 0 || dz != 0) {
                        assertThat(too.getBlockAt(CENTER + dx, Y, CENTER + dz).getType())
                                .describedAs("%s as the gap: never laid", big)
                                .isEqualTo(Material.STONE);
                    }
                }
            }
        }
        World pair = server.addSimpleWorld("grid_pair");
        start("STONE:1", "", "grid", List.of(new SkyGridConfiguration.Mini("pair", 1)))
                .apply(island(pair, UUID.randomUUID()));
        assertThat(pair.getBlockAt(CENTER + 4, Y, CENTER + 4).getType())
                .describedAs("two wide, its middle on the block")
                .isEqualTo(Material.CHEST);
        assertThat(pair.getBlockAt(CENTER + 3, Y, CENTER + 3).getType()).isEqualTo(Material.SPAWNER);
        World three = server.addSimpleWorld("grid_three");
        start("STONE:1", "", "grid", List.of(new SkyGridConfiguration.Mini("edge", 1)))
                .apply(island(three, UUID.randomUUID()));
        assertThat(three.getBlockAt(CENTER + 4, Y, CENTER).getType())
                .describedAs("three high in a gap of four fits")
                .isEqualTo(Material.CHEST);
    }

    @Test
    @DisplayName("The structures roll apart from the blocks: a block not a structure is the block it was without them")
    void theBlocksStand() {
        UUID id = UUID.randomUUID();
        World without = server.addSimpleWorld("grid_without");
        start("STONE:1,DIRT:1,SAND:1,GRAVEL:1", "", "", List.of()).apply(island(without, id));
        World with = server.addSimpleWorld("grid_with");
        start("STONE:1,DIRT:1,SAND:1,GRAVEL:1", "", "", List.of(new SkyGridConfiguration.Mini("room", 0.5)))
                .apply(island(with, id));
        Map<Material, Integer> seen = new HashMap<>();
        int structures = 0;
        for (int dx = -RADIUS; dx <= RADIUS; dx += 4) {
            for (int dz = -RADIUS; dz <= RADIUS; dz += 4) {
                for (int dy = -4; dy <= 4; dy += 4) {
                    Material was =
                            without.getBlockAt(CENTER + dx, Y + dy, CENTER + dz).getType();
                    Material is =
                            with.getBlockAt(CENTER + dx, Y + dy, CENTER + dz).getType();
                    if (is == Material.CHEST) {
                        structures++;
                    } else {
                        assertThat(is).isEqualTo(was);
                    }
                    seen.merge(was, 1, Integer::sum);
                }
            }
        }
        assertThat(structures).isPositive();
        assertThat(seen).containsKeys(Material.STONE, Material.DIRT, Material.SAND, Material.GRAVEL);
    }

    @Test
    @DisplayName("The grid stands in its palette's biome, its chests filled from the palette's loot table")
    void biomeAndLoot() {
        World world = server.addSimpleWorld("grid_desert");
        start("CHEST:1", "minecraft:desert", "grid", List.of()).apply(island(world, UUID.randomUUID()));
        // A biome is kept a cell of four blocks a side at a time, as the server keeps it: each cell is set once,
        // at its corner, from the grid's floor to its roof and across its width.
        for (int x = CENTER - RADIUS; x <= CENTER + RADIUS; x += 4) {
            for (int y = Y - 4; y <= Y + 4; y += 4) {
                assertThat(world.getBiome(x, y, CENTER - RADIUS)).isEqualTo(Biome.DESERT);
                assertThat(world.getBiome(x, y, CENTER + RADIUS)).isEqualTo(Biome.DESERT);
                assertThat(world.getBiome(CENTER + RADIUS, y, x)).isEqualTo(Biome.DESERT);
            }
        }
        assertThat(world.getBiome(CENTER + RADIUS + 4, Y, CENTER))
                .describedAs("past the grid")
                .isNotEqualTo(Biome.DESERT);
        Chest filled = (Chest) world.getBlockAt(CENTER + 4, Y, CENTER).getState();
        assertThat(filled.getBlockInventory().getItem(0)).isEqualTo(new ItemStack(Material.DIAMOND));
        assertThat(tables).containsOnly("grid");

        World plain = server.addSimpleWorld("grid_plain");
        Biome before = plain.getBiome(CENTER + 4, Y, CENTER);
        start("CHEST:1", "", "missing", List.of()).apply(island(plain, UUID.randomUUID()));
        assertThat(plain.getBiome(CENTER + 4, Y, CENTER))
                .describedAs("no biome named")
                .isEqualTo(before);
        Chest fallen = (Chest) plain.getBlockAt(CENTER + 4, Y, CENTER).getState();
        assertThat(fallen.getBlockInventory().contains(Material.BREAD))
                .describedAs("a table loot/ has not: the chest items")
                .isTrue();
        assertThat(tables).contains("missing");
        tables.clear();
        start("CHEST:1", "", "", List.of()).apply(island(server.addSimpleWorld("grid_items"), UUID.randomUUID()));
        assertThat(tables).describedAs("no table named: none is asked").isEmpty();
    }

    @Test
    @DisplayName(
            "The shipped structures are small enough for the shipped grid, and the palettes are read from the file")
    void theShippedAndTheFile() throws Exception {
        assertThat(GridLayouts.SHIPPED).containsOnlyKeys("grid_tree", "grid_well", "grid_fortress", "grid_pillar");
        GridLayouts.SHIPPED.forEach((name, layout) -> {
            Schematic built = layout.apply(4000);
            assertThat(Math.max(built.width(), Math.max(built.height(), built.length())))
                    .describedAs(name)
                    .isLessThan(GridLayout.SHIPPED.spacing());
        });
        assertThat(GridLayouts.tree(4000).blockAt(1, 1, 1)).startsWith("minecraft:oak_log");
        assertThat(GridLayouts.well(4000).blockAt(1, 1, 1)).startsWith("minecraft:water");
        assertThat(GridLayouts.fortress(4000).blockAt(1, 1, 1)).isEqualTo("minecraft:spawner");
        assertThat(GridLayouts.pillar(4000).blockAt(1, 2, 1)).startsWith("minecraft:chest");

        SkyGridConfiguration shipped = SkyGridConfiguration.load(
                HoconConfigurationLoader.builder().buildAndLoadString(resource("modules/skygrid.conf")));
        assertThat(shipped.palettes())
                .isEqualTo(SkyGridConfiguration.defaultConfiguration().palettes());
        assertThat(shipped.palette(SkyGridConfiguration.OVERWORLD).loot()).isEqualTo("skygrid");
        assertThat(shipped.palette(SkyGridConfiguration.NETHER).biome()).isEqualTo("nether_wastes");
        assertThat(new SkyGridStart(scheduler, shipped).templateKeys())
                .containsExactlyInAnyOrder(
                        "schematic:grid_tree",
                        "schematic:grid_well",
                        "schematic:grid_fortress",
                        "schematic:grid_pillar");
        SkyGridConfiguration twice = SkyGridConfiguration.load(HoconConfigurationLoader.builder()
                .buildAndLoadString("palettes { overworld { structures = [\"same:0.1\"] },"
                        + " the_nether { structures = [\"same:0.1\"] }, the_end { structures = [] } }"));
        assertThat(new SkyGridStart(scheduler, twice).templateKeys()).containsExactly("same");
        assertThat(SkyGridConfiguration.Mini.parse(" Schematic:Grid_Tree : 0.004 "))
                .isEqualTo(new SkyGridConfiguration.Mini("schematic:grid_tree", 0.004));
        for (String bad : List.of("tree", "tree:2", "tree:many", ":0.1")) {
            assertThatThrownBy(() -> SkyGridConfiguration.Mini.parse(bad))
                    .describedAs(bad)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        SkyGridConfiguration written = SkyGridConfiguration.load(
                HoconConfigurationLoader.builder()
                        .buildAndLoadString(
                                "palettes { overworld { biome = \" Minecraft:Desert \", loot = \" Mine \","
                                        + " structures = [\"x:0.5\", \"y:0.25\"] }, the_end { structures = [\"x:0.6\", \"y:0.6\"] } }"));
        assertThat(written.palette(SkyGridConfiguration.OVERWORLD))
                .returns("minecraft:desert", SkyGridConfiguration.Palette::biome)
                .returns("mine", SkyGridConfiguration.Palette::loot)
                .returns(
                        List.of(new SkyGridConfiguration.Mini("x", 0.5), new SkyGridConfiguration.Mini("y", 0.25)),
                        SkyGridConfiguration.Palette::structures);
        assertThat(written.palette(SkyGridConfiguration.END))
                .describedAs("chances past one: the shipped grid")
                .isEqualTo(shipped.palette(SkyGridConfiguration.END));
    }

    private com.uxplima.uxmskyblock.core.domain.preset.StarterPreset preset() {
        try {
            return PresetConfiguration.load(
                            HoconConfigurationLoader.builder().buildAndLoadString(resource("modules/presets.conf")))
                    .catalogue()
                    .findById("skygrid")
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
