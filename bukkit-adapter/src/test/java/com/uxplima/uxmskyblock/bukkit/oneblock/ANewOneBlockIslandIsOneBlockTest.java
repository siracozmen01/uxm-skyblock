package com.uxplima.uxmskyblock.bukkit.oneblock;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Material;
import org.bukkit.World;

import com.uxplima.uxmskyblock.bukkit.config.PresetConfiguration;
import com.uxplima.uxmskyblock.bukkit.schematic.IslandStart;
import com.uxplima.uxmskyblock.bukkit.schematic.StarterSchematicEngine;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.oneblock.OneBlockProgressPort;
import com.uxplima.uxmskyblock.core.application.oneblock.OneBlockService;
import com.uxplima.uxmskyblock.core.application.preset.StarterPresetCatalog;
import com.uxplima.uxmskyblock.core.domain.gamemode.GameModeType;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.oneblock.OneBlockPhase;
import com.uxplima.uxmskyblock.core.domain.oneblock.OneBlockPhases;
import com.uxplima.uxmskyblock.core.domain.oneblock.WeightedPool;
import com.uxplima.uxmskyblock.core.domain.preset.StarterPreset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

/**
 * An island made from the OneBlock preset is one block, drawn from the first phase, where its players
 * arrive standing, and it is a OneBlock island from its first break.
 */
class ANewOneBlockIslandIsOneBlockTest extends MockBukkitHarness {

    private static final int CENTER = 5_000;
    private static final int Y = 99;

    private final Map<IslandId, OneBlockProgressPort.OneBlockIsland> stored = new ConcurrentHashMap<>();

    @SuppressWarnings("NullAway.Init")
    private World world;

    @SuppressWarnings("NullAway.Init")
    private OneBlockService service;

    @SuppressWarnings("NullAway.Init")
    private StarterSchematicEngine engine;

    @BeforeEach
    void setUpEngine() {
        world = server.addSimpleWorld("skyblock");
        OneBlockPhases phases = new OneBlockPhases(
                List.of(
                        new OneBlockPhase(
                                "plains", 3, new WeightedPool(Map.of("OAK_LOG", 1.0)), WeightedPool.empty(), 0),
                        new OneBlockPhase(
                                "underground", 3, new WeightedPool(Map.of("STONE", 1.0)), WeightedPool.empty(), 0)),
                OneBlockPhases.AfterTheLast.REPEAT);
        service = new OneBlockService(memory(), phases, new SplittableRandom(7));
        engine = new StarterSchematicEngine();
        engine.actions().register(new OneBlockStart(service));
    }

    @Test
    @DisplayName("The OneBlock preset sets one block from the first phase and lays no platform")
    void theIslandIsOneBlock() throws Exception {
        StarterPreset preset = shippedPresets().findById("oneblock").orElseThrow();
        IslandId island = IslandId.of(UUID.randomUUID());

        engine.start(new IslandStart(world, island, CENTER, Y, CENTER, preset)).join();

        assertThat(world.getBlockAt(CENTER, Y, CENTER).getType()).isEqualTo(Material.OAK_LOG);
        assertThat(world.getBlockAt(CENTER + 1, Y, CENTER).getType())
                .describedAs("nothing stands beside the one block")
                .isEqualTo(Material.AIR);
        assertThat(world.getBlockAt(CENTER, Y - 2, CENTER).getType())
                .describedAs("no bedrock under it either")
                .isEqualTo(Material.AIR);
        assertThat(service.island(island))
                .describedAs("the island is a OneBlock island at the block it was given")
                .get()
                .satisfies(oneBlock -> {
                    assertThat(oneBlock.x()).isEqualTo(CENTER);
                    assertThat(oneBlock.y()).isEqualTo(Y);
                    assertThat(oneBlock.z()).isEqualTo(CENTER);
                    assertThat(oneBlock.blocksBroken()).isZero();
                });
        assertThat(stored).containsKey(island);
    }

    @Test
    @DisplayName("A classic preset still lays its platform and is no OneBlock island")
    void aClassicIslandIsNotOneBlock() throws Exception {
        StarterPreset preset = shippedPresets().findById("classic").orElseThrow();
        IslandId island = IslandId.of(UUID.randomUUID());

        engine.start(new IslandStart(world, island, CENTER, Y, CENTER, preset)).join();

        assertThat(world.getBlockAt(CENTER + 1, Y, CENTER).getType()).isEqualTo(Material.GRASS_BLOCK);
        assertThat(world.getBlockAt(CENTER, Y - 2, CENTER).getType()).isEqualTo(Material.BEDROCK);
        assertThat(stored).doesNotContainKey(island);
    }

    @Test
    @DisplayName("The shipped OneBlock preset plays OneBlock, and is not offered where OneBlock is off")
    void thePresetIsOfferedOnlyWhereOneBlockIsOn() throws Exception {
        PresetConfiguration shipped = PresetConfiguration.load(
                HoconConfigurationLoader.builder().buildAndLoadString(resource("modules/presets.conf")));
        StarterPreset preset = shipped.catalogue().findById("oneblock").orElseThrow();
        assertThat(preset.mode()).isEqualTo(GameModeType.ONEBLOCK);
        assertThat(preset.start()).containsExactly(OneBlockStart.ACTION);

        StarterPresetCatalog on =
                shipped.startableWith(engine.actions()::knowsAll).catalogue();
        StarterPresetCatalog off = shipped.startableWith(new StarterSchematicEngine().actions()::knowsAll)
                .catalogue();

        assertThat(on.findById("oneblock")).isPresent();
        assertThat(off.findById("oneblock")).isEmpty();
        assertThat(off.findById("classic")).isPresent();
    }

    private StarterPresetCatalog shippedPresets() throws Exception {
        return PresetConfiguration.load(
                        HoconConfigurationLoader.builder().buildAndLoadString(resource("modules/presets.conf")))
                .catalogue();
    }

    private String resource(String path) throws Exception {
        try (InputStream in = Objects.requireNonNull(getClass().getClassLoader().getResourceAsStream(path))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private OneBlockProgressPort memory() {
        return new OneBlockProgressPort() {
            @Override
            public void start(IslandId islandId, int x, int y, int z) {
                stored.put(islandId, new OneBlockIsland(islandId, x, y, z, 0));
            }

            @Override
            public List<OneBlockIsland> findAll() {
                return List.copyOf(stored.values());
            }

            @Override
            public Optional<OneBlockIsland> find(IslandId islandId) {
                return Optional.ofNullable(stored.get(islandId));
            }

            @Override
            public void addBreaks(IslandId islandId, long breaks) {
                stored.computeIfPresent(
                        islandId,
                        (id, held) ->
                                new OneBlockIsland(id, held.x(), held.y(), held.z(), held.blocksBroken() + breaks));
            }

            @Override
            public void setBreaks(IslandId islandId, long count) {
                stored.computeIfPresent(
                        islandId, (id, held) -> new OneBlockIsland(id, held.x(), held.y(), held.z(), count));
            }
        };
    }
}
