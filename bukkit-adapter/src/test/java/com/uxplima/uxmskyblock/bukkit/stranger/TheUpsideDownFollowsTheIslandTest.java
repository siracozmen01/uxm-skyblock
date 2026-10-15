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

    /** Every chunk is there at once, as MockBukkit has it: the mirror's own work is what these tests read. */
    private static final UpsideDownStart.ChunkLoads LOADED =
            (world, chunkX, chunkZ) -> java.util.concurrent.CompletableFuture.completedFuture(null);

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

    private boolean holdPlayers;
    private final List<Runnable> heldForPlayers = new ArrayList<>();

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
        doAnswer(call -> {
                    if (holdPlayers) {
                        heldForPlayers.add(call.getArgument(1, Runnable.class));
                    } else {
                        call.getArgument(1, Runnable.class).run();
                    }
                    return null;
                })
                .when(scheduler)
                .onEntity(any(UUID.class), any(Runnable.class));
        Realms realms = new Realms(service, islands, () -> UPSIDE_DOWN, () -> List.of("realms"));
        UpsideDownStart mirror = new UpsideDownStart(
                service,
                scheduler,
                new StrangerRealmsConfiguration.UpsideDown(16, List.of("GRASS_BLOCK:MYCELIUM")),
                () -> UPSIDE_DOWN,
                LOADED);
        Map<String, World> worlds = Map.of("realms", land, UPSIDE_DOWN, upsideDown);
        reach = new UpsideDownReach(
                realms,
                mirror,
                scheduler,
                worlds::get,
                (world, x, z) -> made.contains(world.getName() + ":" + x + "," + z),
                UpsideDownReach.copiedOnly(List.of("dirt", "GRASS_BLOCK", "*_LOG", " ")));
    }

    @Test
    @DisplayName(
            "A player in the Upside Down near a chunk hears it mirrored, once; one far off or on the land does not")
    void aMirrorIsHeardNearby() {
        List<String> fired = new ArrayList<>();
        server.getCommandMap().register("probe", new org.bukkit.command.Command("probe") {
            @Override
            public boolean execute(org.bukkit.command.CommandSender sender, String label, String[] args) {
                fired.add(String.join(" ", args));
                return true;
            }
        });
        reach.useEffects(
                new com.uxplima.uxmskyblock.bukkit.effect.InteractionEffectPlayer(),
                new com.uxplima.uxmskyblock.bukkit.effect.InteractionEffects(Map.of(
                        "upside-down-mirrored",
                        com.uxplima.uxmlib.condition.action.ActionList.parse(
                                List.of("[player] probe %chunk_x% %chunk_z%")))));
        org.mockbukkit.mockbukkit.entity.PlayerMock near = server.addPlayer("Near");
        near.teleport(new org.bukkit.Location(upsideDown, 40 + UpsideDownReach.HEARD_WITHIN - 9, Y, 8));
        org.mockbukkit.mockbukkit.entity.PlayerMock far = server.addPlayer("Far");
        far.teleport(new org.bukkit.Location(upsideDown, 40 + UpsideDownReach.HEARD_WITHIN + 1, Y, 8));
        org.mockbukkit.mockbukkit.entity.PlayerMock corner = server.addPlayer("Corner");
        corner.teleport(new org.bukkit.Location(upsideDown, -14, Y, -24));
        org.mockbukkit.mockbukkit.entity.PlayerMock ashore = server.addPlayer("Ashore");
        ashore.teleport(new org.bukkit.Location(land, 40, Y, 8));

        load(land, 2, 0, false);
        load(land, 2, 0, false);
        load(upsideDown, -2, -2, true);

        assertThat(fired)
                .describedAs("the late mirror of 2,0 heard by Near, the Upside Down's own -2,-2 by Corner")
                .containsExactly("2 0", "-2 -2");

        fired.clear();
        holdPlayers = true;
        load(land, 1, 0, false);
        near.teleport(new org.bukkit.Location(land, 24, Y, 8));
        corner.teleport(new org.bukkit.Location(land, 24, Y, 8));
        heldForPlayers.forEach(Runnable::run);
        assertThat(fired)
                .describedAs("a player who left the Upside Down before their turn came hears nothing")
                .isEmpty();
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
    @DisplayName("A late mirror copies only the ground the operator lists, and lays air for anything else")
    void onlyTheListedGroundIsCopied() {
        land.getBlockAt(40, Y, 8).setType(Material.DIAMOND_BLOCK);
        land.getBlockAt(41, Y, 8).setType(Material.CHEST);
        land.getBlockAt(42, Y, 8).setType(Material.RAW_IRON_BLOCK);
        land.getBlockAt(43, Y, 8).setType(Material.DIRT);
        land.getBlockAt(44, Y, 8).setType(Material.OAK_LOG);
        land.getBlockAt(45, Y, 8).setType(Material.COARSE_DIRT);
        land.getBlockAt(-20, Y, -20).setType(Material.COAL_BLOCK);

        load(land, 2, 0, false);
        load(upsideDown, -2, -2, true);

        assertThat(upsideDown.getBlockAt(40, Y, 8).getType()).isEqualTo(Material.AIR);
        assertThat(upsideDown.getBlockAt(41, Y, 8).getType()).isEqualTo(Material.AIR);
        assertThat(upsideDown.getBlockAt(42, Y, 8).getType())
                .describedAs("a block no list of worth names is still not ground")
                .isEqualTo(Material.AIR);
        assertThat(upsideDown.getBlockAt(43, Y, 8).getType()).isEqualTo(Material.DIRT);
        assertThat(upsideDown.getBlockAt(44, Y, 8).getType())
                .describedAs("a name with a star")
                .isEqualTo(Material.OAK_LOG);
        assertThat(upsideDown.getBlockAt(45, Y, 8).getType())
                .describedAs("a name without one is that block alone")
                .isEqualTo(Material.AIR);
        assertThat(upsideDown.getBlockAt(-20, Y, -20).getType())
                .describedAs("on the Upside Down's own side too")
                .isEqualTo(Material.AIR);
    }
}
