package com.uxplima.uxmskyblock.bukkit.chunkblock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.schematic.IslandStart;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.chunkblock.ChunkBlockService;
import com.uxplima.uxmskyblock.core.application.chunkblock.ChunkTerritoryPort;
import com.uxplima.uxmskyblock.core.application.island.IslandAccessService;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkPos;
import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkTerritory;
import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkUnlockRules;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;
import com.uxplima.uxmskyblock.core.domain.preset.StarterPreset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * A ChunkBlock island's Nether keeps a web of its own: the chunks open there are its own, each web guards its own
 * world alone, a closing chunk moves nobody standing in the other world, and the start lays the web of the world it
 * runs in.
 */
class EachDimensionHoldsAWebOfItsOwnTest extends MockBukkitHarness {

    private static final int Y = 100;

    @SuppressWarnings("NullAway.Init")
    private World overworld;

    @SuppressWarnings("NullAway.Init")
    private World nether;

    @SuppressWarnings("NullAway.Init")
    private Island island;

    @SuppressWarnings("NullAway.Init")
    private ChunkBlockService above;

    @SuppressWarnings("NullAway.Init")
    private ChunkBlockService below;

    @SuppressWarnings("NullAway.Init")
    private ChunkBlockListener aboveEdge;

    @SuppressWarnings("NullAway.Init")
    private ChunkBlockListener belowEdge;

    @BeforeEach
    void setUpTwoWebs() {
        overworld = server.addSimpleWorld("skyblock");
        nether = server.addSimpleWorld("skyblock_nether");
        island = Island.create(
                IslandId.of(UUID.randomUUID()),
                IslandBounds.fromCenterAndRadius(8, 8, 40),
                new PlayerUuid(UUID.randomUUID()),
                new ProfileId(UUID.randomUUID()),
                Instant.now());
        IslandProtectionListener islands =
                new IslandProtectionListener(mock(IslandStoragePort.class), new IslandAccessService());
        islands.cacheIsland(island, "skyblock");
        islands.cacheIsland(island, "skyblock_nether");
        ChunkUnlockRules rules = new ChunkUnlockRules(List.of(1L, 1L), 1);
        above = new ChunkBlockService(memory(), rules);
        below = new ChunkBlockService(memory(), rules);
        above.start(island.id(), 8, 8);
        below.start(island.id(), 8, 8);
        // The overworld opened the chunk east of the first; the Nether the one south of it.
        above.unlock(island.id(), new ChunkPos(1, 0), island.bounds(), 9);
        below.unlock(island.id(), new ChunkPos(0, 1), island.bounds(), 9);

        SchedulerPort scheduler = mock(SchedulerPort.class);
        doAnswer(call -> {
                    call.getArgument(1, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .onEntity(any(PlayerUuid.class), any(Runnable.class));
        IslandLocation home = new IslandLocation(island.id(), "skyblock", island.bounds(), 8.5, 101.0, 8.5, 0, 0);
        aboveEdge = new ChunkBlockListener(above, islands, scheduler, Messages.bundled(), id -> Optional.of(home), "");
        aboveEdge.guarding(world -> !world.equals("skyblock_nether"));
        belowEdge = new ChunkBlockListener(below, islands, scheduler, Messages.bundled(), id -> Optional.of(home), "");
        belowEdge.guarding("skyblock_nether"::equals);
    }

    @Test
    @DisplayName("Each web refuses its own closed chunks in its own world, and leaves the other world to the other")
    void eachGuardsItsOwn() {
        PlayerMock player = createPlayer("Builder");
        // East is open above and closed below; south is open below and closed above.
        assertThat(refused(overworld, player, 20, 3)).isFalse();
        assertThat(refused(overworld, player, 3, 20)).isTrue();
        assertThat(refused(nether, player, 3, 20)).isFalse();
        assertThat(refused(nether, player, 20, 3)).isTrue();
        assertThat(refused(nether, player, 3, 3))
                .describedAs("the Nether's first chunk")
                .isFalse();
    }

    @Test
    @DisplayName("A chunk closing in the overworld moves whoever stands there, and nobody at the same place below")
    void aClosingMovesOnlyItsOwnWorld() {
        Stander there = new Stander(server, new Location(overworld, 20, Y, 3));
        Stander below_ = new Stander(server, new Location(nether, 20, Y, 3));
        server.addPlayer(there);
        server.addPlayer(below_);
        above.whenClosed(aboveEdge::moveOut);

        assertThat(above.onLevel(island.id(), 0)).containsExactly(new ChunkPos(1, 0));

        assertThat(there.getLocation().getX()).isEqualTo(8.5);
        assertThat(below_.getLocation().getWorld()).isEqualTo(nether);
        assertThat(below_.getLocation().getX()).describedAs("not moved").isEqualTo(20.0);
    }

    @Test
    @DisplayName("The start lays the web of the world it runs in, and the overworld's in any other")
    void theStartLaysItsWorldsWeb() {
        ChunkBlockService otherAbove = new ChunkBlockService(memory(), ChunkUnlockRules.shipped());
        ChunkBlockService otherBelow = new ChunkBlockService(memory(), ChunkUnlockRules.shipped());
        ChunkBlockStart start = new ChunkBlockStart(
                otherAbove, world -> world.equals("skyblock_nether") ? Optional.of(otherBelow) : Optional.empty());
        IslandId inNether = IslandId.of(UUID.randomUUID());
        IslandId inOverworld = IslandId.of(UUID.randomUUID());

        start.apply(new IslandStart(nether, inNether, 40, 70, 40, mock(StarterPreset.class)));
        start.apply(new IslandStart(overworld, inOverworld, 40, 100, 40, mock(StarterPreset.class)));

        assertThat(otherBelow.isOpen(inNether, new ChunkPos(2, 2))).contains(true);
        assertThat(otherAbove.isOpen(inNether, new ChunkPos(2, 2))).isEmpty();
        assertThat(otherAbove.isOpen(inOverworld, new ChunkPos(2, 2))).contains(true);
        assertThat(otherBelow.isOpen(inOverworld, new ChunkPos(2, 2))).isEmpty();
    }

    @Test
    @DisplayName("The chunks command of a player in the Nether opens the Nether's panel, and the overworld's its own")
    void thePanelOfTheWorldStoodIn() {
        ChunkBlockPanel below_ = mock(ChunkBlockPanel.class);
        ChunkBlockPanel main = new ChunkBlockPanel(
                above,
                mock(IslandStoragePort.class),
                mock(SchedulerPort.class),
                Messages.bundled(),
                uuid -> Optional.empty(),
                (islandId, profile) -> 0L);
        main.useElsewhere(world -> world.equals("skyblock_nether") ? Optional.of(below_) : Optional.empty());
        PlayerMock down = createPlayer("Down");
        down.setLocation(new Location(nether, 3, Y, 3));
        PlayerMock up = createPlayer("Up");
        up.setLocation(new Location(overworld, 3, Y, 3));

        main.open(down);
        main.unlockFaced(down);
        main.open(up);
        main.unlockFaced(up);

        org.mockito.Mockito.verify(below_).open(down);
        org.mockito.Mockito.verify(below_).unlockFaced(down);
        org.mockito.Mockito.verifyNoMoreInteractions(below_);
    }

    @Test
    @DisplayName("The window the engine answers through hands a player in the Nether to the Nether's window")
    void theWindowOfTheWorldStoodIn() {
        ChunkWindow main = window(above);
        ChunkWindow there = window(below);
        main.useElsewhere(world -> world.equals("skyblock_nether") ? Optional.of(there) : Optional.empty());
        PlayerMock down = createPlayer("Down");
        down.setLocation(new Location(nether, 3, Y, 3));
        PlayerMock up = createPlayer("Up");
        up.setLocation(new Location(overworld, 3, Y, 3));

        assertThat(main.forPlayer(down)).isSameAs(there);
        assertThat(main.forPlayer(up)).isSameAs(main);
        assertThat(there.forPlayer(down))
                .describedAs("a window of no others answers for itself")
                .isSameAs(there);
    }

    private ChunkWindow window(ChunkBlockService web) {
        return new ChunkWindow(
                mock(ChunkPurchase.class),
                web,
                mock(IslandStoragePort.class),
                mock(SchedulerPort.class),
                Messages.bundled(),
                id -> Optional.empty(),
                uuid -> Optional.empty(),
                (islandId, profile) -> 0L);
    }

    private boolean refused(World world, PlayerMock player, int x, int z) {
        Block block = world.getBlockAt(x, Y, z);
        BlockPlaceEvent event = new BlockPlaceEvent(
                block,
                block.getState(),
                world.getBlockAt(x, Y - 1, z),
                new ItemStack(Material.STONE),
                player,
                true,
                EquipmentSlot.HAND);
        aboveEdge.onPlace(event);
        belowEdge.onPlace(event);
        return event.isCancelled();
    }

    /** A player standing somewhere, whom a teleport really moves. */
    private static final class Stander extends PlayerMock {

        Stander(org.mockbukkit.mockbukkit.ServerMock server, Location at) {
            super(server, "Stander" + UUID.randomUUID().toString().substring(0, 4), UUID.randomUUID());
            setLocation(at);
        }

        @Override
        public java.util.concurrent.CompletableFuture<Boolean> teleportAsync(
                Location location,
                org.bukkit.event.player.PlayerTeleportEvent.TeleportCause cause,
                io.papermc.paper.entity.TeleportFlag... flags) {
            setLocation(location);
            return java.util.concurrent.CompletableFuture.completedFuture(true);
        }
    }

    /** One web's table in memory. */
    private static ChunkTerritoryPort memory() {
        Map<IslandId, ChunkPos> origins = new HashMap<>();
        Map<IslandId, List<ChunkPos>> opened = new HashMap<>();
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
