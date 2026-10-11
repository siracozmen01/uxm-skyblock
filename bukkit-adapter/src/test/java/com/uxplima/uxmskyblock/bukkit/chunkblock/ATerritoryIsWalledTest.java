package com.uxplima.uxmskyblock.bukkit.chunkblock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.event.world.ChunkLoadEvent;

import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.test.InlineSchedulerPort;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.bukkit.wall.WallPainter;
import com.uxplima.uxmskyblock.bukkit.wall.WallStyle;
import com.uxplima.uxmskyblock.core.application.chunkblock.ChunkBlockService;
import com.uxplima.uxmskyblock.core.application.chunkblock.ChunkTerritoryPort;
import com.uxplima.uxmskyblock.core.application.island.IslandAccessService;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkPos;
import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkTerritory;
import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkUnlockRules;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.wall.WallSegment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A ChunkBlock island's open ground is walled where it meets closed ground: a chunk stands its walls when it loads,
 * and a chunk that opens or closes redraws its own and its neighbours'.
 */
class ATerritoryIsWalledTest extends MockBukkitHarness {

    /**
     * What stands in each chunk, by its owner, as the entities would: a wall belongs to the chunk of the ground it
     * closes, and taking a chunk's walls down takes only its owner's.
     */
    private final Map<ChunkPos, List<String>> standing = new HashMap<>();

    private final WallPainter painter = new WallPainter() {
        @Override
        public void draw(World world, String owner, WallSegment wall, WallStyle style) {
            standing.computeIfAbsent(chunkOf(wall), chunk -> new ArrayList<>()).add(owner + " " + wall.side());
        }

        @Override
        public int clear(Chunk chunk, String owner) {
            List<String> here = standing.getOrDefault(new ChunkPos(chunk.getX(), chunk.getZ()), new ArrayList<>());
            int before = here.size();
            here.removeIf(wall -> wall.startsWith(owner + " "));
            return before - here.size();
        }
    };

    /** The chunk of the ground a wall closes: the block just inside its plane, in its middle. */
    private static ChunkPos chunkOf(WallSegment wall) {
        int middle = (wall.from() + wall.to()) / 2;
        return switch (wall.side()) {
            case NORTH -> ChunkPos.ofBlock(middle, wall.line());
            case SOUTH -> ChunkPos.ofBlock(middle, wall.line() - 1);
            case WEST -> ChunkPos.ofBlock(wall.line(), middle);
            case EAST -> ChunkPos.ofBlock(wall.line() - 1, middle);
        };
    }

    private final Map<IslandId, ChunkPos> origins = new HashMap<>();
    private final Map<IslandId, List<ChunkPos>> opened = new HashMap<>();

    @SuppressWarnings("NullAway.Init")
    private World world;

    @SuppressWarnings("NullAway.Init")
    private Island island;

    @SuppressWarnings("NullAway.Init")
    private ChunkBlockService service;

    @SuppressWarnings("NullAway.Init")
    private IslandProtectionListener islands;

    @SuppressWarnings("NullAway.Init")
    private TerritoryWalls walls;

    /** Runs every task at once, and counts the ones a chunk's own thread is asked for. */
    private final java.util.concurrent.atomic.AtomicInteger regionTasks =
            new java.util.concurrent.atomic.AtomicInteger();

    private final com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort scheduler =
            new com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort() {
                private final InlineSchedulerPort inline = new InlineSchedulerPort();

                @Override
                public void onRegion(String worldName, int chunkX, int chunkZ, Runnable task) {
                    regionTasks.incrementAndGet();
                    inline.onRegion(worldName, chunkX, chunkZ, task);
                }

                @Override
                public void onGlobal(Runnable task) {
                    inline.onGlobal(task);
                }

                @Override
                public void laterGlobal(java.time.Duration delay, Runnable task) {
                    inline.laterGlobal(delay, task);
                }

                @Override
                public void onEntity(PlayerUuid playerUuid, Runnable task) {
                    inline.onEntity(playerUuid, task);
                }

                @Override
                public void async(Runnable task) {
                    inline.async(task);
                }

                @Override
                public void asyncAfter(java.time.Duration delay, Runnable task) {
                    inline.asyncAfter(delay, task);
                }

                @Override
                public AutoCloseable repeatAsync(Runnable task, java.time.Duration first, java.time.Duration period) {
                    return inline.repeatAsync(task, first, period);
                }

                @Override
                public AutoCloseable repeatGlobal(Runnable task, java.time.Duration first, java.time.Duration period) {
                    return inline.repeatGlobal(task, first, period);
                }
            };

    @BeforeEach
    void setUpIsland() {
        world = server.addSimpleWorld("skyblock");
        island = Island.create(
                IslandId.of(UUID.randomUUID()),
                IslandBounds.fromCenterAndRadius(8, 8, 40),
                new PlayerUuid(UUID.randomUUID()),
                new ProfileId(UUID.randomUUID()),
                Instant.now());
        islands = new IslandProtectionListener(mock(IslandStoragePort.class), new IslandAccessService());
        islands.cacheIsland(island, "skyblock");
        service = new ChunkBlockService(memory(), new ChunkUnlockRules(List.of(1L, 1L), 1));
        service.start(island.id(), 8, 8);
        walls = new TerritoryWalls(
                service,
                islands,
                scheduler,
                painter,
                WallStyle.colour("mist", 0x8A93A1, 22),
                id -> Optional.of("skyblock"));
        service.whenOpened(walls::opened);
        service.whenClosed(walls::closed);
    }

    private List<String> wallsIn(int chunkX, int chunkZ) {
        return standing.getOrDefault(new ChunkPos(chunkX, chunkZ), List.of());
    }

    private void load(int chunkX, int chunkZ) {
        world.getChunkAt(chunkX, chunkZ).load();
    }

    @Test
    @DisplayName("A loading chunk of the island stands a wall on each closed side, and loading again stands no more")
    void aLoadingChunkStandsItsWalls() {
        load(0, 0);
        walls.onChunkLoad(new ChunkLoadEvent(world.getChunkAt(0, 0), false));
        walls.onChunkLoad(new ChunkLoadEvent(world.getChunkAt(0, 0), false));

        assertThat(wallsIn(0, 0)).hasSize(4);
    }

    @Test
    @DisplayName("Opening a chunk takes down the wall between the two and walls the new chunk's other sides")
    void openingRedraws() {
        load(0, 0);
        load(1, 0);
        walls.onChunkLoad(new ChunkLoadEvent(world.getChunkAt(0, 0), false));

        service.unlock(island.id(), new ChunkPos(1, 0), island.bounds(), 9);

        assertThat(wallsIn(0, 0)).describedAs("east side open now").hasSize(3).noneMatch(w -> w.endsWith("EAST"));
        assertThat(wallsIn(1, 0)).hasSize(3).noneMatch(w -> w.endsWith("WEST"));
    }

    @Test
    @DisplayName("Closing a chunk takes its walls down and walls its open neighbour again on that side")
    void closingRedraws() {
        load(0, 0);
        load(1, 0);
        service.unlock(island.id(), new ChunkPos(1, 0), island.bounds(), 9);
        assertThat(wallsIn(1, 0)).hasSize(3);

        service.onLevel(island.id(), 0);

        assertThat(wallsIn(1, 0)).isEmpty();
        assertThat(wallsIn(0, 0)).hasSize(4);
    }

    @Test
    @DisplayName("A chunk that is not loaded is left alone, and stands its walls when it loads")
    void anUnloadedChunkWaits() {
        load(0, 0);

        service.unlock(island.id(), new ChunkPos(1, 0), island.bounds(), 9);

        assertThat(wallsIn(1, 0))
                .describedAs("not loaded, so nothing stands there yet")
                .isEmpty();
        assertThat(wallsIn(0, 0)).hasSize(3);

        load(1, 0);
        walls.onChunkLoad(new ChunkLoadEvent(world.getChunkAt(1, 0), false));
        assertThat(wallsIn(1, 0)).hasSize(3);
    }

    @Test
    @DisplayName("A chunk of no ChunkBlock island stands nothing, and another island's wall is left standing")
    void otherGroundIsLeftAlone() {
        load(40, 40);
        load(0, 0);
        walls.onChunkLoad(new ChunkLoadEvent(world.getChunkAt(40, 40), false));
        assertThat(wallsIn(40, 40)).isEmpty();
        Island skyblock = Island.create(
                IslandId.of(UUID.randomUUID()),
                IslandBounds.fromCenterAndRadius(5120, 0, 40),
                new PlayerUuid(UUID.randomUUID()),
                new ProfileId(UUID.randomUUID()),
                Instant.now());
        islands.cacheIsland(skyblock, "skyblock");
        load(320, 0);
        walls.onChunkLoad(new ChunkLoadEvent(world.getChunkAt(320, 0), false));
        assertThat(regionTasks)
                .describedAs("an island of another mode asks its chunk for nothing")
                .hasValue(0);

        painter.draw(
                world, "someone-else", new WallSegment(WallSegment.Side.NORTH, 0, 0, 16), WallStyle.colour("x", 0, 50));
        walls.onChunkLoad(new ChunkLoadEvent(world.getChunkAt(0, 0), false));

        assertThat(wallsIn(0, 0)).hasSize(5).contains("someone-else NORTH");
    }

    private ChunkTerritoryPort memory() {
        return new ChunkTerritoryPort() {
            @Override
            public Map<IslandId, ChunkTerritory> findAll() {
                return Map.of();
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
            public void close(IslandId islandId, List<ChunkPos> chunks) {
                opened.getOrDefault(islandId, new ArrayList<>()).removeAll(chunks);
            }
        };
    }
}
