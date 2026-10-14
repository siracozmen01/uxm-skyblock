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
import java.util.UUID;

import org.bukkit.Material;
import org.bukkit.World;

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
 * A CaveBlock island's rock holds pools: lakes of water with air above them, and pockets of lava full to the top, each
 * where the island's seed puts it, beyond the room and inside the shell.
 */
class TheRockHoldsLakesAndLavaTest extends MockBukkitHarness {

    private static final int CENTER = 5_000;
    private static final int Y = 99;
    private static final int RADIUS = 24;
    private static final int BELOW = 20;
    private static final int ABOVE = 8;
    private static final CavePlan.Shape SHAPE = new CavePlan.Shape(RADIUS, BELOW, ABOVE, 3, 0, 10, 0, 8, 6);
    private static final CaveBlockConfiguration.Pool LAKE =
            new CaveBlockConfiguration.Pool("WATER", 2, 4, 0.5, 4, 10, List.of(CaveBlockConfiguration.OVERWORLD));
    private static final CaveBlockConfiguration.Pool LAVA =
            new CaveBlockConfiguration.Pool("LAVA", 2, 2, 1, 8, 12, List.of(CaveBlockConfiguration.OVERWORLD));

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

    private World laid(String name, Map<String, CaveBlockConfiguration.Pool> pools, UUID island) {
        WorldMock world = server.addSimpleWorld(name);
        if (name.endsWith("_nether")) {
            world.setEnvironment(World.Environment.NETHER);
        }
        new CavernStart(
                        scheduler,
                        new CaveBlockConfiguration(
                                true,
                                SHAPE,
                                "BEDROCK",
                                Map.of(
                                        CaveBlockConfiguration.OVERWORLD,
                                        CaveBlockConfiguration.Palette.plain("STONE", OreTable.NONE),
                                        CaveBlockConfiguration.NETHER,
                                        CaveBlockConfiguration.Palette.plain("NETHERRACK", OreTable.NONE)),
                                Map.of(),
                                Map.of(),
                                pools))
                .apply(new IslandStart(world, IslandId.of(island), CENTER, Y, CENTER, preset()));
        return world;
    }

    /** Every block of {@code type} in the rock, as x, y, z. */
    private static List<int[]> every(World world, Material type) {
        List<int[]> found = new ArrayList<>();
        for (int x = CENTER - RADIUS; x <= CENTER + RADIUS; x++) {
            for (int z = CENTER - RADIUS; z <= CENTER + RADIUS; z++) {
                for (int y = Y - BELOW; y <= Y + ABOVE; y++) {
                    if (world.getBlockAt(x, y, z).getType() == type) {
                        found.add(new int[] {x, y, z});
                    }
                }
            }
        }
        return found;
    }

    @Test
    @DisplayName("A lake is water with air above it, beyond the room and inside the shell, at its depth")
    void aLake() {
        World world = laid("lakes", Map.of("lake", LAKE), UUID.randomUUID());
        List<int[]> water = every(world, Material.WATER);
        List<int[]> air = every(world, Material.CAVE_AIR);
        assertThat(water).isNotEmpty();
        assertThat(air).describedAs("the half above the water").isNotEmpty();
        for (int[] at : water) {
            assertThat(world.getBlockAt(at[0], at[1] - 1, at[2]).getType())
                    .describedAs("water is never above the air")
                    .isNotEqualTo(Material.CAVE_AIR);
        }
        for (int[] at : air) {
            assertThat(world.getBlockAt(at[0], at[1] + 1, at[2]).getType())
                    .describedAs("air is never below the water")
                    .isNotEqualTo(Material.WATER);
        }
        List<int[]> all = new ArrayList<>(water);
        all.addAll(air);
        for (int[] at : all) {
            int dx = at[0] - CENTER;
            int dz = at[2] - CENTER;
            assertThat(dx * dx + dz * dz).describedAs("beyond the room's reach").isGreaterThan(25);
            assertThat(Math.max(Math.abs(dx), Math.abs(dz)))
                    .describedAs("inside the shell")
                    .isLessThan(RADIUS - 1);
            assertThat(at[1]).describedAs("at its depth").isBetween(Y - 10 - 2, Y - 4 + 2);
        }
        int rows = (int) all.stream().mapToInt(at -> at[1]).distinct().count();
        assertThat(rows)
                .describedAs("half as high as it is wide, two lakes apart")
                .isLessThanOrEqualTo(10);
    }

    @Test
    @DisplayName("A lava pocket is full to the top, and every pool lies where the island's seed puts it")
    void lavaAndTheSeed() {
        World world = laid("lava", Map.of("lava", LAVA), UUID.randomUUID());
        List<int[]> lava = every(world, Material.LAVA);
        assertThat(lava).isNotEmpty();
        assertThat(every(world, Material.CAVE_AIR))
                .describedAs("no air in a full pocket")
                .isEmpty();
        for (int[] at : lava) {
            assertThat(at[1]).isBetween(Y - 12 - 1, Y - 8 + 1);
        }
        IslandStart start = start(world);
        assertThat(planned(start, LAVA, 7L)).isEqualTo(planned(start, LAVA, 7L)).isNotEqualTo(planned(start, LAVA, 8L));
    }

    @Test
    @DisplayName("A pool stays out of the dimensions it does not list, and one that cannot fit is not laid")
    void notLaid() {
        WorldMock nether = server.addSimpleWorld("pools_nether");
        nether.setEnvironment(World.Environment.NETHER);
        assertThat(planned(start(nether), LAKE, 7L)).isEmpty();
        IslandStart start = start(server.addSimpleWorld("pools_fit"));
        for (CaveBlockConfiguration.Pool unfit : List.of(
                pool("WATER", 15, 4, 10),
                pool("WATER", 2, 19, 30),
                pool("WATER", 2, -30, -7),
                pool("NOT_A_BLOCK", 2, 4, 10))) {
            assertThat(planned(start, unfit, 7L)).describedAs("%s", unfit).isEmpty();
        }
        assertThat(planned(start, pool("WATER", 7, 4, 10), 7L))
                .describedAs("just fits between the room and the shell")
                .hasSize(1);
        CavernStart.Pocket deepest = planned(start, pool("LAVA", 2, 16, 16), 7L).getFirst();
        assertThat(deepest.y() - deepest.high())
                .describedAs("just above the floor")
                .isEqualTo(Y - BELOW + 3);
        CavernStart.Pocket highest = planned(start, pool("LAVA", 2, -4, -4), 7L).getFirst();
        assertThat(highest.y() + highest.high())
                .describedAs("just below the roof")
                .isEqualTo(Y + ABOVE - 3);
    }

    private static CaveBlockConfiguration.Pool pool(String block, int radius, int depthFrom, int depthTo) {
        return new CaveBlockConfiguration.Pool(
                block, 1, radius, 0.5, depthFrom, depthTo, List.of(CaveBlockConfiguration.OVERWORLD));
    }

    private IslandStart start(World world) {
        return new IslandStart(world, IslandId.of(UUID.randomUUID()), CENTER, Y, CENTER, preset());
    }

    @Test
    @DisplayName("Pools lie anywhere from the room's reach to three inside the shell, at every depth they allow")
    void wherePoolsLie() {
        IslandStart start = start(server.addSimpleWorld("pools_planned"));
        List<CavernStart.Pocket> lakes = planned(
                start,
                new CaveBlockConfiguration.Pool(
                        "WATER", 400, 4, 0.5, 4, 10, List.of(CaveBlockConfiguration.OVERWORLD)));
        // From the room's 3, three blocks and the lake's 4 out; to the rock's 24, three blocks and 4 in.
        assertThat(lakes.stream()
                        .mapToInt(CavernStart.Pocket::distance)
                        .distinct()
                        .sorted()
                        .toArray())
                .containsExactly(10, 11, 12, 13, 14, 15, 16, 17);
        assertThat(lakes.stream()
                        .mapToInt(pocket -> Y - pocket.y())
                        .distinct()
                        .sorted()
                        .toArray())
                .containsExactly(4, 5, 6, 7, 8, 9, 10);
        // Below 20, three blocks and the lake's 2 up, is the deepest; above 8, three and 2 down, the highest.
        assertThat(planned(
                                start,
                                new CaveBlockConfiguration.Pool(
                                        "WATER", 400, 4, 0.5, -30, 30, List.of(CaveBlockConfiguration.OVERWORLD)))
                        .stream()
                        .mapToInt(pocket -> Y - pocket.y())
                        .summaryStatistics())
                .returns(-3, java.util.IntSummaryStatistics::getMin)
                .returns(15, java.util.IntSummaryStatistics::getMax);
        for (CavernStart.Pocket lake : lakes) {
            assertThat(lake.high()).isEqualTo(2);
            assertThat(lake.surface())
                    .describedAs("half full: three rows of five")
                    .isEqualTo(lake.y());
            assertThat(Math.round(Math.hypot(lake.x() - CENTER, lake.z() - CENTER)))
                    .isBetween((long) lake.distance() - 1, (long) lake.distance() + 1);
        }
        CavernStart.Pocket shape = new CavernStart.Pocket(10, 0, 0, 0, 4, 2, Material.WATER, 0);
        assertThat(shape.holds(4, 0, 0)).describedAs("its rim").isTrue();
        assertThat(shape.holds(0, 2, 0)).describedAs("its top").isTrue();
        assertThat(shape.holds(5, 0, 0)).isFalse();
        assertThat(shape.holds(0, 3, 0)).isFalse();
        assertThat(shape.holds(3, 1, 0)).isTrue();
        assertThat(shape.holds(4, 1, 0)).isFalse();
        CavernStart.Pocket full = planned(start, LAVA).getFirst();
        assertThat(full.surface()).describedAs("full to the top").isEqualTo(full.y() + full.high());
        CavernStart.Pocket empty = planned(
                        start,
                        new CaveBlockConfiguration.Pool(
                                "WATER", 1, 4, 0, 4, 10, List.of(CaveBlockConfiguration.OVERWORLD)))
                .getFirst();
        assertThat(empty.surface()).describedAs("nothing but air").isEqualTo(empty.y() - empty.high() - 1);
    }

    private List<CavernStart.Pocket> planned(IslandStart start, CaveBlockConfiguration.Pool pool) {
        return planned(start, pool, 7L);
    }

    private List<CavernStart.Pocket> planned(IslandStart start, CaveBlockConfiguration.Pool pool, long seed) {
        return new CavernStart(
                        scheduler,
                        new CaveBlockConfiguration(
                                true,
                                SHAPE,
                                "BEDROCK",
                                Map.of(
                                        CaveBlockConfiguration.OVERWORLD,
                                        CaveBlockConfiguration.Palette.plain("STONE", OreTable.NONE)),
                                Map.of(),
                                Map.of(),
                                Map.of("pool", pool)))
                .pockets(start.world(), start, seed);
    }

    @Test
    @DisplayName("The pools are read from the file, by dimension and in the order of their names")
    void theFile() throws Exception {
        CaveBlockConfiguration shipped = CaveBlockConfiguration.load(
                HoconConfigurationLoader.builder().buildAndLoadString(resource("modules/caveblock.conf")));
        assertThat(shipped.pools()).isEqualTo(CaveBlockConfiguration.SHIPPED_POOLS);
        assertThat(shipped.poolsOf(CaveBlockConfiguration.OVERWORLD))
                .containsExactly(
                        CaveBlockConfiguration.SHIPPED_POOLS.get("lake"),
                        CaveBlockConfiguration.SHIPPED_POOLS.get("lava"));
        assertThat(shipped.poolsOf(CaveBlockConfiguration.NETHER))
                .containsExactly(CaveBlockConfiguration.SHIPPED_POOLS.get("lava"));
        CaveBlockConfiguration written = CaveBlockConfiguration.load(HoconConfigurationLoader.builder()
                .buildAndLoadString("pools { lava { count = 0 }, Slime { block = \" honey_block \", fill = 1,"
                        + " dimensions = [\" The_End \"] }, lake { fill = 2 } }"));
        assertThat(written.pools()).containsKeys("lava", "slime").doesNotContainKey("lake");
        assertThat(Objects.requireNonNull(written.pools().get("lava")).count()).isZero();
        assertThat(written.poolsOf(CaveBlockConfiguration.END))
                .containsExactly(new CaveBlockConfiguration.Pool(
                        "HONEY_BLOCK", 1, 3, 1, 2, 16, List.of(CaveBlockConfiguration.END)));
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
