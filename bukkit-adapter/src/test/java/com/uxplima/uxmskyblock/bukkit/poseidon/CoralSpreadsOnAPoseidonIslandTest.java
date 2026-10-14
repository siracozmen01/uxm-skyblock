package com.uxplima.uxmskyblock.bukkit.poseidon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Waterlogged;

import com.uxplima.uxmskyblock.bukkit.config.PoseidonConfiguration;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.island.IslandAccessService;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.poseidon.PoseidonIslandsPort;
import com.uxplima.uxmskyblock.core.application.poseidon.PoseidonService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

/**
 * Coral spreads slowly on a Poseidon island: a living coral block near a player grows, by chance, a coral or a fan of
 * its colour into the water above it, or a fan onto its side, and nowhere else.
 */
class CoralSpreadsOnAPoseidonIslandTest extends MockBukkitHarness {

    private static final int Y = 100;
    private static final PoseidonConfiguration.Coral CORAL =
            new PoseidonConfiguration.Coral(true, Duration.ofSeconds(30), 1, 12, 0.25);

    private final Set<IslandId> stored = new HashSet<>();
    private final Deque<Integer> ints = new ArrayDeque<>();
    private final Deque<Double> doubles = new ArrayDeque<>();
    private final Deque<Boolean> booleans = new ArrayDeque<>();
    private final Set<Location> notOwned = new HashSet<>();

    @SuppressWarnings("NullAway.Init")
    private World world;

    @SuppressWarnings("NullAway.Init")
    private Island poseidon;

    @SuppressWarnings("NullAway.Init")
    private IslandProtectionListener islands;

    @SuppressWarnings("NullAway.Init")
    private PoseidonService service;

    @SuppressWarnings("NullAway.Init")
    private PlayerMock diver;

    @BeforeEach
    void setUpTheReef() {
        world = server.addSimpleWorld("skyblock");
        poseidon = island(0);
        Island plain = island(1000);
        islands = new IslandProtectionListener(mock(IslandStoragePort.class), new IslandAccessService());
        islands.cacheIsland(poseidon, "skyblock");
        islands.cacheIsland(plain, "skyblock");
        service = new PoseidonService(new PoseidonIslandsPort() {
            @Override
            public Set<IslandId> findAll() {
                return Set.copyOf(stored);
            }

            @Override
            public boolean exists(IslandId islandId) {
                return stored.contains(islandId);
            }

            @Override
            public void add(IslandId islandId) {
                stored.add(islandId);
            }
        });
        service.start(poseidon.id());
        diver = createPlayer("Diver");
        diver.teleport(new Location(world, 8.5, Y + 2, 8.5));
    }

    private CoralGrowth growth(PoseidonConfiguration.Coral config) {
        return new CoralGrowth(
                config, service, islands, mock(SchedulerPort.class), at -> !notOwned.contains(at), new Random() {
                    @Override
                    public int nextInt(int bound) {
                        return ints.isEmpty() ? 0 : ints.pop();
                    }

                    @Override
                    public double nextDouble() {
                        return doubles.isEmpty() ? 0.99 : doubles.pop();
                    }

                    @Override
                    public boolean nextBoolean() {
                        return booleans.isEmpty() || booleans.pop();
                    }
                });
    }

    /** The look lands on the block at {@code dx, dy, dz} from the diver's feet, two above the reef. */
    private void lookAt(int dx, int dy, int dz) {
        ints.add(dx + 12);
        ints.add(dy + 6);
        ints.add(dz + 12);
    }

    private Block coralAt(int x, int z, Material type) {
        world.getBlockAt(x, Y + 1, z).setType(Material.WATER);
        for (BlockFace face : List.of(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST)) {
            world.getBlockAt(x, Y, z).getRelative(face).setType(Material.WATER);
        }
        Block coral = world.getBlockAt(x, Y, z);
        coral.setType(type);
        return coral;
    }

    @Test
    @DisplayName("A coral block grows a coral or a fan of its colour above it, in the water, by chance")
    void itGrowsUp() {
        coralAt(8, 8, Material.TUBE_CORAL_BLOCK);
        lookAt(0, -2, 0);
        doubles.add(0.3);
        assertThat(growth(CORAL).grow(diver)).describedAs("the chance not met").isZero();

        lookAt(0, -2, 0);
        doubles.add(0.2);
        ints.add(0);
        booleans.add(true);
        assertThat(growth(CORAL).grow(diver)).isEqualTo(1);
        Block grown = world.getBlockAt(8, Y + 1, 8);
        assertThat(grown.getType()).isEqualTo(Material.TUBE_CORAL);
        assertThat(((Waterlogged) grown.getBlockData()).isWaterlogged()).isTrue();

        coralAt(10, 8, Material.FIRE_CORAL_BLOCK);
        lookAt(2, -2, 0);
        doubles.add(0.0);
        ints.add(0);
        booleans.add(false);
        assertThat(growth(CORAL).grow(diver)).isEqualTo(1);
        assertThat(world.getBlockAt(10, Y + 1, 8).getType()).isEqualTo(Material.FIRE_CORAL_FAN);
    }

    @Test
    @DisplayName("A coral block grows a fan onto its side, facing out")
    void itGrowsAFanOnItsSide() {
        coralAt(8, 8, Material.BRAIN_CORAL_BLOCK);
        lookAt(0, -2, 0);
        doubles.add(0.0);
        ints.add(1);
        assertThat(growth(CORAL).grow(diver)).isEqualTo(1);
        Block fan = world.getBlockAt(8, Y, 7);
        assertThat(fan.getType()).isEqualTo(Material.BRAIN_CORAL_WALL_FAN);
        assertThat(((Directional) fan.getBlockData()).getFacing()).isEqualTo(BlockFace.NORTH);
        assertThat(((Waterlogged) fan.getBlockData()).isWaterlogged()).isTrue();
    }

    @Test
    @DisplayName(
            "Nothing grows out of the water, off the island, from dead coral, where the thread owns nothing, or while off")
    void nothingGrows() {
        Block coral = coralAt(8, 8, Material.HORN_CORAL_BLOCK);
        world.getBlockAt(8, Y + 1, 8).setType(Material.AIR);
        assertThat(once(CORAL, 0)).describedAs("air above, no water").isZero();
        world.getBlockAt(8, Y + 1, 8).setType(Material.WATER);

        coral.setType(Material.DEAD_HORN_CORAL_BLOCK);
        assertThat(once(CORAL, 0)).describedAs("dead coral").isZero();
        coral.setType(Material.HORN_CORAL_BLOCK);

        notOwned.add(coral.getLocation());
        assertThat(once(CORAL, 0)).describedAs("the coral is not this thread's").isZero();
        notOwned.clear();
        notOwned.add(world.getBlockAt(8, Y + 1, 8).getLocation());
        assertThat(once(CORAL, 0)).describedAs("the water is not this thread's").isZero();
        notOwned.clear();

        assertThat(once(new PoseidonConfiguration.Coral(false, Duration.ofSeconds(30), 1, 12, 1), 0))
                .describedAs("off")
                .isZero();
        assertThat(world.getBlockAt(8, Y + 1, 8).getType()).isEqualTo(Material.WATER);

        diver.teleport(new Location(world, 1008.5, Y + 2, 8.5));
        coralAt(1008, 8, Material.HORN_CORAL_BLOCK);
        assertThat(once(CORAL, 0)).describedAs("another mode's island").isZero();
        assertThat(world.getBlockAt(1008, Y + 1, 8).getType()).isEqualTo(Material.WATER);

        // The island ends at x 47 and the next one begins at 48: a fan grows no further.
        islands.cacheIsland(island(80), "skyblock");
        diver.teleport(new Location(world, 47.5, Y + 2, 8.5));
        coralAt(47, 8, Material.HORN_CORAL_BLOCK);
        assertThat(once(CORAL, 3)).describedAs("onto the next island").isZero();
        assertThat(world.getBlockAt(48, Y, 8).getType()).isEqualTo(Material.WATER);

        assertThat(once(CORAL, 0))
                .describedAs("and the same coral grows on its own island")
                .isEqualTo(1);
    }

    /** One look at the block under the diver, the chance met, the face {@code face} chosen. */
    private int once(PoseidonConfiguration.Coral config, int face) {
        ints.clear();
        doubles.clear();
        booleans.clear();
        lookAt(0, -2, 0);
        doubles.add(0.0);
        ints.add(face);
        return growth(config).grow(diver);
    }

    @Test
    @DisplayName("A colour is read off a living coral block only, and the growth is read from the file")
    void coloursAndTheFile() throws Exception {
        assertThat(CoralGrowth.colour(Material.TUBE_CORAL_BLOCK)).contains("TUBE");
        assertThat(CoralGrowth.colour(Material.BUBBLE_CORAL_BLOCK)).contains("BUBBLE");
        assertThat(CoralGrowth.colour(Material.DEAD_TUBE_CORAL_BLOCK)).isEmpty();
        assertThat(CoralGrowth.colour(Material.TUBE_CORAL)).isEmpty();
        assertThat(CoralGrowth.colour(Material.STONE)).isEmpty();

        assertThat(PoseidonConfiguration.load(HoconConfigurationLoader.builder()
                                .buildAndLoadString(resource("modules/poseidon.conf")))
                        .coral())
                .isEqualTo(PoseidonConfiguration.Coral.SHIPPED);
        assertThat(PoseidonConfiguration.load(HoconConfigurationLoader.builder()
                                .buildAndLoadString("coral { enabled = false, check-every = 5s, samples = 3,"
                                        + " radius = 4, chance = 1 }"))
                        .coral())
                .isEqualTo(new PoseidonConfiguration.Coral(false, Duration.ofSeconds(5), 3, 4, 1));
        for (String bad : List.of(
                "coral { radius = 0 }",
                "coral { samples = -1 }",
                "coral { chance = 2 }",
                "coral { check-every = 0s }")) {
            assertThat(PoseidonConfiguration.load(
                                    HoconConfigurationLoader.builder().buildAndLoadString(bad))
                            .coral())
                    .describedAs(bad)
                    .isEqualTo(PoseidonConfiguration.Coral.SHIPPED);
        }
    }

    private String resource(String path) throws Exception {
        try (InputStream in = Objects.requireNonNull(getClass().getClassLoader().getResourceAsStream(path))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Island island(int centreX) {
        return Island.create(
                IslandId.of(UUID.randomUUID()),
                IslandBounds.fromCenterAndRadius(centreX + 8, 8, 40),
                new PlayerUuid(UUID.randomUUID()),
                new ProfileId(UUID.randomUUID()),
                Instant.now());
    }
}
