package com.uxplima.uxmskyblock.bukkit.boundary;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.event.world.ChunkLoadEvent;

import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.test.InlineSchedulerPort;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.bukkit.wall.WallConfiguration;
import com.uxplima.uxmskyblock.bukkit.wall.WallPainter;
import com.uxplima.uxmskyblock.bukkit.wall.WallStyle;
import com.uxplima.uxmskyblock.core.application.boundary.IslandEdgePort;
import com.uxplima.uxmskyblock.core.application.island.IslandAccessService;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkPos;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;
import com.uxplima.uxmskyblock.core.domain.wall.WallSegment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * An island's members choose how its edge is drawn: the client's border, a wall of a style walls.conf names, or
 * nothing. One that never chose keeps the server's default, and a ChunkBlock island edges its own ground instead.
 * A chosen wall stands along the edge, chunk by chunk.
 */
class AnIslandChoosesItsEdgeTest extends MockBukkitHarness {

    private static final WallStyle MIST = WallStyle.colour("mist", 0x8A93A1, 22);
    private static final WallStyle GLASS = WallStyle.block("glass", "minecraft:glass");

    private final Map<IslandId, String> stored = new HashMap<>();
    private final IslandEdgePort port = new IslandEdgePort() {
        @Override
        public Optional<String> find(IslandId islandId) {
            return Optional.ofNullable(stored.get(islandId));
        }

        @Override
        public Map<IslandId, String> findAll() {
            return Map.copyOf(stored);
        }

        @Override
        public void keep(IslandId islandId, String style) {
            stored.put(islandId, style);
        }
    };

    /** What stands in each chunk, by owner and wall, as the entities would. */
    private final Map<ChunkPos, List<String>> standing = new HashMap<>();

    private final WallPainter painter = new WallPainter() {
        @Override
        public void draw(World world, String owner, WallSegment wall, WallStyle style) {
            int ground =
                    switch (wall.side()) {
                        case NORTH, WEST -> wall.line();
                        case SOUTH, EAST -> wall.line() - 1;
                    };
            ChunkPos chunk = wall.side().alongX()
                    ? ChunkPos.ofBlock(wall.from(), ground)
                    : ChunkPos.ofBlock(ground, wall.from());
            standing.computeIfAbsent(chunk, at -> new ArrayList<>())
                    .add(owner + " " + style.id() + " " + wall.side() + " " + wall.from() + ".." + wall.to());
        }

        @Override
        public int clear(Chunk chunk, String owner) {
            List<String> here = standing.getOrDefault(new ChunkPos(chunk.getX(), chunk.getZ()), new ArrayList<>());
            int before = here.size();
            here.removeIf(wall -> wall.startsWith(owner + " "));
            return before - here.size();
        }
    };

    private final Set<IslandId> chunkBlock = new java.util.HashSet<>();
    private final List<IslandId> heard = new ArrayList<>();

    @SuppressWarnings("NullAway.Init")
    private World world;

    @SuppressWarnings("NullAway.Init")
    private Island island;

    @SuppressWarnings("NullAway.Init")
    private IslandEdges edges;

    @SuppressWarnings("NullAway.Init")
    private IslandEdgeWalls walls;

    @BeforeEach
    void setUp() {
        world = server.addSimpleWorld("skyblock");
        island = Island.create(
                IslandId.of(UUID.randomUUID()),
                IslandBounds.fromCenterAndRadius(8, 8, 40),
                new PlayerUuid(UUID.randomUUID()),
                new ProfileId(UUID.randomUUID()),
                Instant.now());
        IslandProtectionListener islands = new IslandProtectionListener(
                org.mockito.Mockito.mock(IslandStoragePort.class), new IslandAccessService());
        islands.cacheIsland(island, "skyblock");
        WallConfiguration config = new WallConfiguration(
                Map.of("mist", MIST, "glass", GLASS),
                "mist",
                -64,
                320,
                64,
                WallConfiguration.Bedrock.SHIPPED,
                "border");
        edges = new IslandEdges(port, config, chunkBlock::contains);
        edges.whenChanged(heard::add);
        walls = new IslandEdgeWalls(
                edges,
                islands,
                new InlineSchedulerPort(),
                painter,
                id -> Optional.of(new IslandLocation(id, "skyblock", island.bounds(), 8, 100, 8, 0, 0)));
        edges.whenChanged(walls::changed);
    }

    private void load(int chunkX, int chunkZ) {
        world.getChunkAt(chunkX, chunkZ).load();
        walls.onChunkLoad(new ChunkLoadEvent(world.getChunkAt(chunkX, chunkZ), false));
    }

    private List<String> wallsIn(int chunkX, int chunkZ) {
        return standing.getOrDefault(new ChunkPos(chunkX, chunkZ), List.of());
    }

    @Test
    @DisplayName("An island that never chose keeps the default; a choice is kept, heard, and a wrong one refused")
    void aChoiceIsKept() {
        assertThat(edges.styleOf(island.id())).isEqualTo("border");
        assertThat(edges.drawsBorder(island.id())).isTrue();
        assertThat(edges.wallOf(island.id())).isEmpty();
        assertThat(edges.choices()).containsExactly("border", "glass", "mist", "none");

        assertThat(edges.choose(island.id(), "mist")).isTrue();
        assertThat(stored).containsEntry(island.id(), "mist");
        assertThat(heard).containsExactly(island.id());
        assertThat(edges.styleOf(island.id())).isEqualTo("mist");
        assertThat(edges.drawsBorder(island.id())).isFalse();
        assertThat(edges.wallOf(island.id())).contains(MIST);

        assertThat(edges.choose(island.id(), "lava")).isFalse();
        assertThat(edges.styleOf(island.id())).isEqualTo("mist");
        assertThat(heard).hasSize(1);

        assertThat(edges.choose(island.id(), "none")).isTrue();
        assertThat(edges.drawsBorder(island.id())).isFalse();
        assertThat(edges.wallOf(island.id())).isEmpty();
    }

    @Test
    @DisplayName("Choices are read at start, and one of a style taken out of the file since is the default again")
    void choicesAreReadAtStart() {
        IslandId other = IslandId.of(UUID.randomUUID());
        stored.put(island.id(), "glass");
        stored.put(other, "lava");
        assertThat(edges.prime()).isEqualTo(2);
        assertThat(edges.styleOf(island.id())).isEqualTo("glass");
        assertThat(edges.styleOf(other)).isEqualTo("border");
    }

    @Test
    @DisplayName("A ChunkBlock island draws nothing at its edge, and its members cannot choose")
    void aChunkBlockIslandHasItsOwnEdge() {
        chunkBlock.add(island.id());
        stored.put(island.id(), "mist");
        edges.prime();
        assertThat(edges.styleOf(island.id())).isEqualTo("none");
        assertThat(edges.drawsBorder(island.id())).isFalse();
        assertThat(edges.choosable(island.id())).isFalse();
        assertThat(edges.choose(island.id(), "glass")).isFalse();
        assertThat(stored).containsEntry(island.id(), "mist");
    }

    @Test
    @DisplayName("Each chunk the edge runs through holds the stretch that closes its own ground, cut at its sides")
    void theEdgeIsCutIntoChunks() {
        IslandBounds bounds = IslandBounds.fromCenterAndRadius(8, 8, 40);
        assertThat(IslandEdgeWalls.segmentsIn(bounds, new ChunkPos(-2, 0)))
                .containsExactly(new WallSegment(WallSegment.Side.WEST, -32, 0, 16));
        assertThat(IslandEdgeWalls.segmentsIn(bounds, new ChunkPos(3, 0)))
                .containsExactly(new WallSegment(WallSegment.Side.EAST, 49, 0, 16));
        assertThat(IslandEdgeWalls.segmentsIn(bounds, new ChunkPos(-2, -2)))
                .containsExactlyInAnyOrder(
                        new WallSegment(WallSegment.Side.NORTH, -32, -32, -16),
                        new WallSegment(WallSegment.Side.WEST, -32, -32, -16));
        assertThat(IslandEdgeWalls.segmentsIn(bounds, new ChunkPos(3, 3)))
                .containsExactlyInAnyOrder(
                        new WallSegment(WallSegment.Side.SOUTH, 49, 48, 49),
                        new WallSegment(WallSegment.Side.EAST, 49, 48, 49));
        assertThat(IslandEdgeWalls.segmentsIn(bounds, new ChunkPos(0, 0))).isEmpty();
        assertThat(IslandEdgeWalls.segmentsIn(bounds, new ChunkPos(4, 0)))
                .describedAs("past the edge")
                .isEmpty();
        IslandBounds onTheLine = IslandBounds.fromCenterAndRadius(8, 8, 39);
        assertThat(IslandEdgeWalls.segmentsIn(onTheLine, new ChunkPos(0, 2)))
                .describedAs("an edge on a chunk's side belongs to the chunk of the ground it closes")
                .containsExactly(new WallSegment(WallSegment.Side.SOUTH, 48, 0, 16));
        assertThat(IslandEdgeWalls.segmentsIn(onTheLine, new ChunkPos(0, 3))).isEmpty();
        assertThat(IslandEdgeWalls.segmentsIn(onTheLine, new ChunkPos(2, 0)))
                .containsExactly(new WallSegment(WallSegment.Side.EAST, 48, 0, 16));
        assertThat(IslandEdgeWalls.edgeChunks(bounds)).hasSize(20).contains(new ChunkPos(-2, 0), new ChunkPos(3, 3));
    }

    @Test
    @DisplayName("A chosen wall stands as its chunks load, moves to another style, and comes down for the border")
    void theWallFollowsTheChoice() {
        String owner = "edge:" + island.id().value();
        load(-2, 0);
        assertThat(wallsIn(-2, 0)).describedAs("the border draws no wall").isEmpty();

        edges.choose(island.id(), "mist");
        assertThat(wallsIn(-2, 0)).containsExactly(owner + " mist WEST 0..16");
        load(-2, 0);
        assertThat(wallsIn(-2, 0)).describedAs("loading again stands no more").hasSize(1);

        edges.choose(island.id(), "glass");
        assertThat(wallsIn(-2, 0)).containsExactly(owner + " glass WEST 0..16");

        load(0, 0);
        assertThat(wallsIn(0, 0)).describedAs("no edge runs through the middle").isEmpty();

        edges.choose(island.id(), "border");
        assertThat(wallsIn(-2, 0)).isEmpty();
    }

    @Test
    @DisplayName("An island that grows takes its wall down at the old edge and stands it at the new one")
    void aGrowingIslandMovesItsWall() {
        edges.choose(island.id(), "mist");
        load(-2, 0);
        load(-4, 0);
        IslandBounds bigger = IslandBounds.fromCenterAndRadius(8, 8, 72);

        walls.moved(island.id(), "skyblock", island.bounds(), bigger);

        assertThat(wallsIn(-2, 0)).isEmpty();
        assertThat(wallsIn(-4, 0)).containsExactly("edge:" + island.id().value() + " mist WEST 0..16");
    }
}
