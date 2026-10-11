package com.uxplima.uxmskyblock.bukkit.inactivity;

import java.util.Objects;
import java.util.Optional;

import com.uxplima.uxmskyblock.bukkit.freeze.BukkitIslandVisitorEvictionAdapter;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.recycle.FoliaIslandVoidingAdapter;
import com.uxplima.uxmskyblock.core.application.inactivity.IslandRecyclePort;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.recycle.IslandWipeAreas;
import com.uxplima.uxmskyblock.core.application.world.SpiralSlotPoolPort;
import com.uxplima.uxmskyblock.core.application.world.WorldGridAllocationPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;
import com.uxplima.uxmskyblock.core.domain.world.WorldGridAllocation;
import org.jspecify.annotations.Nullable;

/**
 * Platform adapter implementing {@link IslandRecyclePort} to evict visitors, purge spatial
 * caching, trigger chunk voiding, and release the Archimedean spiral slot on team abandonment.
 */
public final class BukkitIslandRecycleAdapter implements IslandRecyclePort {

    private final IslandStoragePort islandStoragePort;
    private final WorldGridAllocationPort worldGridAllocationPort;
    private final SpiralSlotPoolPort spiralSlotPoolPort;
    private final @Nullable FoliaIslandVoidingAdapter voidingAdapter;
    private final @Nullable IslandProtectionListener protectionListener;
    private final @Nullable BukkitIslandVisitorEvictionAdapter visitorEvictionAdapter;

    public BukkitIslandRecycleAdapter(
            IslandStoragePort islandStoragePort,
            WorldGridAllocationPort worldGridAllocationPort,
            SpiralSlotPoolPort spiralSlotPoolPort,
            @Nullable FoliaIslandVoidingAdapter voidingAdapter,
            @Nullable IslandProtectionListener protectionListener,
            @Nullable BukkitIslandVisitorEvictionAdapter visitorEvictionAdapter) {
        this.islandStoragePort = Objects.requireNonNull(islandStoragePort, "islandStoragePort must not be null");
        this.worldGridAllocationPort =
                Objects.requireNonNull(worldGridAllocationPort, "worldGridAllocationPort must not be null");
        this.spiralSlotPoolPort = Objects.requireNonNull(spiralSlotPoolPort, "spiralSlotPoolPort must not be null");
        this.voidingAdapter = voidingAdapter;
        this.protectionListener = protectionListener;
        this.visitorEvictionAdapter = visitorEvictionAdapter;
    }

    @Override
    @SuppressWarnings("FutureReturnValueIgnored")
    public void recycleIsland(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");

        if (visitorEvictionAdapter != null) {
            visitorEvictionAdapter.evictNonStaffVisitors(islandId, "inactivity.recycled_reason");
        }
        if (protectionListener != null) {
            protectionListener.spatialIndex().removeIsland(islandId);
        }

        Optional<IslandLocation> optLocation = islandStoragePort.findLocationByIslandId(islandId);
        FoliaIslandVoidingAdapter voiding = voidingAdapter;
        java.util.concurrent.CompletableFuture<Void> cleared;
        if (optLocation.isPresent() && voiding != null) {
            IslandLocation loc = optLocation.get();
            IslandWipeAreas areas = this.wipeAreas;
            cleared = java.util.concurrent.CompletableFuture.allOf(
                            areas.of(islandId, loc.worldName(), loc.bounds()).stream()
                                    .map(area -> voiding.voidIslandChunks(islandId, area.world(), area.bounds()))
                                    .toArray(java.util.concurrent.CompletableFuture[]::new))
                    .thenRun(() -> areas.cleared(islandId));
        } else {
            cleared = java.util.concurrent.CompletableFuture.completedFuture(null);
        }

        // The slot is handed to the next island only once the old one is gone from it. Handed over at once, a new
        // island could be laid where the clearing had not reached yet, and be cleared with the old one.
        Optional<WorldGridAllocation> optAlloc = worldGridAllocationPort.findByIslandId(islandId);
        if (optAlloc.isPresent()) {
            WorldGridAllocation alloc = optAlloc.get();
            cleared.whenComplete((done, failure) -> {
                if (failure != null) {
                    LOGGER.log(
                            java.util.logging.Level.WARNING,
                            "Island " + islandId + " was not cleared, so its slot is kept from the next island",
                            failure);
                    return;
                }
                spiralSlotPoolPort.releaseSlot(
                        alloc.sequenceIndex(), alloc.worldName(), alloc.centerX(), alloc.centerZ());
            });
        }
    }

    private static final java.util.logging.Logger LOGGER =
            java.util.logging.Logger.getLogger(BukkitIslandRecycleAdapter.class.getName());

    /** Every square an island is cleared over: its border, and wherever its game mode wrote past it. */
    private volatile IslandWipeAreas wipeAreas = IslandWipeAreas.bordersOnly();

    /** Clears an island over every square it wrote in, not only its border. */
    public void useWipeAreas(IslandWipeAreas areas) {
        this.wipeAreas = Objects.requireNonNull(areas, "areas must not be null");
    }
}
