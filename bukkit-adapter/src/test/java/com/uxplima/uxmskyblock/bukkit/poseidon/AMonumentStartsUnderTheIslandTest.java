package com.uxplima.uxmskyblock.bukkit.poseidon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.bukkit.Material;
import org.bukkit.World;

import com.uxplima.uxmlib.schematic.Schematic;
import com.uxplima.uxmskyblock.bukkit.config.PoseidonConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.PresetConfiguration;
import com.uxplima.uxmskyblock.bukkit.schematic.IslandSchematics;
import com.uxplima.uxmskyblock.bukkit.schematic.IslandStart;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.gamemode.GameModeType;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.preset.StarterPreset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

/**
 * A monument start lays a sunken temple under a Poseidon island, from the schematic the plugin writes on its first
 * start and the operator may edit: a hall under the island, with a door on each side and a chest at its heart.
 */
class AMonumentStartsUnderTheIslandTest extends MockBukkitHarness {

    private static final int CENTER = 3_000;
    private static final int Y = 99;

    @TempDir
    Path folder;

    @Test
    @DisplayName("The shipped temple fits under the island: a hall open in the middle, doors, lanterns and a chest")
    void theShippedTemple() {
        Schematic temple = MonumentLayout.shipped(4000);
        int last = MonumentLayout.SIDE - 1;
        int middle = MonumentLayout.SIDE / 2;
        int roof = MonumentLayout.HEIGHT - 1;

        assertThat(temple.width()).isEqualTo(MonumentLayout.SIDE);
        assertThat(temple.length()).isEqualTo(MonumentLayout.SIDE);
        assertThat(temple.height())
                .describedAs("under the island in the shipped ocean")
                .isLessThan(PoseidonConfiguration.Ocean.SHIPPED.depth());
        assertThat(temple.dataVersion()).isEqualTo(4000);
        assertThat(count(temple, "minecraft:chest")).isEqualTo(1);
        assertThat(temple.blockAt(middle, 1, middle)).startsWith("minecraft:chest");
        assertThat(temple.blockAt(middle, roof, middle))
                .describedAs("open in the middle")
                .isEqualTo(Schematic.AIR);
        assertThat(temple.blockAt(middle + 1, roof, middle + 1)).isEqualTo(Schematic.AIR);
        assertThat(temple.blockAt(middle + 2, roof, middle)).startsWith("minecraft:prismarine_brick_slab");
        assertThat(temple.blockAt(0, roof, 0)).isEqualTo("minecraft:sea_lantern");
        assertThat(temple.blockAt(last, 2, last)).isEqualTo("minecraft:prismarine");
        for (int[] door : new int[][] {{0, middle}, {last, middle}, {middle, 0}, {middle, last}}) {
            assertThat(temple.blockAt(door[0], 1, door[1]))
                    .describedAs("a door")
                    .isEqualTo(Schematic.AIR);
            assertThat(temple.blockAt(door[0], 2, door[1])).isEqualTo(Schematic.AIR);
            assertThat(temple.blockAt(door[0], 3, door[1])).isEqualTo("minecraft:dark_prismarine");
        }
        assertThat(temple.blockAt(0, 1, middle + 1)).describedAs("three wide").isEqualTo(Schematic.AIR);
        assertThat(temple.blockAt(0, 1, middle - 1)).isEqualTo(Schematic.AIR);
        assertThat(temple.blockAt(0, 1, middle + 2)).isEqualTo("minecraft:dark_prismarine");
        assertThat(temple.blockAt(0, roof, middle))
                .describedAs("the roof's rim")
                .isEqualTo("minecraft:prismarine_bricks");
        assertThat(temple.blockAt(middle + 2, 0, middle)).isEqualTo("minecraft:sea_lantern");
        assertThat(temple.blockAt(middle + 2, 0, middle + 1)).isEqualTo("minecraft:prismarine_bricks");
        assertThat(count(temple, "minecraft:sea_lantern")).isEqualTo(4 + 8);
    }

    @Test
    @DisplayName("A schematic becomes a template once: air and an unknown block lay nothing, each state is read once")
    void aSchematicIsATemplate() {
        Schematic small = Schematic.builder(2, 2, 3)
                .block(0, 0, 0, "minecraft:prismarine")
                .block(1, 0, 2, "minecraft:prismarine")
                .block(1, 1, 1, "minecraft:not_a_block")
                .build();
        List<String> parsed = new ArrayList<>();
        WreckTemplate template = SchematicWreckTemplates.template(small, state -> {
            parsed.add(state);
            return state.equals("minecraft:prismarine") ? Material.PRISMARINE.createBlockData() : null;
        });

        assertThat(template.sizeX()).isEqualTo(2);
        assertThat(template.sizeZ()).isEqualTo(3);
        assertThat(template.pieces())
                .extracting(piece -> piece.x() + "," + piece.y() + "," + piece.z())
                .containsExactlyInAnyOrder("0,0,0", "1,0,2");
        assertThat(template.pieces()).allMatch(piece -> piece.marker() == null);
        assertThat(parsed).containsExactlyInAnyOrder("minecraft:prismarine", "minecraft:not_a_block");
    }

    @Test
    @DisplayName("A schematic key reads its file once the server is up; another key is the server's; a bad one is none")
    void keysAreRead() throws Exception {
        IslandSchematics files = files();
        List<String> asked = new ArrayList<>();
        SchematicWreckTemplates templates = new SchematicWreckTemplates(
                key -> {
                    asked.add(key);
                    return Optional.empty();
                },
                SchematicWreckTemplates::parseQuietly);
        assertThat(templates.load("schematic:monument"))
                .describedAs("before the server is up")
                .isEmpty();

        files.writeIfMissing(MonumentLayout.FILE, MonumentLayout.shipped(4000)).join();
        files.writeIfMissing("escape.schem", MonumentLayout.shipped(4000)).join();
        assertThat(templates
                        .prime(
                                files,
                                List.of(
                                        "schematic:monument",
                                        "schematic:monument",
                                        "schematic:missing",
                                        "schematic:../escape",
                                        "minecraft:monument",
                                        "minecraft:shipwreck/with_mast"))
                        .join())
                .describedAs("a name that leaves the folder, and the server's own keys, are not read")
                .isEqualTo(1);
        assertThat(templates.load("schematic:../escape")).isEmpty();

        assertThat(templates.load("schematic:monument"))
                .get()
                .extracting(WreckTemplate::sizeX)
                .isEqualTo(MonumentLayout.SIDE);
        assertThat(templates.load("schematic:missing")).isEmpty();
        assertThat(asked).describedAs("no schematic key reaches the server").isEmpty();
        assertThat(templates.load("minecraft:shipwreck/with_mast")).isEmpty();
        assertThat(asked).containsExactly("minecraft:shipwreck/with_mast");
    }

    @Test
    @DisplayName("The monument group lays the temple centred under the island, its chest given the group's loot")
    void theTempleIsLaid() throws Exception {
        World world = server.addSimpleWorld("sea");
        PoseidonConfiguration.Wreck monument =
                Objects.requireNonNull(PoseidonConfiguration.SHIPPED_WRECKS.get("monument"));
        IslandSchematics files = files();
        files.writeIfMissing(MonumentLayout.FILE, MonumentLayout.shipped(4000)).join();
        SchematicWreckTemplates templates =
                new SchematicWreckTemplates(key -> Optional.empty(), SchematicWreckTemplates::parseQuietly);
        templates.prime(files, monument.templates()).join();
        List<String> looted = new ArrayList<>();
        SchedulerPort scheduler = mock(SchedulerPort.class);
        doAnswer(call -> {
                    call.getArgument(3, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .onRegion(anyString(), anyInt(), anyInt(), any(Runnable.class));

        new WreckStart(
                        "uxm:monument",
                        monument,
                        PoseidonConfiguration.Ocean.SHIPPED,
                        templates,
                        (block, table, seed) ->
                                looted.add(block.getX() + "," + block.getY() + "," + block.getZ() + " " + table),
                        scheduler)
                .apply(new IslandStart(world, IslandId.of(UUID.randomUUID()), CENTER, Y, CENTER, preset()));

        int floor = Y - PoseidonConfiguration.Ocean.SHIPPED.depth();
        int corner = CENTER - MonumentLayout.SIDE / 2;
        assertThat(world.getBlockAt(CENTER, floor + 1, CENTER).getType()).isEqualTo(Material.CHEST);
        assertThat(looted)
                .containsExactly(CENTER + "," + (floor + 1) + "," + CENTER + " minecraft:chests/underwater_ruin_big");
        assertThat(world.getBlockAt(corner, floor, corner).getType()).isEqualTo(Material.PRISMARINE_BRICKS);
        assertThat(world.getBlockAt(corner, floor + MonumentLayout.HEIGHT - 1, corner)
                        .getType())
                .isEqualTo(Material.SEA_LANTERN);
        assertThat(world.getBlockAt(CENTER, floor + MonumentLayout.HEIGHT - 1, CENTER)
                        .getType())
                .describedAs("open over the middle")
                .isEqualTo(Material.AIR);
    }

    @Test
    @DisplayName("The shipped files offer the monument preset, playing Poseidon, laying the temple after the ocean")
    void theShippedFiles() throws Exception {
        StarterPreset preset = PresetConfiguration.load(
                        HoconConfigurationLoader.builder().buildAndLoadString(resource("modules/presets.conf")))
                .catalogue()
                .findById("poseidon_monument")
                .orElseThrow();
        assertThat(preset.mode()).isEqualTo(GameModeType.POSEIDON);
        assertThat(preset.start())
                .containsExactly(StarterPreset.PLATFORM, OceanStart.ACTION, "uxm:monument", "uxm:shipwreck");
        assertThat(PoseidonConfiguration.load(HoconConfigurationLoader.builder()
                                .buildAndLoadString(resource("modules/poseidon.conf")))
                        .wrecks()
                        .get("monument"))
                .isEqualTo(new PoseidonConfiguration.Wreck(
                        List.of("schematic:monument"), 1, 0, List.of("minecraft:chests/underwater_ruin_big")));
    }

    @Test
    @DisplayName("A file written before the monument gains it; its block is read; a group of that name wins")
    void theMonumentHasItsOwnBlock() throws Exception {
        java.util.Map<String, PoseidonConfiguration.Wreck> older = PoseidonConfiguration.load(
                        HoconConfigurationLoader.builder().buildAndLoadString("wrecks { shipwreck { distance = 30 } }"))
                .wrecks();
        assertThat(older).containsOnlyKeys("shipwreck", "monument");
        assertThat(older.get("monument")).isEqualTo(PoseidonConfiguration.SHIPPED_WRECKS.get("monument"));
        assertThat(PoseidonConfiguration.load(HoconConfigurationLoader.builder()
                                .buildAndLoadString("monument { templates = [\"schematic:hall\"], loot = [] }"))
                        .wrecks()
                        .get("monument"))
                .isEqualTo(new PoseidonConfiguration.Wreck(List.of("schematic:hall"), 1, 0, List.of()));
        assertThat(PoseidonConfiguration.load(HoconConfigurationLoader.builder()
                                .buildAndLoadString("wrecks { monument { templates = [\"a:b\"] } },"
                                        + " monument { templates = [\"schematic:hall\"] }"))
                        .wrecks()
                        .get("monument"))
                .describedAs("a wreck group of that name wins")
                .extracting(PoseidonConfiguration.Wreck::templates)
                .isEqualTo(List.of("a:b"));
        assertThat(PoseidonConfiguration.load(HoconConfigurationLoader.builder()
                                .buildAndLoadString("wrecks { shipwreck {} }, monument { count = -1 }"))
                        .wrecks()
                        .get("monument"))
                .describedAs("one that cannot be read is the shipped temple")
                .isEqualTo(PoseidonConfiguration.SHIPPED_WRECKS.get("monument"));
    }

    private IslandSchematics files() {
        return new IslandSchematics(
                folder,
                Runnable::run,
                (schematic, at, options) -> CompletableFuture.failedFuture(new AssertionError("no paste here")),
                (w, corner, other, origin, options) -> CompletableFuture.failedFuture(new AssertionError()),
                com.uxplima.uxmlib.schematic.paper.PasteOptions.DEFAULT);
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

    private StarterPreset preset() throws Exception {
        return PresetConfiguration.load(
                        HoconConfigurationLoader.builder().buildAndLoadString(resource("modules/presets.conf")))
                .catalogue()
                .findById("poseidon_monument")
                .orElseThrow();
    }

    private String resource(String path) throws Exception {
        try (InputStream in = Objects.requireNonNull(getClass().getClassLoader().getResourceAsStream(path))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
