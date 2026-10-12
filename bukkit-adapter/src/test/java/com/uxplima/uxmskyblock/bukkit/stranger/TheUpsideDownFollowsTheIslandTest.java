package com.uxplima.uxmskyblock.bukkit.stranger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.event.world.ChunkLoadEvent;

import com.uxplima.uxmskyblock.bukkit.config.StrangerRealmsConfiguration;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.island.IslandAccessService;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.stranger.StrangerRealmsPort;
import com.uxplima.uxmskyblock.core.application.stranger.StrangerRealmsService;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Upside Down was laid once, as far as the radius reached when the island was made, and the island grew past
 * it. A chunk of the island's land is mirrored the first time it loads on either side, while its Upside Down has
 * never been made, and never over an Upside Down chunk that already exists.
 */
class TheUpsideDownFollowsTheIslandTest extends MockBukkitHarness {

    private static final String UPSIDE_DOWN = "skyblock_nether";
    private static final int Y = 64;

    private final Set<IslandId> stranger = new HashSet<>();
    private final Set<String> made = new HashSet<>();
    private final List<String> regions = new ArrayList<>();

    @SuppressWarnings("NullAway.Init")
    private World land;

    @SuppressWarnings("NullAway.Init")
    private World upsideDown;

    @SuppressWarnings("NullAway.Init")
    private UpsideDownReach reach;

    @BeforeEach
    void setUp() {
        land = server.addSimpleWorld("realms");
        upsideDown = server.addSimpleWorld(UPSIDE_DOWN);
        IslandProtectionListener islands =
                new IslandProtectionListener(mock(IslandStoragePort.class), new IslandAccessService());
        // Blocks -32 to 48 each way: chunks -2 to 3.
        Island island = Island.create(
                IslandId.of(UUID.randomUUID()),
                IslandBounds.fromCenterAndRadius(8, 8, 40),
                new PlayerUuid(UUID.randomUUID()),
                new ProfileId(UUID.randomUUID()),
                Instant.now());
        islands.cacheIsland(island, "realms");
        stranger.add(island.id());
        StrangerRealmsService service = new StrangerRealmsService(new StrangerRealmsPort() {
            @Override
            public Set<IslandId> findAll() {
                return Set.copyOf(stranger);
            }

            @Override
            public boolean exists(IslandId islandId) {
                return stranger.contains(islandId);
            }

            @Override
            public void add(IslandId islandId) {
                stranger.add(islandId);
            }

            @Override
            public int farthestReach() {
                return 0;
            }
        });
        service.prime();
        SchedulerPort scheduler = mock(SchedulerPort.class);
        doAnswer(call -> {
                    regions.add(call.getArgument(0) + ":" + call.getArgument(1) + "," + call.getArgument(2));
                    call.getArgument(3, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .onRegion(anyString(), anyInt(), anyInt(), any(Runnable.class));
        Realms realms = new Realms(service, islands, () -> UPSIDE_DOWN, () -> List.of("realms"));
        UpsideDownStart mirror = new UpsideDownStart(
                service,
                scheduler,
                new StrangerRealmsConfiguration.UpsideDown(16, List.of("GRASS_BLOCK:MYCELIUM")),
                () -> UPSIDE_DOWN);
        Map<String, World> worlds = Map.of("realms", land, UPSIDE_DOWN, upsideDown);
        reach = new UpsideDownReach(
                realms,
                mirror,
                scheduler,
                worlds::get,
                (world, x, z) -> made.contains(world.getName() + ":" + x + "," + z),
                UpsideDownReach.copiedUnless(
                        Set.of("minecraft:diamond_block"), material -> material == Material.CHEST));
    }

    private void load(World world, int chunkX, int chunkZ, boolean fresh) {
        reach.onChunkLoad(new ChunkLoadEvent(world.getChunkAt(chunkX, chunkZ), fresh));
    }

    @Test
    @DisplayName("Land the island grew into is mirrored when it loads, and the Nether that was there is laid over")
    void grownLandIsMirrored() {
        land.getBlockAt(40, Y, 8).setType(Material.GRASS_BLOCK);
        upsideDown.getBlockAt(40, Y + 3, 8).setType(Material.NETHERRACK);

        load(land, 2, 0, false);

        assertThat(upsideDown.getBlockAt(40, Y, 8).getType()).isEqualTo(Material.MYCELIUM);
        assertThat(upsideDown.getBlockAt(40, Y + 3, 8).getType())
                .describedAs("the land has air there")
                .isEqualTo(Material.AIR);
        assertThat(regions)
                .describedAs("asked on the Upside Down's region, read on the land's, laid on the Upside Down's")
                .containsExactly(UPSIDE_DOWN + ":2,0", "realms:2,0", UPSIDE_DOWN + ":2,0");
    }

    @Test
    @DisplayName("An Upside Down chunk that already exists is never laid over, and a chunk is asked about once")
    void anExistingUpsideDownIsKept() {
        made.add(UPSIDE_DOWN + ":2,0");
        land.getBlockAt(40, Y, 8).setType(Material.GRASS_BLOCK);
        upsideDown.getBlockAt(40, Y, 8).setType(Material.CHEST);

        load(land, 2, 0, false);
        load(land, 2, 0, false);

        assertThat(upsideDown.getBlockAt(40, Y, 8).getType())
                .describedAs("what was built there stays")
                .isEqualTo(Material.CHEST);
        assertThat(regions).containsExactly(UPSIDE_DOWN + ":2,0");
    }

    @Test
    @DisplayName("A chunk the Upside Down makes on its own side is mirrored from the land, an old one is left")
    void theUpsideDownsOwnNewChunk() {
        land.getBlockAt(-20, Y, -20).setType(Material.GRASS_BLOCK);
        upsideDown.getBlockAt(-20, Y, -20).setType(Material.NETHERRACK);

        load(upsideDown, -2, -2, false);
        assertThat(upsideDown.getBlockAt(-20, Y, -20).getType()).isEqualTo(Material.NETHERRACK);

        load(upsideDown, -2, -2, true);
        assertThat(upsideDown.getBlockAt(-20, Y, -20).getType()).isEqualTo(Material.MYCELIUM);
        assertThat(regions).containsExactly("realms:-2,-2", UPSIDE_DOWN + ":-2,-2");
    }

    @Test
    @DisplayName("The Upside Down's own load of a chunk the land is mirroring does not mirror it again")
    void aMirrorIsLaidOnce() {
        load(land, 1, 1, false);
        int laid = regions.size();

        load(upsideDown, 1, 1, true);

        assertThat(regions).hasSize(laid);
    }

    @Test
    @DisplayName("A chunk on the island's edge is mirrored, one past it and land of no StrangerRealms island are not")
    void onlyTheIslandsChunks() {
        load(land, 3, 0, false);
        assertThat(regions).describedAs("the edge chunk, partly the island's").isNotEmpty();
        regions.clear();

        load(land, 4, 0, false);
        load(land, 40, 40, false);
        load(upsideDown, 40, 40, true);

        assertThat(regions).isEmpty();
    }

    @Test
    @DisplayName("A late mirror lays air for a block worth something or one that holds things, and copies the rest")
    void whatIsWorthSomethingIsNotHadTwice() {
        land.getBlockAt(40, Y, 8).setType(Material.DIAMOND_BLOCK);
        land.getBlockAt(41, Y, 8).setType(Material.CHEST);
        land.getBlockAt(42, Y, 8).setType(Material.DIRT);
        land.getBlockAt(-20, Y, -20).setType(Material.DIAMOND_BLOCK);

        load(land, 2, 0, false);
        load(upsideDown, -2, -2, true);

        assertThat(upsideDown.getBlockAt(40, Y, 8).getType()).isEqualTo(Material.AIR);
        assertThat(upsideDown.getBlockAt(41, Y, 8).getType()).isEqualTo(Material.AIR);
        assertThat(upsideDown.getBlockAt(42, Y, 8).getType()).isEqualTo(Material.DIRT);
        assertThat(upsideDown.getBlockAt(-20, Y, -20).getType())
                .describedAs("on the Upside Down's own side too")
                .isEqualTo(Material.AIR);
    }
}
