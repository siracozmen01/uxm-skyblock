package com.uxplima.uxmskyblock.bukkit.inactivity;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmskyblock.bukkit.freeze.BukkitIslandVisitorEvictionAdapter;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.recycle.FoliaIslandVoidingAdapter;
import com.uxplima.uxmskyblock.bukkit.spatial.SpatialIslandIndex;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.world.SpiralSlotPoolPort;
import com.uxplima.uxmskyblock.core.application.world.WorldGridAllocationPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;
import com.uxplima.uxmskyblock.core.domain.world.WorldGridAllocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BukkitIslandInactivityAdaptersTest {

    private IslandProtectionListener protectionListener;
    private SpatialIslandIndex spatialIndex;
    private BukkitIslandVisitorEvictionAdapter evictionAdapter;
    private IslandStoragePort islandStoragePort;
    private WorldGridAllocationPort worldGridAllocationPort;
    private SpiralSlotPoolPort spiralSlotPoolPort;
    private FoliaIslandVoidingAdapter voidingAdapter;

    private IslandId islandId;

    @BeforeEach
    void setUp() {
        protectionListener = mock(IslandProtectionListener.class);
        spatialIndex = mock(SpatialIslandIndex.class);
        when(protectionListener.spatialIndex()).thenReturn(spatialIndex);

        evictionAdapter = mock(BukkitIslandVisitorEvictionAdapter.class);
        islandStoragePort = mock(IslandStoragePort.class);
        worldGridAllocationPort = mock(WorldGridAllocationPort.class);
        spiralSlotPoolPort = mock(SpiralSlotPoolPort.class);
        voidingAdapter = mock(FoliaIslandVoidingAdapter.class);
        when(voidingAdapter.voidIslandChunks(any(), any(), any()))
                .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(null));

        islandId = IslandId.of(UUID.randomUUID());
    }

    @Test
    @DisplayName("archival adapter evicts visitors and removes island from spatial index")
    void archivalAdapterEvictsVisitorsAndRemovesFromSpatialIndex() {
        BukkitIslandArchivalAdapter adapter = new BukkitIslandArchivalAdapter(protectionListener, evictionAdapter);

        adapter.archiveIsland(islandId);

        verify(evictionAdapter).evictNonStaffVisitors(eq(islandId), any(String.class));
        verify(spatialIndex).removeIsland(islandId);
    }

    @Test
    @DisplayName("archival adapter succeeds when optional collaborators are null")
    void archivalAdapterHandlesNullCollaborators() {
        BukkitIslandArchivalAdapter adapter = new BukkitIslandArchivalAdapter(null, null);

        adapter.archiveIsland(islandId);
    }

    @Test
    @DisplayName("recycle adapter evicts visitors, unindexes, voids chunks, and releases spiral slot")
    void recycleAdapterPerformsFullTeardown() {
        BukkitIslandRecycleAdapter adapter = new BukkitIslandRecycleAdapter(
                islandStoragePort,
                worldGridAllocationPort,
                spiralSlotPoolPort,
                voidingAdapter,
                protectionListener,
                evictionAdapter);

        IslandBounds bounds = IslandBounds.fromCenterAndRadius(0, 0, 100);
        IslandLocation location = new IslandLocation(islandId, "skyblock_world", bounds, 0, 100, 0, 0, 0);
        when(islandStoragePort.findLocationByIslandId(islandId)).thenReturn(Optional.of(location));

        WorldGridAllocation allocation = new WorldGridAllocation(
                42L,
                "skyblock_world",
                100,
                200,
                Optional.of(islandId),
                com.uxplima.uxmskyblock.core.domain.session.ServerNodeId.of("node-1"),
                java.time.Instant.now());
        when(worldGridAllocationPort.findByIslandId(islandId)).thenReturn(Optional.of(allocation));

        adapter.recycleIsland(islandId);

        verify(evictionAdapter).evictNonStaffVisitors(eq(islandId), any(String.class));
        verify(spatialIndex).removeIsland(islandId);
        verify(voidingAdapter).voidIslandChunks(islandId, "skyblock_world", bounds);
        verify(spiralSlotPoolPort).releaseSlot(42L, "skyblock_world", 100, 200);
    }

    @Test
    @DisplayName("recycle adapter hands the slot on only once the island is cleared, and keeps it when clearing fails")
    void theSlotWaitsForTheClearing() {
        BukkitIslandRecycleAdapter adapter = new BukkitIslandRecycleAdapter(
                islandStoragePort, worldGridAllocationPort, spiralSlotPoolPort, voidingAdapter, null, null);
        IslandBounds bounds = IslandBounds.fromCenterAndRadius(100, 200, 25);
        when(islandStoragePort.findLocationByIslandId(islandId))
                .thenReturn(Optional.of(new IslandLocation(islandId, "skyblock_world", bounds, 100, 100, 200, 0, 0)));
        when(worldGridAllocationPort.findByIslandId(islandId))
                .thenReturn(Optional.of(new WorldGridAllocation(
                        42L,
                        "skyblock_world",
                        100,
                        200,
                        Optional.of(islandId),
                        com.uxplima.uxmskyblock.core.domain.session.ServerNodeId.of("node-1"),
                        java.time.Instant.now())));
        java.util.concurrent.CompletableFuture<Void> clearing = new java.util.concurrent.CompletableFuture<>();
        when(voidingAdapter.voidIslandChunks(any(), any(), any())).thenReturn(clearing);

        adapter.recycleIsland(islandId);

        verify(spiralSlotPoolPort, never()).releaseSlot(any(Long.class), any(), any(Integer.class), any(Integer.class));
        clearing.complete(null);
        verify(spiralSlotPoolPort).releaseSlot(42L, "skyblock_world", 100, 200);

        java.util.concurrent.CompletableFuture<Void> failed = new java.util.concurrent.CompletableFuture<>();
        when(voidingAdapter.voidIslandChunks(any(), any(), any())).thenReturn(failed);
        adapter.recycleIsland(islandId);
        failed.completeExceptionally(new IllegalStateException("region gone"));
        verify(spiralSlotPoolPort, org.mockito.Mockito.times(1))
                .releaseSlot(any(Long.class), any(), any(Integer.class), any(Integer.class));
    }

    @Test
    @DisplayName("recycle adapter clears everywhere the island wrote, and forgets it once cleared")
    void everywhereTheIslandWrote() {
        BukkitIslandRecycleAdapter adapter = new BukkitIslandRecycleAdapter(
                islandStoragePort, worldGridAllocationPort, spiralSlotPoolPort, voidingAdapter, null, null);
        IslandBounds bounds = IslandBounds.fromCenterAndRadius(100, 200, 25);
        when(islandStoragePort.findLocationByIslandId(islandId))
                .thenReturn(Optional.of(new IslandLocation(islandId, "skyblock_world", bounds, 100, 100, 200, 0, 0)));
        com.uxplima.uxmskyblock.core.application.recycle.IslandFootprintPort footprints =
                mock(com.uxplima.uxmskyblock.core.application.recycle.IslandFootprintPort.class);
        when(footprints.of(islandId))
                .thenReturn(java.util.List.of(
                        new com.uxplima.uxmskyblock.core.domain.recycle.IslandFootprint("skyblock_world", 100, 200, 48),
                        new com.uxplima.uxmskyblock.core.domain.recycle.IslandFootprint(
                                "skyblock_nether", 100, 200, 0)));
        adapter.useWipeAreas(new com.uxplima.uxmskyblock.core.application.recycle.IslandWipeAreas(
                footprints, island -> java.util.OptionalInt.empty(), 0));

        adapter.recycleIsland(islandId);

        verify(voidingAdapter)
                .voidIslandChunks(islandId, "skyblock_world", IslandBounds.fromCenterAndRadius(100, 200, 48));
        verify(voidingAdapter).voidIslandChunks(islandId, "skyblock_nether", bounds);
        verify(footprints).forget(islandId);
    }

    @Test
    @DisplayName("recycle adapter handles missing location and allocation gracefully")
    void recycleAdapterHandlesMissingRecordsGracefully() {
        BukkitIslandRecycleAdapter adapter = new BukkitIslandRecycleAdapter(
                islandStoragePort,
                worldGridAllocationPort,
                spiralSlotPoolPort,
                voidingAdapter,
                protectionListener,
                evictionAdapter);

        when(islandStoragePort.findLocationByIslandId(islandId)).thenReturn(Optional.empty());
        when(worldGridAllocationPort.findByIslandId(islandId)).thenReturn(Optional.empty());

        adapter.recycleIsland(islandId);

        verify(evictionAdapter).evictNonStaffVisitors(eq(islandId), any(String.class));
        verify(spatialIndex).removeIsland(islandId);
        verify(voidingAdapter, never()).voidIslandChunks(any(), any(), any());
        verify(spiralSlotPoolPort, never()).releaseSlot(any(Long.class), any(), any(Integer.class), any(Integer.class));
    }
}
