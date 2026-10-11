package com.uxplima.uxmskyblock.bukkit.chunkblock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.World;

import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.test.InlineSchedulerPort;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.bukkit.wall.WallConfiguration;
import com.uxplima.uxmskyblock.core.application.chunkblock.ChunkBlockService;
import com.uxplima.uxmskyblock.core.application.island.IslandAccessService;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkPos;
import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkUnlockRules;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * A Bedrock client draws no wall, so a Bedrock player on a ChunkBlock island is shown where its open ground ends as a
 * line of dust along every wall near them, three rows high, once a second, and nobody else is shown it.
 */
class ABedrockPlayerSeesTheEdgeTest extends MockBukkitHarness {

    private final ChunkBlockStores.Territories territories = new ChunkBlockStores.Territories();
    private final List<String> drawn = new ArrayList<>();
    private final List<Runnable> beats = new ArrayList<>();

    @SuppressWarnings("NullAway.Init")
    private World world;

    @SuppressWarnings("NullAway.Init")
    private Island island;

    @SuppressWarnings("NullAway.Init")
    private ChunkBlockService service;

    @SuppressWarnings("NullAway.Init")
    private IslandProtectionListener islands;

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
        service = new ChunkBlockService(territories, new ChunkUnlockRules(List.of(1L), 1));
        service.start(island.id(), 8, 8);
    }

    private BedrockEdges edges(java.util.function.Predicate<UUID> bedrock, int reach) {
        InlineSchedulerPort inline = new InlineSchedulerPort();
        SchedulerPort scheduler = new SchedulerPort() {
            @Override
            public AutoCloseable repeatGlobal(Runnable task, java.time.Duration first, java.time.Duration period) {
                beats.add(task);
                return () -> beats.remove(task);
            }

            @Override
            public void onGlobal(Runnable task) {
                inline.onGlobal(task);
            }

            @Override
            public void onRegion(String worldName, int chunkX, int chunkZ, Runnable task) {
                inline.onRegion(worldName, chunkX, chunkZ, task);
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
            public void laterGlobal(java.time.Duration delay, Runnable task) {
                inline.laterGlobal(delay, task);
            }

            @Override
            public AutoCloseable repeatAsync(Runnable task, java.time.Duration first, java.time.Duration period) {
                return inline.repeatAsync(task, first, period);
            }
        };
        return new BedrockEdges(
                service,
                islands,
                scheduler,
                bedrock,
                new WallConfiguration.Bedrock(true, 0x8A93A1, 1.0, reach),
                (player, x, y, z) -> drawn.add(player.getName() + " " + x + "," + y + "," + z));
    }

    @Test
    @DisplayName("Every side of one open chunk is dust a block apart, as far as the reach goes")
    void oneChunksEdge() {
        assertThat(BedrockEdges.points(Set.of(new ChunkPos(0, 0)), 8, 8, 12, 1.0))
                .hasSize(64);
        List<double[]> near = BedrockEdges.points(Set.of(new ChunkPos(0, 0)), 8, 8, 9, 1.0);
        assertThat(near)
                .describedAs("eight a side within nine blocks of the middle")
                .hasSize(32);
        assertThat(near)
                .allSatisfy(point ->
                        assertThat(Math.hypot(point[0] - 8, point[1] - 8)).isLessThanOrEqualTo(9));
        assertThat(BedrockEdges.points(Set.of(new ChunkPos(0, 0)), 8, 8, 12, 2.0))
                .describedAs("two blocks apart, half as many")
                .hasSize(32);
    }

    @Test
    @DisplayName("Where two open chunks meet there is no edge, and a chunk past the reach draws none")
    void sharedSidesAreOpen() {
        List<double[]> points = BedrockEdges.points(Set.of(new ChunkPos(0, 0), new ChunkPos(1, 0)), 16, 8, 30, 1.0);
        assertThat(points).hasSize(16 * 6);
        assertThat(points).noneMatch(point -> point[0] == 16 && point[1] > 0 && point[1] < 16);
        assertThat(BedrockEdges.points(Set.of(new ChunkPos(5, 5)), 8, 8, 12, 1.0))
                .isEmpty();
        assertThat(BedrockEdges.points(Set.of(new ChunkPos(0, 0), new ChunkPos(1, 0)), 15, 8, 12, 1.0))
                .describedAs(
                        "the walls of the open chunk beside the player's count too: eighteen north, eighteen south")
                .hasSize(36);
    }

    @Test
    @DisplayName("A Bedrock player on the island is shown the edge in three rows, and a Java player is shown nothing")
    void onlyBedrockPlayersSeeIt() {
        PlayerMock bedrock = createPlayer("Pocket");
        PlayerMock java = createPlayer("Desk");
        bedrock.setLocation(new Location(world, 8, 100, 8));
        java.setLocation(new Location(world, 8, 100, 8));
        AutoCloseable beat =
                edges(uuid -> uuid.equals(bedrock.getUniqueId()), 12).start();

        beats.getFirst().run();

        assertThat(drawn).hasSize(64 * 3).allMatch(line -> line.startsWith("Pocket "));
        assertThat(drawn.stream().map(line -> line.split(",", -1)[1]).distinct())
                .containsExactlyInAnyOrder("100.5", "101.5", "102.5");
        assertThat(beats).hasSize(1);
        try {
            beat.close();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        assertThat(beats).describedAs("closing the beat stops it").isEmpty();
    }

    @Test
    @DisplayName("Off a ChunkBlock island there is no edge to show")
    void offTheIslandNothing() {
        PlayerMock bedrock = createPlayer("Pocket");
        bedrock.setLocation(new Location(world, 5000, 100, 5000));
        assertThat(edges(uuid -> true, 12).show(bedrock)).isZero();
        assertThat(drawn).isEmpty();

        bedrock.setLocation(new Location(world, 8, 100, 8));
        assertThat(edges(uuid -> true, 12).show(bedrock)).isEqualTo(64 * 3);
    }
}
