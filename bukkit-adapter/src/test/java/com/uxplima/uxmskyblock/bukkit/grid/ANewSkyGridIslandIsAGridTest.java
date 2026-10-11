package com.uxplima.uxmskyblock.bukkit.grid;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Chest;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemStack;

import com.uxplima.uxmskyblock.bukkit.config.PresetConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.SkyGridConfiguration;
import com.uxplima.uxmskyblock.bukkit.schematic.IslandStart;
import com.uxplima.uxmskyblock.bukkit.schematic.StarterSchematicEngine;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.dimension.DimensionId;
import com.uxplima.uxmskyblock.core.domain.gamemode.GameModeType;
import com.uxplima.uxmskyblock.core.domain.grid.GridLayout;
import com.uxplima.uxmskyblock.core.domain.grid.GridPalette;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.preset.StarterPreset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.world.WorldMock;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

/**
 * The shipped SkyGrid preset makes the island a sparse grid of blocks: one every few blocks along each
 * axis, the safe block players arrive on set before anything else, chests stocked, spawners raising a
 * creature, and each dimension a grid of its own blocks.
 */
class ANewSkyGridIslandIsAGridTest extends MockBukkitHarness {

    private static final int CENTER = 5_000;
    private static final int Y = 100;
    private static final GridLayout LAYOUT = new GridLayout(4, 12, 8, 8);

    private final List<Runnable> regions = new ArrayList<>();

    @SuppressWarnings("NullAway.Init")
    private SchedulerPort scheduler;

    @BeforeEach
    void setUpScheduler() {
        scheduler = mock(SchedulerPort.class);
        doAnswer(call -> {
                    regions.add(call.getArgument(3, Runnable.class));
                    return null;
                })
                .when(scheduler)
                .onRegion(anyString(), anyInt(), anyInt(), any(Runnable.class));
    }

    @Test
    @DisplayName("The safe block is there at once, and the grid stands one block every spacing, air between")
    void theGridIsSparse() throws Exception {
        World world = server.addSimpleWorld("skyblock");
        world.getBlockAt(CENTER + 4, Y + 4, CENTER).setType(Material.GOLD_BLOCK);
        StarterSchematicEngine engine = engine(config("STONE:1", List.of()));

        engine.start(new IslandStart(world, IslandId.of(UUID.randomUUID()), CENTER, Y, CENTER, preset()))
                .join();
        assertThat(world.getBlockAt(CENTER, Y, CENTER).getType())
                .describedAs("set before the rest of the grid, so it is there when players arrive")
                .isEqualTo(Material.GRASS_BLOCK);
        assertThat(world.getBlockAt(CENTER + 4, Y, CENTER).getType()).isEqualTo(Material.AIR);
        regions.forEach(Runnable::run);

        assertThat(regions).hasSize(9);
        assertThat(world.getBlockAt(CENTER + 4, Y, CENTER).getType()).isEqualTo(Material.STONE);
        assertThat(world.getBlockAt(CENTER - 12, Y - 8, CENTER + 8).getType()).isEqualTo(Material.STONE);
        assertThat(world.getBlockAt(CENTER + 2, Y, CENTER).getType()).isEqualTo(Material.AIR);
        assertThat(world.getBlockAt(CENTER, Y + 1, CENTER).getType()).isEqualTo(Material.AIR);
        assertThat(world.getBlockAt(CENTER + 16, Y, CENTER).getType())
                .describedAs("nothing past the radius")
                .isEqualTo(Material.AIR);
        assertThat(world.getBlockAt(CENTER, Y - 12, CENTER).getType())
                .describedAs("nothing below the grid")
                .isEqualTo(Material.AIR);
        assertThat(world.getBlockAt(CENTER + 4, Y + 4, CENTER).getType())
                .describedAs("a block already there is kept")
                .isEqualTo(Material.GOLD_BLOCK);
    }

    @Test
    @DisplayName("A chest in the grid holds some of the chest items, and a spawner raises the dimension's creature")
    void chestsAndSpawners() throws Exception {
        World world = server.addSimpleWorld("skyblock");
        engine(config("CHEST:1", List.of()))
                .build(
                        new IslandStart(world, IslandId.of(UUID.randomUUID()), CENTER, Y, CENTER, preset()),
                        List.of(SkyGridStart.ACTION))
                .join();
        regions.forEach(Runnable::run);
        regions.clear();

        Chest chest = (Chest) world.getBlockAt(CENTER + 4, Y, CENTER).getState();
        List<ItemStack> held = new ArrayList<>();
        for (ItemStack item : chest.getBlockInventory().getContents()) {
            if (item != null && !item.isEmpty()) {
                held.add(item);
            }
        }
        assertThat(held).isNotEmpty().hasSizeLessThanOrEqualTo(2);
        assertThat(held).allSatisfy(item -> assertThat(item.getType()).isIn(Material.BONE_MEAL, Material.TORCH));

        World other = server.addSimpleWorld("skyblock_two");
        engine(config("SPAWNER:1", List.of("BLAZE")))
                .build(
                        new IslandStart(other, IslandId.of(UUID.randomUUID()), CENTER, Y, CENTER, preset()),
                        List.of(SkyGridStart.ACTION))
                .join();
        regions.forEach(Runnable::run);

        CreatureSpawner spawner =
                (CreatureSpawner) other.getBlockAt(CENTER, Y - 4, CENTER).getState();
        assertThat(spawner.getSpawnedType()).isEqualTo(EntityType.BLAZE);
    }

    @Test
    @DisplayName("An island in the Nether is a grid of the Nether's blocks")
    void theNetherHasItsOwnGrid() throws Exception {
        WorldMock nether = server.addSimpleWorld("skyblock_nether");
        nether.setEnvironment(World.Environment.NETHER);
        SkyGridConfiguration config = new SkyGridConfiguration(
                true,
                LAYOUT,
                "NETHERRACK",
                0,
                List.of(),
                Map.of(
                        SkyGridConfiguration.OVERWORLD,
                        new SkyGridConfiguration.Palette(palette("STONE:1"), List.of()),
                        SkyGridConfiguration.NETHER,
                        new SkyGridConfiguration.Palette(palette("SOUL_SAND:1"), List.of())));

        engine(config)
                .build(
                        new IslandStart(nether, IslandId.of(UUID.randomUUID()), CENTER, 70, CENTER, preset()),
                        List.of(SkyGridStart.ACTION))
                .join();
        regions.forEach(Runnable::run);

        assertThat(nether.getBlockAt(CENTER + 4, 70, CENTER).getType()).isEqualTo(Material.SOUL_SAND);
    }

    @Test
    @DisplayName("The preset plays SkyGrid in every dimension, and the shipped file reads as shipped")
    void thePresetAndTheFile() throws Exception {
        StarterPreset preset = preset();

        assertThat(preset.mode()).isEqualTo(GameModeType.SKYGRID);
        assertThat(preset.start()).containsExactly(SkyGridStart.ACTION);
        assertThat(preset.dimensions()
                        .resolve(DimensionId.THE_NETHER)
                        .orElseThrow()
                        .actions())
                .containsExactly(SkyGridStart.ACTION);
        assertThat(shipped()
                        .startableWith(new StarterSchematicEngine().actions()::knowsAll)
                        .catalogue()
                        .findById("skygrid"))
                .describedAs("not offered where SkyGrid is off")
                .isEmpty();
        assertThat(SkyGridConfiguration.load(
                        HoconConfigurationLoader.builder().buildAndLoadString(resource("modules/skygrid.conf"))))
                .isEqualTo(SkyGridConfiguration.defaultConfiguration());
        SkyGridConfiguration odd = SkyGridConfiguration.load(HoconConfigurationLoader.builder()
                .buildAndLoadString(
                        "spacing = 1\nchests { items = [\"TORCH:0:4\"] }\n" + "palettes { the_end { blocks = [] } }"));
        SkyGridConfiguration shipped = SkyGridConfiguration.defaultConfiguration();
        assertThat(odd.layout()).isEqualTo(GridLayout.SHIPPED);
        assertThat(odd.chestItems()).isEqualTo(shipped.chestItems());
        assertThat(odd.palette(SkyGridConfiguration.END)).isEqualTo(shipped.palette(SkyGridConfiguration.END));
    }

    @Test
    @DisplayName("A SkyGrid island notes that it wrote as far as its grid, past the island it starts with")
    void theGridIsNoted() throws Exception {
        World world = server.addSimpleWorld("skyblock");
        StarterSchematicEngine engine = engine(config("STONE:1", List.of()));
        List<com.uxplima.uxmskyblock.core.domain.recycle.IslandFootprint> noted = new ArrayList<>();
        engine.useFootprints(new com.uxplima.uxmskyblock.core.application.recycle.IslandFootprintPort() {
            @Override
            public void record(
                    IslandId islandId, com.uxplima.uxmskyblock.core.domain.recycle.IslandFootprint footprint) {
                noted.add(footprint);
            }

            @Override
            public List<com.uxplima.uxmskyblock.core.domain.recycle.IslandFootprint> of(IslandId islandId) {
                return List.of();
            }

            @Override
            public void forget(IslandId islandId) {}
        });

        engine.start(new IslandStart(world, IslandId.of(UUID.randomUUID()), CENTER, Y, CENTER, preset()))
                .join();

        assertThat(noted)
                .contains(
                        new com.uxplima.uxmskyblock.core.domain.recycle.IslandFootprint("skyblock", CENTER, CENTER, 0),
                        new com.uxplima.uxmskyblock.core.domain.recycle.IslandFootprint(
                                "skyblock", CENTER, CENTER, LAYOUT.radius()));
    }

    private StarterSchematicEngine engine(SkyGridConfiguration config) {
        StarterSchematicEngine engine = new StarterSchematicEngine();
        engine.actions().register(new SkyGridStart(scheduler, config));
        return engine;
    }

    private static SkyGridConfiguration config(String block, List<String> spawners) {
        return new SkyGridConfiguration(
                true,
                LAYOUT,
                "GRASS_BLOCK",
                2,
                List.of(
                        new SkyGridConfiguration.Loot("BONE_MEAL", 2, 6),
                        new SkyGridConfiguration.Loot("TORCH", 4, 12)),
                Map.of(SkyGridConfiguration.OVERWORLD, new SkyGridConfiguration.Palette(palette(block), spawners)));
    }

    private static GridPalette palette(String block) {
        return new GridPalette(List.of(GridPalette.Entry.parse(block)));
    }

    private StarterPreset preset() throws Exception {
        return shipped().catalogue().findById("skygrid").orElseThrow();
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
