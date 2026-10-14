package com.uxplima.uxmskyblock.bukkit.chunkblock;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Material;
import org.bukkit.World;

import com.uxplima.uxmskyblock.bukkit.config.ChunkBlockConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.PresetConfiguration;
import com.uxplima.uxmskyblock.bukkit.oneblock.OneBlockStart;
import com.uxplima.uxmskyblock.bukkit.schematic.IslandStart;
import com.uxplima.uxmskyblock.bukkit.schematic.StarterSchematicEngine;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.chunkblock.ChunkBlockService;
import com.uxplima.uxmskyblock.core.application.chunkblock.ChunkTerritoryPort;
import com.uxplima.uxmskyblock.core.application.oneblock.OneBlockProgressPort;
import com.uxplima.uxmskyblock.core.application.oneblock.OneBlockService;
import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkPos;
import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkTerritory;
import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkUnlockRules;
import com.uxplima.uxmskyblock.core.domain.dimension.DimensionId;
import com.uxplima.uxmskyblock.core.domain.gamemode.GameModeType;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.oneblock.OneBlockPhase;
import com.uxplima.uxmskyblock.core.domain.oneblock.OneBlockPhases;
import com.uxplima.uxmskyblock.core.domain.oneblock.WeightedPool;
import com.uxplima.uxmskyblock.core.domain.preset.StarterPreset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

/**
 * The shipped ChunkBlock preset makes an island a magic block in one chunk: the OneBlock start sets the
 * block, the ChunkBlock start holds the chunk it stands in, and the Nether and the End stay closed.
 */
class ANewChunkBlockIslandHoldsOneChunkTest extends MockBukkitHarness {

    private static final int CENTER = 5_000;
    private static final int Y = 99;

    private final Map<IslandId, OneBlockProgressPort.OneBlockIsland> blocks = new ConcurrentHashMap<>();
    private final Map<IslandId, ChunkPos> origins = new HashMap<>();

    @SuppressWarnings("NullAway.Init")
    private World world;

    @SuppressWarnings("NullAway.Init")
    private ChunkBlockService chunks;

    @SuppressWarnings("NullAway.Init")
    private StarterSchematicEngine engine;

    @BeforeEach
    void setUpEngine() {
        world = server.addSimpleWorld("skyblock");
        OneBlockService oneBlock = new OneBlockService(oneBlockMemory(), phases(), new SplittableRandom(3));
        chunks = new ChunkBlockService(chunkMemory(), ChunkUnlockRules.shipped());
        engine = new StarterSchematicEngine();
        engine.actions().register(new OneBlockStart(oneBlock));
        engine.actions().register(new ChunkBlockStart(chunks));
    }

    @Test
    @DisplayName("The island is the magic block and the one chunk it stands in")
    void theIslandIsOneChunk() throws Exception {
        StarterPreset preset = shipped().catalogue().findById("chunkblock").orElseThrow();
        IslandId island = IslandId.of(UUID.randomUUID());

        engine.start(new IslandStart(world, island, CENTER, Y, CENTER, preset)).join();

        assertThat(world.getBlockAt(CENTER, Y, CENTER).getType()).isEqualTo(Material.OAK_LOG);
        assertThat(blocks).containsKey(island);
        ChunkPos home = ChunkPos.ofBlock(CENTER, CENTER);
        assertThat(origins).containsEntry(island, home);
        assertThat(chunks.isOpen(island, home)).contains(true);
        assertThat(chunks.isOpen(island, new ChunkPos(home.x() + 1, home.z()))).contains(false);
    }

    @Test
    @DisplayName(
            "The preset plays ChunkBlock, starts the block before the chunk, and starts a web of its own in its Nether"
                    + " and End")
    void thePresetIsChunkBlock() throws Exception {
        StarterPreset preset = shipped().catalogue().findById("chunkblock").orElseThrow();

        assertThat(preset.mode()).isEqualTo(GameModeType.CHUNKBLOCK);
        assertThat(preset.start()).containsExactly(OneBlockStart.ACTION, ChunkBlockStart.ACTION);
        IslandBounds bounds = IslandBounds.fromCenterAndRadius(0, 0, 50);
        // No magic block below: one island has one, in the overworld. Each dimension's first chunk is a platform.
        var nether = preset.dimensions().placeIn(DimensionId.THE_NETHER, bounds).orElseThrow();
        assertThat(nether.template().actions()).containsExactly("uxm:dimension-platform", ChunkBlockStart.ACTION);
        assertThat(nether.height()).isEqualTo(70);
        var end = preset.dimensions().placeIn(DimensionId.THE_END, bounds).orElseThrow();
        assertThat(end.template().actions()).containsExactly("uxm:dimension-platform", ChunkBlockStart.ACTION);
        assertThat(end.height()).isEqualTo(64);
    }

    @Test
    @DisplayName("Where ChunkBlock is off, its preset is not offered")
    void offWhereTheModeIsOff() throws Exception {
        StarterSchematicEngine withoutChunkBlock = new StarterSchematicEngine();
        withoutChunkBlock
                .actions()
                .register(new OneBlockStart(new OneBlockService(oneBlockMemory(), phases(), new SplittableRandom(1))));

        assertThat(shipped()
                        .startableWith(withoutChunkBlock.actions()::knowsAll)
                        .catalogue()
                        .findById("chunkblock"))
                .isEmpty();
        assertThat(shipped()
                        .startableWith(engine.actions()::knowsAll)
                        .catalogue()
                        .findById("chunkblock"))
                .isPresent();
    }

    @Test
    @DisplayName("The shipped file reads as the shipped ladder, and a ladder that goes down falls back to it")
    void theShippedFile() throws Exception {
        ChunkBlockConfiguration file = ChunkBlockConfiguration.load(
                HoconConfigurationLoader.builder().buildAndLoadString(resource("modules/chunkblock.conf")));
        ChunkBlockConfiguration fallingLadder = ChunkBlockConfiguration.load(HoconConfigurationLoader.builder()
                .buildAndLoadString("unlock-levels = [5, 2]\nbypass-permission = \" staff.chunks \""));

        assertThat(file).isEqualTo(ChunkBlockConfiguration.defaultConfiguration());
        assertThat(fallingLadder.rules()).isEqualTo(ChunkUnlockRules.shipped());
        assertThat(fallingLadder.bypassPermission()).isEqualTo("staff.chunks");
        assertThat(ChunkBlockConfiguration.load(HoconConfigurationLoader.builder()
                                .buildAndLoadString("unlock-levels = [2, 4]\nthen-every = 7"))
                        .rules()
                        .requiredFor(4))
                .isEqualTo(18);
    }

    private static OneBlockPhases phases() {
        return new OneBlockPhases(
                List.of(new OneBlockPhase(
                        "plains", 3, new WeightedPool(Map.of("OAK_LOG", 1.0)), WeightedPool.empty(), 0)),
                OneBlockPhases.AfterTheLast.STAY);
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

    private ChunkTerritoryPort chunkMemory() {
        return new ChunkTerritoryPort() {
            private final Map<IslandId, List<ChunkPos>> opened = new HashMap<>();

            @Override
            public Map<IslandId, ChunkTerritory> findAll() {
                Map<IslandId, ChunkTerritory> all = new HashMap<>();
                origins.keySet().forEach(id -> all.put(id, find(id).orElseThrow()));
                return all;
            }

            @Override
            public Optional<ChunkTerritory> find(IslandId islandId) {
                ChunkPos origin = origins.get(islandId);
                return origin == null
                        ? Optional.empty()
                        : Optional.of(new ChunkTerritory(origin, opened.getOrDefault(islandId, List.of())));
            }

            @Override
            public void start(IslandId islandId, ChunkPos origin) {
                origins.putIfAbsent(islandId, origin);
            }

            @Override
            public boolean open(IslandId islandId, ChunkPos chunk, int order) {
                return opened.computeIfAbsent(islandId, id -> new ArrayList<>()).add(chunk);
            }

            @Override
            public void close(IslandId islandId, List<ChunkPos> closed) {
                opened.getOrDefault(islandId, new ArrayList<>()).removeAll(closed);
            }
        };
    }

    private OneBlockProgressPort oneBlockMemory() {
        return new OneBlockProgressPort() {
            @Override
            public void start(IslandId islandId, int x, int y, int z) {
                blocks.put(islandId, new OneBlockIsland(islandId, x, y, z, 0));
            }

            @Override
            public List<OneBlockIsland> findAll() {
                return List.copyOf(blocks.values());
            }

            @Override
            public Optional<OneBlockIsland> find(IslandId islandId) {
                return Optional.ofNullable(blocks.get(islandId));
            }

            @Override
            public void addBreaks(IslandId islandId, long breaks) {}

            @Override
            public void setBreaks(IslandId islandId, long count) {}
        };
    }
}
