package com.uxplima.uxmskyblock.bukkit.cave;

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
import java.util.Optional;
import java.util.UUID;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.CreatureSpawner;

import com.uxplima.uxmlib.schematic.Schematic;
import com.uxplima.uxmskyblock.bukkit.config.CaveBlockConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.PresetConfiguration;
import com.uxplima.uxmskyblock.bukkit.poseidon.WreckTemplate;
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
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

/**
 * The rock of a CaveBlock island holds structures laid after it: a dungeon, mineshafts, a geode and a trial chamber as
 * shipped, each kind spread around the island at its distance and depth, carving its hollow, keeping the shell, its
 * chests looted and its spawners set.
 */
class TheRockHoldsItsStructuresTest extends MockBukkitHarness {

    private static final int CENTER = 5_000;
    private static final int Y = 99;
    private static final CavePlan.Shape SHAPE = new CavePlan.Shape(16, 12, 8, 3, 0, 10, 0, 8, 6);

    private final List<Runnable> queued = new ArrayList<>();
    private final List<String> looted = new ArrayList<>();

    @SuppressWarnings("NullAway.Init")
    private World world;

    @SuppressWarnings("NullAway.Init")
    private SchedulerPort scheduler;

    @BeforeEach
    void setUpTheRock() {
        world = server.addSimpleWorld("caves");
        scheduler = mock(SchedulerPort.class);
        doAnswer(call -> queued.add(call.getArgument(3, Runnable.class)))
                .when(scheduler)
                .onRegion(anyString(), anyInt(), anyInt(), any(Runnable.class));
    }

    /** A 3 by 3 by 3 room: stone walls round a hollow, a chest and a spawner on its floor, a block of the template's air. */
    private static WreckTemplate room() {
        List<WreckTemplate.Piece> pieces = new ArrayList<>();
        for (int x = 0; x < 3; x++) {
            for (int y = 0; y < 3; y++) {
                for (int z = 0; z < 3; z++) {
                    boolean inside = x == 1 && z == 1 && y == 1;
                    pieces.add(new WreckTemplate.Piece(
                            x,
                            y,
                            z,
                            (inside ? Material.CAVE_AIR : Material.MOSSY_COBBLESTONE).createBlockData(),
                            null));
                }
            }
        }
        pieces.set(1 * 9 + 0 * 3 + 0, new WreckTemplate.Piece(1, 0, 0, Material.CHEST.createBlockData(), null));
        pieces.set(0 * 9 + 0 * 3 + 1, new WreckTemplate.Piece(0, 0, 1, Material.SPAWNER.createBlockData(), null));
        pieces.set(2 * 9 + 2 * 3 + 2, new WreckTemplate.Piece(2, 2, 2, Material.AIR.createBlockData(), null));
        pieces.set(1 * 9 + 0 * 3 + 2, new WreckTemplate.Piece(1, 0, 2, Material.BARREL.createBlockData(), null));
        return new WreckTemplate(3, 3, pieces);
    }

    private CaveStructures structures(Map<String, CaveBlockConfiguration.Structure> kinds) {
        return new CaveStructures(
                kinds,
                SHAPE,
                key -> key.equals("room") ? Optional.of(room()) : Optional.empty(),
                (block, table, seed) -> looted.add(table),
                scheduler);
    }

    private static CaveBlockConfiguration.Structure kind(int count, int distance, int depth) {
        return new CaveBlockConfiguration.Structure(
                List.of("room"), count, distance, depth, List.of("a", "b"), "zombie");
    }

    private IslandStart start(UUID island) {
        return new IslandStart(world, IslandId.of(island), CENTER, Y, CENTER, preset());
    }

    @Test
    @DisplayName("Each kind is laid around the island at its distance and depth, replacing rock, carving its hollow")
    void laid() {
        fillRock(Material.STONE);
        UUID island = UUID.randomUUID();
        assertThat(structures(Map.of("rooms", kind(2, 8, 4))).lay(start(island), Material.BEDROCK))
                .isEqualTo(2);
        queued.forEach(Runnable::run);

        List<int[]> rooms = roomsFound(Y - 4);
        assertThat(rooms).hasSize(2);
        for (int[] at : rooms) {
            double off = Math.hypot(at[0] + 1 - CENTER, at[1] + 1 - CENTER);
            assertThat(off).describedAs("its middle at the distance").isBetween(7.0, 9.0);
            assertThat(world.getBlockAt(at[0] + 1, Y - 3, at[1] + 1).getType()).isEqualTo(Material.CAVE_AIR);
            assertThat(world.getBlockAt(at[0] + 2, Y - 2, at[1] + 2).getType())
                    .describedAs("the template's air is a hollow")
                    .isEqualTo(Material.CAVE_AIR);
            CreatureSpawner cage =
                    (CreatureSpawner) world.getBlockAt(at[0], Y - 4, at[1] + 1).getState();
            assertThat(cage.getSpawnedType()).isEqualTo(org.bukkit.entity.EntityType.ZOMBIE);
        }
        assertThat(looted).describedAs("each container the next table").containsExactlyInAnyOrder("a", "b", "a", "b");

        // The same island is laid the same way again.
        List<int[]> before = rooms;
        fillRock(Material.STONE);
        queued.clear();
        structures(Map.of("rooms", kind(2, 8, 4))).lay(start(island), Material.BEDROCK);
        queued.forEach(Runnable::run);
        assertThat(roomsFound(Y - 4)).usingRecursiveComparison().isEqualTo(before);
    }

    @Test
    @DisplayName("The shell is kept, and a kind that would reach past it, or names nothing, is not laid")
    void notLaid() {
        fillRock(Material.BEDROCK);
        assertThat(structures(Map.of("rooms", kind(1, 0, 4))).lay(start(UUID.randomUUID()), Material.BEDROCK))
                .isEqualTo(1);
        queued.forEach(Runnable::run);
        assertThat(world.getBlockAt(CENTER - 1, Y - 4, CENTER - 1).getType())
                .describedAs("the shell is never replaced")
                .isEqualTo(Material.BEDROCK);

        CaveStructures structures = structures(Map.of(
                "far", kind(1, 25, 4),
                "deep", kind(1, 0, 12),
                "high", kind(1, 0, -6),
                "none", kind(0, 0, 4),
                "unknown", new CaveBlockConfiguration.Structure(List.of("gone"), 1, 0, 4, List.of(), ""),
                "empty", new CaveBlockConfiguration.Structure(List.of(), 1, 0, 4, List.of(), "")));
        assertThat(structures.lay(start(UUID.randomUUID()), Material.BEDROCK)).isZero();
        assertThat(structures(Map.of("deepest", kind(1, 0, 11))).lay(start(UUID.randomUUID()), Material.BEDROCK))
                .describedAs("just inside the floor")
                .isEqualTo(1);
        assertThat(structures(Map.of("edge", kind(1, 14, 4))).lay(start(UUID.randomUUID()), Material.BEDROCK))
                .describedAs("just inside the side")
                .isEqualTo(1);
        assertThat(structures(Map.of("many", kind(6, 8, 4))).lay(start(UUID.randomUUID()), Material.BEDROCK))
                .describedAs("as many as the count")
                .isEqualTo(6);
        CaveStructures sizing = structures(Map.of());
        IslandStart here = start(UUID.randomUUID());
        assertThat(sizing.fits(here, room(), CENTER + 13, Y - 4, CENTER)).isTrue();
        assertThat(sizing.fits(here, room(), CENTER + 14, Y - 4, CENTER))
                .describedAs("past the east side")
                .isFalse();
        assertThat(sizing.fits(here, room(), CENTER - 15, Y - 4, CENTER)).isTrue();
        assertThat(sizing.fits(here, room(), CENTER - 16, Y - 4, CENTER))
                .describedAs("past the west side")
                .isFalse();
        assertThat(sizing.fits(here, room(), CENTER, Y - 4, CENTER + 13)).isTrue();
        assertThat(sizing.fits(here, room(), CENTER, Y - 4, CENTER + 14))
                .describedAs("past the south side")
                .isFalse();
        assertThat(sizing.fits(here, room(), CENTER, Y - 4, CENTER - 15)).isTrue();
        assertThat(sizing.fits(here, room(), CENTER, Y - 4, CENTER - 16))
                .describedAs("past the north side")
                .isFalse();
        assertThat(structures(Map.of("rooms", kind(1, 0, 4), "more", kind(1, 3, 2)))
                        .templateKeys())
                .containsExactly("room");
    }

    @Test
    @DisplayName("Structures never stand in one another: each is turned round until it fits, or is not laid")
    void apart() {
        fillRock(Material.STONE);
        CaveStructures two = structures(Map.of("first", kind(2, 8, 4), "second", kind(2, 8, 4)));
        assertThat(two.lay(start(UUID.randomUUID()), Material.BEDROCK)).isEqualTo(4);
        queued.forEach(Runnable::run);
        assertThat(roomsFound(Y - 4)).hasSize(4);

        fillRock(Material.STONE);
        queued.clear();
        structures(Map.of("first", kind(1, 8, 4), "second", kind(1, 8, 4)))
                .lay(start(UUID.randomUUID()), Material.BEDROCK);
        queued.forEach(Runnable::run);
        List<int[]> pair = roomsFound(Y - 4);
        assertThat(pair).hasSize(2);
        assertThat(Math.hypot(pair.get(0)[0] - pair.get(1)[0], pair.get(0)[1] - pair.get(1)[1]))
                .describedAs("two share the round: they stand across the island from each other")
                .isGreaterThan(14);

        // At 15 out, a room along an axis reaches past the side; turned off it, it fits.
        for (int island = 0; island < 20; island++) {
            assertThat(structures(Map.of("edge", kind(1, 15, 4))).lay(start(UUID.randomUUID()), Material.BEDROCK))
                    .describedAs("turned round until it fits")
                    .isEqualTo(1);
        }

        fillRock(Material.STONE);
        queued.clear();
        int laid = structures(Map.of("crowd", kind(30, 8, 4))).lay(start(UUID.randomUUID()), Material.BEDROCK);
        queued.forEach(Runnable::run);
        List<int[]> rooms = roomsFound(Y - 4);
        assertThat(laid).describedAs("a round of 50 holds no 30 rooms 3 wide").isBetween(6, 29);
        assertThat(rooms).hasSize(laid);
        for (int[] one : rooms) {
            for (int[] other : rooms) {
                if (one != other) {
                    assertThat(Math.max(Math.abs(one[0] - other[0]), Math.abs(one[1] - other[1])))
                            .describedAs("rooms wall to wall or in one another")
                            .isGreaterThan(3);
                }
            }
        }
    }

    @Test
    @DisplayName("The cavern lays the rock first and the structures after it, so the rock fills no hollow")
    void theRockComesFirst() throws Exception {
        CaveBlockConfiguration config = new CaveBlockConfiguration(
                true,
                SHAPE,
                "BEDROCK",
                Map.of(CaveBlockConfiguration.OVERWORLD, CaveBlockConfiguration.Palette.plain("STONE", OreTable.NONE)),
                Map.of("rooms", kind(1, 8, 4)));
        new CavernStart(scheduler, config, structures(config.structures())).apply(start(UUID.randomUUID()));
        queued.forEach(Runnable::run);

        List<int[]> rooms = roomsFound(Y - 4);
        assertThat(rooms).hasSize(1);
        assertThat(world.getBlockAt(rooms.getFirst()[0] + 1, Y - 3, rooms.getFirst()[1] + 1)
                        .getType())
                .isEqualTo(Material.CAVE_AIR);
    }

    @Test
    @DisplayName("The shipped structures: a dungeon, a mineshaft, a geode and a trial chamber, each hollow inside")
    void theShippedLayouts() {
        Schematic dungeon = CaveLayouts.dungeon(4000);
        assertThat(dungeon.blockAt(4, 1, 4)).isEqualTo("minecraft:spawner");
        assertThat(count(dungeon, "minecraft:chest")).isEqualTo(2);
        assertThat(dungeon.blockAt(2, 2, 2)).isEqualTo(CaveLayouts.HOLLOW);
        assertThat(dungeon.blockAt(0, 2, 2)).endsWith("cobblestone");
        assertThat(dungeon.blockAt(4, 5, 4)).endsWith("cobblestone");

        Schematic mineshaft = CaveLayouts.mineshaft(4000);
        assertThat(mineshaft.length()).isEqualTo(21);
        assertThat(mineshaft.blockAt(2, 1, 5)).startsWith("minecraft:rail");
        assertThat(mineshaft.blockAt(1, 1, 4)).startsWith("minecraft:oak_fence");
        assertThat(mineshaft.blockAt(2, 2, 5)).isEqualTo(CaveLayouts.HOLLOW);
        assertThat(mineshaft.blockAt(1, 3, 2)).isEqualTo("minecraft:cobweb");
        assertThat(mineshaft.blockAt(1, 3, 3)).isEqualTo(CaveLayouts.HOLLOW);
        assertThat(mineshaft.blockAt(0, 2, 5))
                .describedAs("the rock beside it stands")
                .isEqualTo(Schematic.AIR);
        assertThat(count(mineshaft, "minecraft:chest")).isEqualTo(1);

        Schematic geode = CaveLayouts.geode(4000);
        assertThat(geode.blockAt(0, 0, 0))
                .describedAs("the rock round the sphere stands")
                .isEqualTo(Schematic.AIR);
        assertThat(geode.blockAt(0, 5, 5)).isEqualTo("minecraft:smooth_basalt");
        assertThat(geode.blockAt(5, 5, 5)).isEqualTo(CaveLayouts.HOLLOW);
        assertThat(count(geode, "minecraft:budding_amethyst")).isPositive();
        assertThat(count(geode, "minecraft:calcite")).isPositive();

        Schematic chamber = CaveLayouts.trialChamber(4000);
        assertThat(chamber.blockAt(5, 1, 5)).isEqualTo("minecraft:trial_spawner");
        assertThat(count(chamber, "minecraft:waxed_copper_bulb")).isEqualTo(4);
        assertThat(count(chamber, "minecraft:chest")).isEqualTo(1);
        assertThat(chamber.blockAt(5, 3, 5)).isEqualTo(CaveLayouts.HOLLOW);
        assertThat(chamber.blockAt(0, 3, 5)).isEqualTo("minecraft:chiseled_tuff_bricks");
        assertThat(CaveLayouts.SHIPPED)
                .containsOnlyKeys("cave_dungeon", "cave_mineshaft", "cave_geode", "cave_trial_chamber");
        for (CaveBlockConfiguration.Structure kind : CaveBlockConfiguration.SHIPPED_STRUCTURES.values()) {
            String name = kind.templates().getFirst().substring("schematic:".length());
            Schematic layout =
                    Objects.requireNonNull(CaveLayouts.SHIPPED.get(name)).apply(4000);
            int half = Math.max(layout.width(), layout.length()) / 2 + 1;
            assertThat(kind.distance() + half)
                    .describedAs("%s fits inside the shipped cave", name)
                    .isLessThan(CavePlan.Shape.SHIPPED.radius());
            assertThat(kind.depth()).isLessThan(CavePlan.Shape.SHIPPED.below());
        }
    }

    @Test
    @DisplayName("The structures are read from the file: the shipped stay, a written one wins, a bad one is left out")
    void theFile() throws Exception {
        assertThat(CaveBlockConfiguration.load(HoconConfigurationLoader.builder()
                                .buildAndLoadString(resource("modules/caveblock.conf")))
                        .structures())
                .isEqualTo(CaveBlockConfiguration.SHIPPED_STRUCTURES);
        Map<String, CaveBlockConfiguration.Structure> read = CaveBlockConfiguration.load(
                        HoconConfigurationLoader.builder()
                                .buildAndLoadString("structures { geode { count = 0 }, Vault { templates = [\"x:y\"],"
                                        + " spawner = \" Breeze \" }, dungeon { distance = -1 } }"))
                .structures();
        assertThat(read)
                .containsKeys("mineshaft", "trial_chamber", "geode", "vault")
                .doesNotContainKey("dungeon");
        assertThat(Objects.requireNonNull(read.get("geode")).count()).isZero();
        assertThat(read.get("vault"))
                .isEqualTo(new CaveBlockConfiguration.Structure(List.of("x:y"), 1, 16, 8, List.of(), "breeze"));
    }

    /** Fills the cave's volume with {@code rock}. */
    private void fillRock(Material rock) {
        for (int x = CENTER - 16; x <= CENTER + 16; x++) {
            for (int z = CENTER - 16; z <= CENTER + 16; z++) {
                for (int y = Y - 12; y <= Y + 8; y++) {
                    world.getBlockAt(x, y, z).setType(rock);
                }
            }
        }
    }

    /** The corners of the rooms whose chest stands at height {@code floor}. */
    private List<int[]> roomsFound(int floor) {
        List<int[]> found = new ArrayList<>();
        for (int x = CENTER - 16; x <= CENTER + 16; x++) {
            for (int z = CENTER - 16; z <= CENTER + 16; z++) {
                if (world.getBlockAt(x, floor, z).getType() == Material.CHEST) {
                    found.add(new int[] {x - 1, z});
                }
            }
        }
        return found;
    }

    private static int count(Schematic schematic, String prefix) {
        int found = 0;
        for (int y = 0; y < schematic.height(); y++) {
            for (int x = 0; x < schematic.width(); x++) {
                for (int z = 0; z < schematic.length(); z++) {
                    if (schematic.blockAt(x, y, z).startsWith(prefix)) {
                        found++;
                    }
                }
            }
        }
        return found;
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
