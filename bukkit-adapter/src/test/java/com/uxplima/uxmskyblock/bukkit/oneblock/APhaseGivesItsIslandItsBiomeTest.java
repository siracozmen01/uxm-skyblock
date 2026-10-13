package com.uxplima.uxmskyblock.bukkit.oneblock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.event.block.BlockBreakEvent;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import com.mojang.brigadier.CommandDispatcher;
import com.uxplima.uxmskyblock.bukkit.command.ModeAdminCommands;
import com.uxplima.uxmskyblock.bukkit.config.OneBlockBiome;
import com.uxplima.uxmskyblock.bukkit.config.OneBlockConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.PresetConfiguration;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffectPlayer;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffects;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.schematic.IslandStart;
import com.uxplima.uxmskyblock.bukkit.schematic.StarterSchematicEngine;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.island.IslandAccessService;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.oneblock.OneBlockProgressPort;
import com.uxplima.uxmskyblock.core.application.oneblock.OneBlockService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

/**
 * A phase that names a biome gives it to its island when it begins: over the whole island, or the blocks within a
 * distance of its block, chunk by chunk on each chunk's region, each chunk sent again so the players see it. An
 * island just made takes its first phase's biome, and so does one an operator moves to another phase.
 */
class APhaseGivesItsIslandItsBiomeTest extends MockBukkitHarness {

    private static final int Y = 100;

    private final List<Runnable> regionTasks = new ArrayList<>();
    private final List<String> resent = new ArrayList<>();
    private final Map<IslandId, OneBlockProgressPort.OneBlockIsland> stored = new ConcurrentHashMap<>();

    @SuppressWarnings("NullAway.Init")
    private World world;

    @SuppressWarnings("NullAway.Init")
    private SchedulerPort scheduler;

    @SuppressWarnings("NullAway.Init")
    private PlayerMock player;

    @SuppressWarnings("NullAway.Init")
    private Island island;

    @BeforeEach
    void world() {
        // Reaching below nothing, as a server's worlds do, so the painting is seen to start at the bottom.
        org.mockbukkit.mockbukkit.world.WorldMock below =
                new org.mockbukkit.mockbukkit.world.WorldMock(Material.AIR, Biome.PLAINS, -64, 320, 0);
        below.setName("skyblock");
        server.addWorld(below);
        world = below;
        player = createPlayer("Breaker");
        island = Island.create(
                IslandId.of(UUID.randomUUID()),
                IslandBounds.fromCenterAndRadius(0, 0, 20),
                new PlayerUuid(player.getUniqueId()),
                new ProfileId(player.getUniqueId()),
                Instant.now());
        scheduler = mock(SchedulerPort.class);
        doAnswer(call -> regionTasks.add(call.getArgument(3, Runnable.class)))
                .when(scheduler)
                .onRegion(anyString(), anyInt(), anyInt(), any(Runnable.class));
        doAnswer(call -> {
                    call.getArgument(0, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .async(any(Runnable.class));
    }

    private static OneBlockConfiguration configured(String plains, String cave) throws Exception {
        return OneBlockConfiguration.load(
                HoconConfigurationLoader.builder().buildAndLoadString("""
                phases = [
                    { name = "plains", length = 2, blocks { DIRT = 1 }, %s },
                    { name = "cave", length = 5, blocks { STONE = 1 }, %s }
                ]
                """.formatted(plains, cave)));
    }

    private OneBlockBiomes biomes(OneBlockConfiguration config) {
        return new OneBlockBiomes(
                () -> config,
                scheduler,
                id -> id.equals(island.id()) ? Optional.of(island) : Optional.empty(),
                (painted, chunkX, chunkZ) -> resent.add(chunkX + "," + chunkZ));
    }

    private void runRegions() {
        List<Runnable> due = new ArrayList<>(regionTasks);
        regionTasks.clear();
        due.forEach(Runnable::run);
    }

    /** The biome of the cell {@code x}, {@code z} falls in, as a server reads it: one per four blocks each way. */
    private Biome at(int x, int z) {
        int y = world.getMinHeight() + Math.floorDiv(Y - world.getMinHeight(), 4) * 4;
        return world.getBiome(Math.floorDiv(x, 4) * 4, y, Math.floorDiv(z, 4) * 4);
    }

    @Test
    @DisplayName("A phase file names a biome and where it goes; one the server does not know is left out")
    void read() throws Exception {
        OneBlockConfiguration config =
                configured("biome = \"snowy_plains\"", "biome = \"MINECRAFT:DESERT\", biome-area = \"6\"");

        assertThat(config.biomeOf("plains")).contains(new OneBlockBiome(Biome.SNOWY_PLAINS, OneBlockBiome.ISLAND));
        assertThat(config.biomeOf("cave")).contains(new OneBlockBiome(Biome.DESERT, 6));
        assertThat(configured("biome = \"moon\"", "").biomeOf("plains")).isEmpty();
        assertThat(configured("biome = \"desert\", biome-area = \"far\"", "").biomeOf("plains"))
                .describedAs("an area that is neither is the whole island")
                .contains(new OneBlockBiome(Biome.DESERT, OneBlockBiome.ISLAND));
        assertThat(configured("biome = \"desert\", biome-area = \"0\"", "").biomeOf("plains"))
                .contains(new OneBlockBiome(Biome.DESERT, 0));
        assertThat(configured("", "").biomeOf("plains")).isEmpty();
    }

    @Test
    @DisplayName("Around the block, every cell within the distance takes it, and the chunks are sent again")
    void aroundTheBlock() throws Exception {
        Biome before = at(30, 30);
        OneBlockBiomes biomes = biomes(configured("biome = \"desert\", biome-area = \"6\"", ""));

        biomes.began(island, world, 0, 0, "plains");
        assertThat(at(0, 0))
                .describedAs("nothing is painted off the region's thread")
                .isEqualTo(before);
        runRegions();

        assertThat(at(0, 0)).isEqualTo(Biome.DESERT);
        assertThat(at(-6, -6)).isEqualTo(Biome.DESERT);
        assertThat(at(6, 6)).isEqualTo(Biome.DESERT);
        assertThat(at(-8, 7)).describedAs("the cell the edge falls in").isEqualTo(Biome.DESERT);
        assertThat(at(8, 0)).isEqualTo(before);
        assertThat(at(0, -9)).isEqualTo(before);
        assertThat(world.getMinHeight()).isNegative();
        assertThat(world.getBiome(0, world.getMinHeight(), 0)).isEqualTo(Biome.DESERT);
        assertThat(world.getBiome(
                        0, world.getMinHeight() + (world.getMaxHeight() - world.getMinHeight() - 1) / 4 * 4, 0))
                .isEqualTo(Biome.DESERT);
        assertThat(resent).containsExactlyInAnyOrder("-1,-1", "-1,0", "0,-1", "0,0");

        resent.clear();
        biomes.began(island, world, 20, 0, "plains");
        runRegions();
        assertThat(resent).containsExactlyInAnyOrder("0,-1", "0,0", "1,-1", "1,0");
    }

    @Test
    @DisplayName("Over the island, every block of it takes the biome, and none past it")
    void overTheIsland() throws Exception {
        Biome before = at(40, 0);
        OneBlockBiomes biomes = biomes(configured("biome = \"snowy_plains\"", ""));

        biomes.paint(island.id(), world, 0, 0, "plains");
        runRegions();

        assertThat(at(-20, -20)).isEqualTo(Biome.SNOWY_PLAINS);
        assertThat(at(20, 20)).isEqualTo(Biome.SNOWY_PLAINS);
        assertThat(at(40, 0)).isEqualTo(before);
        assertThat(resent).describedAs("the chunks from -20 to 20 each way").hasSize(16);

        resent.clear();
        biomes.began(island, world, 0, 0, "cave");
        runRegions();
        assertThat(resent)
                .describedAs("a phase that names no biome leaves the one before")
                .isEmpty();
        assertThat(at(0, 0)).isEqualTo(Biome.SNOWY_PLAINS);
    }

    @Test
    @DisplayName("The break that begins a phase gives the island its biome, and the breaks within it do not")
    void atTheBreak() throws Exception {
        OneBlockConfiguration config = configured("", "biome = \"desert\", biome-area = \"2\"");
        OneBlockService service = new OneBlockService(memory(), config.phases(), new SplittableRandom(1));
        service.start(island.id(), 0, Y, 0);
        IslandProtectionListener islands =
                new IslandProtectionListener(mock(IslandStoragePort.class), new IslandAccessService());
        islands.cacheIsland(island);
        OneBlockListener listener = new OneBlockListener(
                service,
                islands,
                scheduler,
                Messages.bundled(),
                new InteractionEffects(Map.of()),
                new InteractionEffectPlayer(null, Messages.bundled()));
        listener.useBiomes(biomes(config));

        breakIt(listener);
        assertThat(resent).isEmpty();
        breakIt(listener);

        assertThat(at(0, 0)).isEqualTo(Biome.DESERT);
        assertThat(resent).containsExactlyInAnyOrder("-1,-1", "-1,0", "0,-1", "0,0");
        breakIt(listener);
        assertThat(resent).hasSize(4);
    }

    private void breakIt(OneBlockListener listener) {
        Block block = world.getBlockAt(0, Y, 0);
        block.setType(Material.DIRT);
        listener.onBlockBreak(new BlockBreakEvent(block, player));
        runRegions();
    }

    @Test
    @DisplayName("An island just made takes the biome of its first phase")
    void aNewIsland() throws Exception {
        OneBlockConfiguration config = configured("biome = \"snowy_plains\"", "");
        OneBlockService service = new OneBlockService(memory(), config.phases(), new SplittableRandom(1));
        StarterSchematicEngine engine = new StarterSchematicEngine();
        engine.actions().register(new OneBlockStart(service, OneBlockDraws.PLAIN, biomes(config)));

        engine.start(new IslandStart(
                        world,
                        island.id(),
                        0,
                        Y,
                        0,
                        PresetConfiguration.load(HoconConfigurationLoader.builder()
                                        .buildAndLoadString(resource("modules/presets.conf")))
                                .catalogue()
                                .findById("oneblock")
                                .orElseThrow()))
                .join();
        runRegions();

        assertThat(at(15, -15)).isEqualTo(Biome.SNOWY_PLAINS);
    }

    @Test
    @DisplayName("An island an operator moves to another phase takes that phase's biome")
    void anOperatorMovesIt() throws Exception {
        OneBlockConfiguration config = configured("", "biome = \"desert\", biome-area = \"1\"");
        OneBlockService service = new OneBlockService(memory(), config.phases(), new SplittableRandom(1));
        service.start(island.id(), 0, Y, 0);
        IslandLocationService locations = mock(IslandLocationService.class);
        when(locations.findLocation(island.id()))
                .thenReturn(Optional.of(IslandLocation.fromCenterAndRadius(island.id(), "skyblock", 0, 0, 20)));
        ModeAdminCommands admin = new ModeAdminCommands(
                List.of(new OneBlockAdmin(service, OneBlockDraws.PLAIN, biomes(config))),
                locations,
                null,
                scheduler,
                Messages.bundled());
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        dispatcher.register(admin.build(null, (source, tag) -> true).orElseThrow());
        CommandSourceStack source = mock(CommandSourceStack.class);
        PlayerMock operator = createPlayer("Op");
        operator.setOp(true);
        when(source.getSender()).thenReturn(operator);

        dispatcher.execute("isadmin oneblock addcount " + island.id().value() + " 1", source);
        runRegions();
        assertThat(resent).describedAs("still in the first phase").isEmpty();
        dispatcher.execute("isadmin oneblock setphase " + island.id().value() + " cave", source);
        runRegions();

        assertThat(at(0, 0)).isEqualTo(Biome.DESERT);
        assertThat(resent).containsExactlyInAnyOrder("-1,-1", "-1,0", "0,-1", "0,0");
    }

    private String resource(String path) throws Exception {
        try (InputStream in =
                java.util.Objects.requireNonNull(getClass().getClassLoader().getResourceAsStream(path))) {
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
