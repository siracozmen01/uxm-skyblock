package com.uxplima.uxmskyblock.bukkit.acid;

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
import org.bukkit.block.data.type.BubbleColumn;

import com.uxplima.uxmskyblock.bukkit.config.AcidIslandConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.PresetConfiguration;
import com.uxplima.uxmskyblock.bukkit.schematic.IslandStart;
import com.uxplima.uxmskyblock.bukkit.schematic.StarterSchematicEngine;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.acid.AcidIslandService;
import com.uxplima.uxmskyblock.core.application.acid.AcidIslandsPort;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.gamemode.GameModeType;
import com.uxplima.uxmskyblock.core.domain.hazard.AcidRules;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.preset.StarterPreset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

/**
 * The shipped AcidIsland preset lays the starter platform and then the acid sea around it: water in the
 * air around the island, a floor under the water, and every block the island already has kept.
 */
class ANewAcidIslandStandsInItsSeaTest extends MockBukkitHarness {

    private static final int CENTER = 5_000;
    private static final int Y = 99;
    private static final AcidIslandConfiguration.Sea SEA = new AcidIslandConfiguration.Sea(2, 3, 20, "SANDSTONE");

    private final Map<IslandId, Integer> recorded = new HashMap<>();
    private final Set<String> regions = new HashSet<>();

    @SuppressWarnings("NullAway.Init")
    private World world;

    @SuppressWarnings("NullAway.Init")
    private AcidIslandService service;

    @SuppressWarnings("NullAway.Init")
    private StarterSchematicEngine engine;

    @SuppressWarnings("NullAway.Init")
    private SchedulerPort scheduler;

    @BeforeEach
    void setUpEngine() {
        world = server.addSimpleWorld("skyblock");
        service = new AcidIslandService(new AcidIslandsPort() {
            @Override
            public Map<IslandId, Integer> findAll() {
                return Map.copyOf(recorded);
            }

            @Override
            public java.util.OptionalInt find(IslandId islandId) {
                Integer level = recorded.get(islandId);
                return level == null ? java.util.OptionalInt.empty() : java.util.OptionalInt.of(level);
            }

            @Override
            public void add(IslandId islandId, int seaLevel) {
                recorded.putIfAbsent(islandId, seaLevel);
            }
        });
        scheduler = mock(SchedulerPort.class);
        doAnswer(call -> {
                    regions.add(call.getArgument(1) + "," + call.getArgument(2));
                    call.getArgument(3, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .onRegion(anyString(), anyInt(), anyInt(), any(Runnable.class));
        engine = new StarterSchematicEngine();
        engine.actions().register(new AcidSeaStart(service, scheduler, SEA));
    }

    @Test
    @DisplayName("The island stands in water on a floor, and its own blocks are kept")
    void theIslandStandsInItsSea() throws Exception {
        StarterPreset preset = shipped().catalogue().findById("acid_island").orElseThrow();
        IslandId island = IslandId.of(UUID.randomUUID());

        engine.start(new IslandStart(world, island, CENTER, Y, CENTER, preset)).join();

        int surface = Y - SEA.belowIsland();
        assertThat(world.getBlockAt(CENTER + 15, surface, CENTER).getType()).isEqualTo(Material.WATER);
        assertThat(world.getBlockAt(CENTER - 20, surface - 2, CENTER + 20).getType())
                .isEqualTo(Material.WATER);
        assertThat(world.getBlockAt(CENTER + 15, surface - 3, CENTER).getType()).isEqualTo(Material.SANDSTONE);
        assertThat(world.getBlockAt(CENTER + 15, surface + 1, CENTER).getType())
                .describedAs("nothing above the surface")
                .isEqualTo(Material.AIR);
        assertThat(world.getBlockAt(CENTER + 21, surface, CENTER).getType())
                .describedAs("nothing past the radius")
                .isEqualTo(Material.AIR);
        assertThat(world.getBlockAt(CENTER, Y - 2, CENTER).getType())
                .describedAs("the platform's bedrock is kept where the sea reaches it")
                .isEqualTo(Material.BEDROCK);
        assertThat(world.getBlockAt(CENTER, Y, CENTER).getType()).isEqualTo(Material.GRASS_BLOCK);
        assertThat(service.seaLevel(island)).hasValue(surface);
        assertThat(regions)
                .describedAs("each chunk of the sea is laid on its own region's thread")
                .hasSize(9);
    }

    @Test
    @DisplayName("Vents pull down and geysers throw up, laid only where the sea reaches the floor")
    void ventsAndGeysers() throws Exception {
        StarterPreset preset = shipped().catalogue().findById("acid_island").orElseThrow();
        int surface = Y - SEA.belowIsland();
        int floor = surface - SEA.depth();
        world.getBlockAt(CENTER, surface, CENTER).setType(Material.STONE);

        new AcidSeaStart(
                        service,
                        scheduler,
                        new AcidIslandConfiguration.Sea(
                                2, 3, 6, "SAND", new AcidIslandConfiguration.Vents(1.0, 3, List.of()), 0))
                .apply(new IslandStart(world, IslandId.of(UUID.randomUUID()), CENTER, Y, CENTER, preset));

        assertThat(world.getBlockAt(CENTER + 3, floor, CENTER).getType()).isEqualTo(AcidSeaStart.VENT);
        assertThat(world.getBlockAt(CENTER + 3, surface, CENTER).getBlockData())
                .isInstanceOfSatisfying(
                        BubbleColumn.class,
                        column -> assertThat(column.isDrag()).isTrue());
        assertThat(world.getBlockAt(CENTER, floor, CENTER).getType())
                .describedAs("a column the island stands in is no vent, and sand, which falls, is sandstone")
                .isEqualTo(Material.SANDSTONE);

        new AcidSeaStart(
                        service,
                        scheduler,
                        new AcidIslandConfiguration.Sea(2, 3, 6, "SAND", AcidIslandConfiguration.Vents.NONE, 1.0))
                .apply(new IslandStart(world, IslandId.of(UUID.randomUUID()), CENTER + 40, Y, CENTER, preset));

        assertThat(world.getBlockAt(CENTER + 43, floor, CENTER).getType()).isEqualTo(AcidSeaStart.GEYSER);
        assertThat(world.getBlockAt(CENTER + 43, surface, CENTER).getBlockData())
                .isInstanceOfSatisfying(
                        BubbleColumn.class,
                        column -> assertThat(column.isDrag()).isFalse());
    }

    @Test
    @DisplayName(
            "The sea is painted with its biome from the floor to the sky where it fills a column, and each chunk sent again")
    void theSeaIsPainted() throws Exception {
        StarterPreset preset = shipped().catalogue().findById("acid_island").orElseThrow();
        int surface = Y - SEA.belowIsland();
        int floor = surface - SEA.depth();
        org.bukkit.block.Biome before = cell(CENTER + 8, surface, CENTER);
        for (int y = floor; y <= Y + 20; y++) {
            world.getBlockAt(CENTER, y, CENTER).setType(Material.STONE);
        }
        List<String> sent = new java.util.ArrayList<>();

        new AcidSeaStart(
                        service,
                        scheduler,
                        new AcidIslandConfiguration.Sea(2, 3, 20, "SANDSTONE"),
                        org.bukkit.block.Biome.WARM_OCEAN,
                        (painted, chunkX, chunkZ) -> sent.add(chunkX + "," + chunkZ),
                        List.of())
                .apply(new IslandStart(world, IslandId.of(UUID.randomUUID()), CENTER, Y, CENTER, preset));

        assertThat(before).isNotEqualTo(org.bukkit.block.Biome.WARM_OCEAN);
        assertThat(cell(CENTER + 8, surface, CENTER)).isEqualTo(org.bukkit.block.Biome.WARM_OCEAN);
        assertThat(cell(CENTER + 8, floor, CENTER))
                .describedAs("the floor")
                .isEqualTo(org.bukkit.block.Biome.WARM_OCEAN);
        assertThat(cell(CENTER + 8, world.getMaxHeight() - 1, CENTER))
                .describedAs("up to the sky")
                .isEqualTo(org.bukkit.block.Biome.WARM_OCEAN);
        assertThat(cell(CENTER + 8, floor - 8, CENTER))
                .describedAs("below the floor")
                .isEqualTo(before);
        assertThat(cell(CENTER - 20, surface, CENTER - 20))
                .describedAs("the sea's corner")
                .isEqualTo(org.bukkit.block.Biome.WARM_OCEAN);
        assertThat(cell(CENTER, surface, CENTER))
                .describedAs("the island's column")
                .isEqualTo(before);
        assertThat(cell(CENTER + 24, surface, CENTER))
                .describedAs("past the sea")
                .isEqualTo(before);
        int across = ((CENTER + 20) >> 4) - ((CENTER - 20) >> 4) + 1;
        int chunks = across * across;
        assertThat(sent).hasSize(chunks).doesNotHaveDuplicates();

        new AcidSeaStart(service, scheduler, new AcidIslandConfiguration.Sea(2, 3, 6, "SANDSTONE"))
                .apply(new IslandStart(world, IslandId.of(UUID.randomUUID()), CENTER + 80, Y, CENTER, preset));
        assertThat(cell(CENTER + 84, surface, CENTER))
                .describedAs("no biome asked for")
                .isEqualTo(before);
    }

    /** The biome of the cell a block stands in, read at the cell's corner, where the server keeps it. */
    private org.bukkit.block.Biome cell(int x, int y, int z) {
        return world.getBiome(Math.floorDiv(x, 4) * 4, Math.floorDiv(y, 4) * 4, Math.floorDiv(z, 4) * 4);
    }

    @Test
    @DisplayName("The look is read with its colours, and one that cannot be is the shipped")
    void theLook() throws Exception {
        assertThat(AcidIslandConfiguration.look(HoconConfigurationLoader.builder()
                        .buildAndLoadString("biome = \" Minecraft:Warm_Ocean \", water = \"#AABBCC\"")))
                .isEqualTo(new AcidIslandConfiguration.Look(
                        "minecraft:warm_ocean", "#aabbcc", "#565f6b", "#fff3a8", "#e8ecf5"));
        assertThat(AcidIslandConfiguration.look(
                        HoconConfigurationLoader.builder().buildAndLoadString("biome = \"\"")))
                .extracting(AcidIslandConfiguration.Look::paints)
                .isEqualTo(false);
        assertThat(AcidIslandConfiguration.Look.SHIPPED.paints()).isTrue();
        for (String wrong : List.of(
                "water = \"green\"",
                "fog = \"#12345\"",
                "sky = \"#1234567\"",
                "water-fog = \"#gggggg\"",
                "biome = \"no key here\"")) {
            assertThat(AcidIslandConfiguration.look(
                            HoconConfigurationLoader.builder().buildAndLoadString(wrong)))
                    .describedAs(wrong)
                    .isEqualTo(AcidIslandConfiguration.Look.SHIPPED);
        }
        assertThat(AcidIslandConfiguration.load(HoconConfigurationLoader.builder()
                                .buildAndLoadString("sea { look { sky = \"#010203\" } }"))
                        .look()
                        .sky())
                .isEqualTo("#010203");
    }

    @Test
    @DisplayName(
            "The floor is as thick as written, of the blocks written by their weight, and rises in mounds under water")
    void theFloorIsVaried() throws Exception {
        StarterPreset preset = shipped().catalogue().findById("acid_island").orElseThrow();
        AcidIslandConfiguration.Sea sea = new AcidIslandConfiguration.Sea(
                2,
                4,
                40,
                "SANDSTONE",
                new AcidIslandConfiguration.Vents(0.3, 3, List.of()),
                0,
                new AcidIslandConfiguration.Bed(List.of("tuff 3", "CLAY", "sand 5", "no_such 2", "stone x"), 3, 9));
        int surface = Y - sea.belowIsland();
        int bottom = surface - sea.depth() + 1;

        new AcidSeaStart(service, scheduler, sea, null, (painted, chunkX, chunkZ) -> {}, List.of())
                .apply(new IslandStart(world, IslandId.of(UUID.randomUUID()), CENTER, Y, CENTER, preset));

        Map<Material, Integer> tops = new HashMap<>();
        Set<Integer> rises = new HashSet<>();
        int vents = 0;
        for (int x = CENTER - 40; x <= CENTER + 40; x++) {
            for (int z = CENTER - 40; z <= CENTER + 40; z++) {
                Material top = world.getBlockAt(x, bottom - 1, z).getType();
                int rise = 0;
                while (world.getBlockAt(x, bottom + rise, z).getType() == top) {
                    rise++;
                }
                if (top == AcidSeaStart.VENT) {
                    vents++;
                    assertThat(world.getBlockAt(x, bottom - 2, z).getType())
                            .describedAs("a vent tops a floor column")
                            .isIn(Material.TUFF, Material.CLAY);
                    continue;
                }
                tops.merge(top, 1, Integer::sum);
                rises.add(rise);
                assertThat(world.getBlockAt(x, bottom - 3, z).getType()).isEqualTo(top);
                assertThat(world.getBlockAt(x, bottom - 4, z).getType())
                        .describedAs("nothing below the floor")
                        .isEqualTo(Material.AIR);
                assertThat(world.getBlockAt(x, surface, z).getType())
                        .describedAs("water always stands above a mound")
                        .isIn(Material.WATER, Material.BUBBLE_COLUMN);
                int along = rise;
                if (x > CENTER - 40) {
                    int west = 0;
                    Material westTop = world.getBlockAt(x - 1, bottom - 1, z).getType();
                    while (world.getBlockAt(x - 1, bottom + west, z).getType() == westTop) {
                        west++;
                    }
                    assertThat(Math.abs(along - west))
                            .describedAs("mounds rise gently")
                            .isLessThanOrEqualTo(1);
                }
            }
        }
        assertThat(tops.keySet()).containsExactlyInAnyOrder(Material.TUFF, Material.CLAY);
        assertThat(tops.getOrDefault(Material.TUFF, 0)).isGreaterThan(tops.getOrDefault(Material.CLAY, 0) * 2);
        assertThat(rises).describedAs("clamped below the surface").containsExactlyInAnyOrder(0, 1, 2, 3);
        assertThat(vents).isPositive();
    }

    @Test
    @DisplayName("A floor of no block that stands is the sea's own floor, and the same island lays the same floor")
    void aFloorWithNoBlocks() throws Exception {
        StarterPreset preset = shipped().catalogue().findById("acid_island").orElseThrow();
        AcidIslandConfiguration.Sea sea = new AcidIslandConfiguration.Sea(
                2,
                4,
                12,
                "CLAY",
                AcidIslandConfiguration.Vents.NONE,
                0,
                new AcidIslandConfiguration.Bed(List.of("gravel 4"), 1, 0));
        int bottom = Y - 2 - 4 + 1;
        new AcidSeaStart(service, scheduler, sea, null, (painted, chunkX, chunkZ) -> {}, List.of())
                .apply(new IslandStart(world, IslandId.of(UUID.randomUUID()), CENTER, Y, CENTER, preset));
        assertThat(world.getBlockAt(CENTER + 9, bottom - 1, CENTER).getType()).isEqualTo(Material.CLAY);
        assertThat(world.getBlockAt(CENTER + 9, bottom, CENTER).getType()).isEqualTo(Material.WATER);

        IslandId same = IslandId.of(UUID.randomUUID());
        for (AcidIslandConfiguration.Bed bed : List.of(
                new AcidIslandConfiguration.Bed(AcidIslandConfiguration.Bed.SHIPPED.blocks(), 3, 0),
                new AcidIslandConfiguration.Bed(List.of("TUFF"), 3, 2))) {
            AcidIslandConfiguration.Sea varied =
                    new AcidIslandConfiguration.Sea(2, 4, 12, "CLAY", AcidIslandConfiguration.Vents.NONE, 0, bed);
            World again = server.addSimpleWorld("again" + bed.mounds());
            World other = server.addSimpleWorld("other" + bed.mounds());
            int at = CENTER + 200 * (bed.mounds() + 1);
            for (World each : List.of(world, again)) {
                new AcidSeaStart(service, scheduler, varied, null, (painted, chunkX, chunkZ) -> {}, List.of())
                        .apply(new IslandStart(each, same, at, Y, CENTER, preset));
            }
            new AcidSeaStart(service, scheduler, varied, null, (painted, chunkX, chunkZ) -> {}, List.of())
                    .apply(new IslandStart(other, IslandId.of(UUID.randomUUID()), at, Y, CENTER, preset));
            boolean differs = false;
            for (int dx = -12; dx <= 12; dx++) {
                for (int dz = -12; dz <= 12; dz += 3) {
                    for (int y = bottom - 3; y <= bottom + 2; y++) {
                        Material laid =
                                world.getBlockAt(at + dx, y, CENTER + dz).getType();
                        assertThat(again.getBlockAt(at + dx, y, CENTER + dz).getType())
                                .isEqualTo(laid);
                        differs |= other.getBlockAt(at + dx, y, CENTER + dz).getType() != laid;
                    }
                }
            }
            assertThat(differs)
                    .describedAs("another island lays another floor: " + bed)
                    .isTrue();
        }
        assertThat(SeaBed.read(
                        new AcidIslandConfiguration.Bed(List.of("stone x", "stone 0", "sand", "tuff 2"), 1, 0),
                        Material.CLAY))
                .containsExactly(new SeaBed.Weighted(Material.TUFF, 2));
    }

    @Test
    @DisplayName(
            "A structure is buried in the floor as deep as written, fills the floor's blocks, and never hangs below it")
    void aStructureIsBuried() throws Exception {
        StarterPreset preset = shipped().catalogue().findById("acid_island").orElseThrow();
        AcidIslandConfiguration.Sea sea = new AcidIslandConfiguration.Sea(
                2,
                4,
                40,
                "SANDSTONE",
                AcidIslandConfiguration.Vents.NONE,
                0,
                new AcidIslandConfiguration.Bed(List.of("TUFF"), 3, 0));
        int bottom = Y - 2 - 4 + 1;
        List<String> asked = new java.util.ArrayList<>();
        Map<String, String> given = new HashMap<>();
        com.uxplima.uxmskyblock.bukkit.poseidon.WreckTemplates templates = key -> {
            asked.add(key);
            List<com.uxplima.uxmskyblock.bukkit.poseidon.WreckTemplate.Piece> pieces = new java.util.ArrayList<>();
            for (int y = 0; y < 8; y++) {
                pieces.add(new com.uxplima.uxmskyblock.bukkit.poseidon.WreckTemplate.Piece(
                        0, y, 0, Material.CRACKED_STONE_BRICKS.createBlockData(), null));
            }
            pieces.add(new com.uxplima.uxmskyblock.bukkit.poseidon.WreckTemplate.Piece(
                    1, 2, 0, Material.CHEST.createBlockData(), null));
            return java.util.Optional.of(new com.uxplima.uxmskyblock.bukkit.poseidon.WreckTemplate(1, 1, pieces));
        };
        var buried = AcidSeaStart.buried(
                sea,
                new AcidIslandConfiguration.Buried(
                        2,
                        Map.of(
                                "p",
                                new com.uxplima.uxmskyblock.bukkit.config.PoseidonConfiguration.Wreck(
                                        List.of("test:pillar"), 4, 20, List.of("test:loot")),
                                "a",
                                new com.uxplima.uxmskyblock.bukkit.config.PoseidonConfiguration.Wreck(
                                        List.of("test:first"), 1, 30, List.of()))),
                templates,
                (block, table, seed) -> given.put(block.getX() + "," + block.getY() + "," + block.getZ(), table),
                scheduler);
        AcidSeaStart start = new AcidSeaStart(service, scheduler, sea, null, (painted, chunkX, chunkZ) -> {}, buried);
        IslandStart island = new IslandStart(world, IslandId.of(UUID.randomUUID()), CENTER, Y, CENTER, preset);

        assertThat(start.reach(island)).hasSize(3);
        start.apply(island);

        assertThat(asked.getFirst())
                .describedAs("the groups in the order of their names")
                .isEqualTo("test:first");
        int pillars = 0;
        for (int x = CENTER - 40; x <= CENTER + 40; x++) {
            for (int z = CENTER - 40; z <= CENTER + 40; z++) {
                if (world.getBlockAt(x, bottom, z).getType() != Material.CRACKED_STONE_BRICKS) {
                    continue;
                }
                pillars++;
                assertThat(world.getBlockAt(x, bottom - 2, z).getType())
                        .describedAs("sunk two into the floor")
                        .isEqualTo(Material.CRACKED_STONE_BRICKS);
                assertThat(world.getBlockAt(x, bottom - 3, z).getType())
                        .describedAs("the floor's bottom below the sink is kept")
                        .isEqualTo(Material.TUFF);
                assertThat(world.getBlockAt(x, bottom + 5, z).getType()).isEqualTo(Material.CRACKED_STONE_BRICKS);
                assertThat(world.getBlockAt(x, bottom - 4, z).getType()).isEqualTo(Material.AIR);
                assertThat(world.getBlockAt(x + 1, bottom, z).getType())
                        .describedAs("a chest in the water")
                        .isEqualTo(Material.CHEST);
            }
        }
        assertThat(pillars).isEqualTo(5);
        assertThat(given).hasSize(4).containsValue("test:loot");

        // A structure sunk deeper than the floor is thick stops at its bottom.
        var deep = AcidSeaStart.buried(
                sea,
                new AcidIslandConfiguration.Buried(
                        5,
                        Map.of(
                                "deep",
                                new com.uxplima.uxmskyblock.bukkit.config.PoseidonConfiguration.Wreck(
                                        List.of("test:pillar"), 1, 20, List.of()))),
                templates,
                (block, table, seed) -> {},
                scheduler);
        new AcidSeaStart(service, scheduler, sea, null, (painted, chunkX, chunkZ) -> {}, deep)
                .apply(new IslandStart(world, IslandId.of(UUID.randomUUID()), CENTER + 300, Y, CENTER, preset));
        int found = 0;
        for (int x = CENTER + 260; x <= CENTER + 340; x++) {
            for (int z = CENTER - 40; z <= CENTER + 40; z++) {
                if (world.getBlockAt(x, bottom - 3, z).getType() == Material.CRACKED_STONE_BRICKS) {
                    found++;
                    assertThat(world.getBlockAt(x, bottom - 4, z).getType()).isEqualTo(Material.AIR);
                    assertThat(world.getBlockAt(x, bottom + 2, z).getType())
                            .describedAs("its top, sunk five, is the eighth layer: two above the bottom water")
                            .isEqualTo(Material.CRACKED_STONE_BRICKS);
                    assertThat(world.getBlockAt(x, bottom + 3, z).getType()).isEqualTo(Material.WATER);
                }
            }
        }
        assertThat(found).isEqualTo(1);
    }

    @Test
    @DisplayName("The floor and the buried structures are read from the file, and what cannot be is the shipped")
    void theFloorIsRead() throws Exception {
        AcidIslandConfiguration read = AcidIslandConfiguration.load(
                HoconConfigurationLoader.builder()
                        .buildAndLoadString(
                                "sea { bed { blocks = [\"TUFF 2\"], thickness = 4, mounds = 1 },"
                                        + " buried { sink = 3, groups { only { templates = [\"a:b\"], count = 2, distance = 9 } } } }"));
        assertThat(read.sea().bed()).isEqualTo(new AcidIslandConfiguration.Bed(List.of("TUFF 2"), 4, 1));
        assertThat(read.buried().sink()).isEqualTo(3);
        assertThat(read.buried().groups()).containsOnlyKeys("only");
        assertThat(read.buried().groups())
                .hasEntrySatisfying("only", group -> assertThat(group.count()).isEqualTo(2));
        AcidIslandConfiguration partly = AcidIslandConfiguration.load(HoconConfigurationLoader.builder()
                .buildAndLoadString("sea { buried { groups { ruins { count = 2 } } } }"));
        assertThat(partly.buried().groups())
                .hasEntrySatisfying(
                        "ruins",
                        group -> assertThat(group.templates())
                                .describedAs("what a shipped group leaves out comes from it")
                                .contains("minecraft:underwater_ruin/cracked_8"));
        AcidIslandConfiguration none = AcidIslandConfiguration.load(
                HoconConfigurationLoader.builder().buildAndLoadString("sea { buried { sink = 1 } }"));
        assertThat(none.buried().groups()).isEqualTo(AcidIslandConfiguration.Buried.SHIPPED.groups());
        assertThat(AcidIslandConfiguration.load(
                                HoconConfigurationLoader.builder().buildAndLoadString("sea { buried { sink = 17 } }"))
                        .buried())
                .isEqualTo(AcidIslandConfiguration.Buried.SHIPPED);
        assertThat(AcidIslandConfiguration.load(
                                HoconConfigurationLoader.builder().buildAndLoadString("sea { bed { thickness = 0 } }"))
                        .sea())
                .isEqualTo(AcidIslandConfiguration.Sea.SHIPPED);
    }

    @Test
    @DisplayName("The preset plays AcidIsland and lays the platform before the sea")
    void thePresetIsAcidIsland() throws Exception {
        StarterPreset preset = shipped().catalogue().findById("acid_island").orElseThrow();

        assertThat(preset.mode()).isEqualTo(GameModeType.ACID_ISLAND);
        assertThat(preset.start()).containsExactly(StarterPreset.PLATFORM, AcidSeaStart.ACTION);
        assertThat(shipped()
                        .startableWith(new StarterSchematicEngine().actions()::knowsAll)
                        .catalogue()
                        .findById("acid_island"))
                .describedAs("not offered where AcidIsland is off")
                .isEmpty();
    }

    @Test
    @DisplayName("The shipped file reads as the shipped numbers, and a sea without depth falls back")
    void theShippedFile() throws Exception {
        AcidIslandConfiguration file = AcidIslandConfiguration.load(
                HoconConfigurationLoader.builder().buildAndLoadString(resource("modules/acidisland.conf")));
        AcidIslandConfiguration odd = AcidIslandConfiguration.load(HoconConfigurationLoader.builder()
                .buildAndLoadString(
                        "sea { depth = 0 }\nhazard { check-every = \"500ms\", water-damage = 4, water-effects = [] }"));

        assertThat(file).isEqualTo(AcidIslandConfiguration.defaultConfiguration());
        assertThat(odd.sea()).isEqualTo(AcidIslandConfiguration.Sea.SHIPPED);
        assertThat(odd.rules().checkEvery()).isEqualTo(java.time.Duration.ofMillis(500));
        assertThat(odd.rules().waterDamage()).isEqualTo(4.0);
        assertThat(odd.waterEffects()).isEmpty();
        assertThat(odd.rules()).isNotEqualTo(AcidRules.shipped());
        assertThat(file.waterProtection()).isEqualTo(List.of("water_breathing"));
        assertThat(file.sea().vents().effects()).contains("nausea:0:6");
        assertThat(AcidIslandConfiguration.load(HoconConfigurationLoader.builder()
                                .buildAndLoadString("sea { vents { chance = 0.7 }, geysers { chance = 0.5 } }"))
                        .sea())
                .describedAs("chances that add past one are no sea")
                .isEqualTo(AcidIslandConfiguration.Sea.SHIPPED);
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
