package com.uxplima.uxmskyblock.bukkit.cave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

/** A CaveBlock island's ores are richer further down, and an ore written to begin some way down is found only there. */
class TheOresAreRicherFurtherDownTest extends MockBukkitHarness {

    private static final int CENTER = 5_000;
    private static final int Y = 99;
    private static final int RADIUS = 12;
    private static final int BELOW = 20;
    private static final int ABOVE = 8;
    private static final CavePlan.Shape SHAPE = new CavePlan.Shape(RADIUS, BELOW, ABOVE, 3, 0, 10, 0, 8, 6);

    @Test
    @DisplayName("Further down the ores lie thicker, and the diamond is found only from where it begins")
    void richerFurtherDown() throws Exception {
        SchedulerPort scheduler = mock(SchedulerPort.class);
        doAnswer(call -> {
                    call.getArgument(3, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .onRegion(anyString(), anyInt(), anyInt(), any(Runnable.class));
        World world = server.addSimpleWorld("ores");
        new CavernStart(
                        scheduler,
                        new CaveBlockConfiguration(
                                true,
                                SHAPE,
                                "BEDROCK",
                                Map.of(
                                        CaveBlockConfiguration.OVERWORLD,
                                        new CaveBlockConfiguration.Palette(
                                                "STONE",
                                                new OreTable(List.of(
                                                        new OreTable.Ore("COAL_ORE", 0.05),
                                                        new OreTable.Ore("DIAMOND_ORE", 0.05, 10))),
                                                "STONE",
                                                Integer.MAX_VALUE,
                                                OreTable.NONE,
                                                3)),
                                Map.of(),
                                Map.of(),
                                Map.of()))
                .apply(new IslandStart(
                        world,
                        IslandId.of(UUID.randomUUID()),
                        CENTER,
                        Y,
                        CENTER,
                        PresetConfiguration.load(HoconConfigurationLoader.builder()
                                        .buildAndLoadString(resource("modules/presets.conf")))
                                .catalogue()
                                .findById("caveblock")
                                .orElseThrow()));
        int high = 0;
        int low = 0;
        for (int depth = -ABOVE + 1; depth < BELOW; depth++) {
            int coal = count(world, Y - depth, Material.COAL_ORE);
            int diamond = count(world, Y - depth, Material.DIAMOND_ORE);
            if (depth < 10) {
                assertThat(diamond).describedAs("no diamond %s down", depth).isZero();
            }
            if (depth >= -ABOVE + 1 && depth < -ABOVE + 6) {
                high += coal;
            }
            if (depth >= BELOW - 5) {
                low += coal;
                assertThat(diamond).describedAs("diamond %s down", depth).isPositive();
            }
        }
        // Above the arrival a coal is one in twenty; near the floor about three and a half times as many.
        assertThat(low).describedAs("the floor's coal against the roof's").isGreaterThan(2 * high);
        assertThat(high).isPositive();
    }

    @Test
    @DisplayName("The richness rises evenly from the arrival to the floor, and is read from the file")
    void theRichness() throws Exception {
        CaveBlockConfiguration.Palette rich = new CaveBlockConfiguration.Palette(
                "STONE", OreTable.NONE, "STONE", Integer.MAX_VALUE, OreTable.NONE, 1);
        assertThat(rich.scaleAt(-3, 20)).isEqualTo(1);
        assertThat(rich.scaleAt(0, 20)).isEqualTo(1);
        assertThat(rich.scaleAt(10, 20)).isEqualTo(1.5);
        assertThat(rich.scaleAt(20, 20)).isEqualTo(2);
        assertThat(rich.scaleAt(30, 20)).isEqualTo(2);
        assertThat(new CaveBlockConfiguration.Palette("STONE", OreTable.NONE, "STONE", 1, OreTable.NONE).richer())
                .isZero();

        CaveBlockConfiguration shipped = CaveBlockConfiguration.load(
                HoconConfigurationLoader.builder().buildAndLoadString(resource("modules/caveblock.conf")));
        assertThat(shipped.palettes())
                .isEqualTo(CaveBlockConfiguration.defaultConfiguration().palettes());
        assertThat(shipped.palette(CaveBlockConfiguration.OVERWORLD).richer()).isEqualTo(1);
        assertThat(shipped.palette(CaveBlockConfiguration.OVERWORLD).deepOres().ores())
                .contains(new OreTable.Ore("DEEPSLATE_DIAMOND_ORE", 0.0015, 16));
        CaveBlockConfiguration written = CaveBlockConfiguration.load(HoconConfigurationLoader.builder()
                .buildAndLoadString("palettes { overworld { richer = 2.5 }, the_nether { richer = -1 },"
                        + " the_end { rock = OBSIDIAN, richer = 4 } }"));
        assertThat(written.palette(CaveBlockConfiguration.OVERWORLD).richer()).isEqualTo(2.5);
        assertThat(written.palette(CaveBlockConfiguration.NETHER))
                .describedAs("a bad richness keeps the shipped rock")
                .isEqualTo(shipped.palette(CaveBlockConfiguration.NETHER));
        assertThat(written.palette(CaveBlockConfiguration.END).richer()).isEqualTo(4);
    }

    private static int count(World world, int y, Material type) {
        int found = 0;
        for (int x = CENTER - RADIUS + 1; x < CENTER + RADIUS; x++) {
            for (int z = CENTER - RADIUS + 1; z < CENTER + RADIUS; z++) {
                if (world.getBlockAt(x, y, z).getType() == type) {
                    found++;
                }
            }
        }
        return found;
    }

    private String resource(String path) throws Exception {
        try (InputStream in = Objects.requireNonNull(getClass().getClassLoader().getResourceAsStream(path))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
