package com.uxplima.uxmskyblock.core.application.recycle;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.uxplima.uxmskyblock.core.domain.event.EventId;
import com.uxplima.uxmskyblock.core.domain.event.JsonText;
import com.uxplima.uxmskyblock.core.domain.event.StagedOutboxEvent;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;
import com.uxplima.uxmskyblock.core.domain.recycle.IslandRecycleOperation;
import com.uxplima.uxmskyblock.core.domain.recycle.IslandRecycleState;
import com.uxplima.uxmskyblock.core.domain.world.WorldGridAllocation;

/**
 * One island reset carried out step by step under its journal, and the steps a crash left half done carried on at start. Split out of {@link IslandRecycleService}, whose services and words it uses, so neither file is over the size the
 * standards draw a line at.
 */
final class RecycleRuns {

    private final IslandRecycleService host;

    RecycleRuns(IslandRecycleService host) {
        this.host = host;
    }

    /** The erasure itself, with the island's slot already taken. */
    CompletableFuture<IslandRecycleService.RecycleResult> runReset(
            Island island, IslandId islandId, boolean byItsOwner) {
        Optional<IslandLocation> optLocation = host.islandStoragePort.findLocationByIslandId(islandId);
        if (optLocation.isEmpty()) {
            return CompletableFuture.completedFuture(new IslandRecycleService.RecycleResult.Failure(
                    "Island location record missing for islandId=" + islandId));
        }
        IslandLocation location = optLocation.get();

        // Resolve world grid allocation
        long slotIndex = 0L;
        String worldName = location.worldName();
        int gridX = location.bounds().centerX();
        int gridZ = location.bounds().centerZ();

        Optional<WorldGridAllocation> optAlloc = host.worldGridAllocationPort.findByIslandId(islandId);
        if (optAlloc.isPresent()) {
            WorldGridAllocation alloc = optAlloc.get();
            slotIndex = alloc.sequenceIndex();
            worldName = alloc.worldName();
            gridX = alloc.centerX();
            gridZ = alloc.centerZ();
        }

        final long finalSlotIndex = slotIndex;
        final String finalWorldName = worldName;
        final int finalGridX = gridX;
        final int finalGridZ = gridZ;

        String operationId = UUID.randomUUID().toString();
        Instant now = host.clock.instant();

        // 1. Transaction State: REQUESTED
        if (host.recycleOperationPort != null) {
            host.recycleOperationPort.recordOperation(new IslandRecycleOperation(
                    operationId,
                    islandId,
                    island.ownerPlayerUuid(),
                    finalSlotIndex,
                    IslandRecycleState.REQUESTED,
                    null,
                    null,
                    now,
                    now));
        }

        // 2. Pre-deletion disaster recovery backup snapshot (fail-closed, never bypassed)
        String backupPath = null;
        if (host.backupPort != null) {
            try {
                backupPath = host.backupPort.createPreDeletionBackup(island, location);
                if (host.recycleOperationPort != null) {
                    host.recycleOperationPort.updateState(
                            operationId, IslandRecycleState.BACKUP_COMPLETE, backupPath, null, host.clock.instant());
                }
            } catch (Exception e) {
                if (host.recycleOperationPort != null) {
                    host.recycleOperationPort.updateState(
                            operationId, IslandRecycleState.FAILED, null, e.getMessage(), host.clock.instant());
                }
                return CompletableFuture.completedFuture(new IslandRecycleService.RecycleResult.Failure(
                        "Failed to create pre-deletion backup: " + e.getMessage()));
            }
        }

        // 3. Transaction State: VOIDING
        if (host.recycleOperationPort != null) {
            host.recycleOperationPort.updateState(
                    operationId, IslandRecycleState.VOIDING, null, null, host.clock.instant());
        }

        IslandWipeAreas areas = host.wipeAreas;
        CompletableFuture<Void> voidFuture = (host.voidingPort != null)
                ? CompletableFuture.allOf(areas.of(islandId, finalWorldName, island.bounds()).stream()
                        .map(area -> host.voidingPort.voidIslandChunks(islandId, area.world(), area.bounds()))
                        .toArray(CompletableFuture[]::new))
                : CompletableFuture.completedFuture(null);

        // 4. Defer canonical delete, slot release and completion until voiding completes
        return voidFuture.handle((v, ex) -> {
            if (ex != null) {
                if (host.recycleOperationPort != null) {
                    host.recycleOperationPort.updateState(
                            operationId, IslandRecycleState.FAILED, null, ex.getMessage(), host.clock.instant());
                }
                return new IslandRecycleService.RecycleResult.Failure(
                        "Asynchronous chunk voiding failed: " + ex.getMessage());
            }

            if (host.recycleOperationPort != null) {
                host.recycleOperationPort.updateState(
                        operationId, IslandRecycleState.VOID_COMPLETE, null, null, host.clock.instant());
            }
            areas.cleared(islandId);

            try {
                host.beforeDelete.run(island, byItsOwner);
            } catch (RuntimeException failed) {
                java.util.logging.Logger.getLogger(IslandRecycleService.class.getName())
                        .warning(() -> "The step before deleting island " + islandId.value() + " failed: "
                                + failed.getMessage());
            }

            // 5. Transaction State: CANONICAL_DELETE (Must succeed before releasing slot)
            if (host.recycleOperationPort != null) {
                host.recycleOperationPort.updateState(
                        operationId, IslandRecycleState.CANONICAL_DELETE, null, null, host.clock.instant());
            }

            String payload = String.format(
                    "{\"islandId\":\"%s\",\"ownerProfileId\":\"%s\",\"slotIndex\":%d,\"worldName\":\"%s\",\"gridX\":%d,\"gridZ\":%d}",
                    islandId.value(),
                    island.ownerProfileId().value(),
                    finalSlotIndex,
                    JsonText.escaped(finalWorldName),
                    finalGridX,
                    finalGridZ);

            StagedOutboxEvent outboxEvent = (host.outboxPort != null)
                    ? new StagedOutboxEvent(
                            EventId.random(),
                            "ISLAND_RECYCLED",
                            islandId.value().toString(),
                            payload)
                    : null;

            try {
                host.islandStoragePort.deleteIsland(islandId, outboxEvent);
            } catch (Exception e) {
                if (host.recycleOperationPort != null) {
                    host.recycleOperationPort.updateState(
                            operationId,
                            IslandRecycleState.FAILED,
                            null,
                            "Canonical island deletion failed: " + e.getMessage(),
                            host.clock.instant());
                }
                return new IslandRecycleService.RecycleResult.Failure(
                        "Canonical island deletion failed: " + e.getMessage());
            }

            // 6. Release Archimedean spiral slot
            try {
                host.spiralSlotPoolPort.releaseSlot(finalSlotIndex, finalWorldName, finalGridX, finalGridZ);
            } catch (Exception e) {
                if (host.recycleOperationPort != null) {
                    host.recycleOperationPort.updateState(
                            operationId,
                            IslandRecycleState.FAILED,
                            null,
                            "Slot release failed: " + e.getMessage(),
                            host.clock.instant());
                }
                return new IslandRecycleService.RecycleResult.Failure("Slot release failed: " + e.getMessage());
            }

            // Transaction State: SLOT_RELEASED (Only after slot release actually succeeds)
            if (host.recycleOperationPort != null) {
                host.recycleOperationPort.updateState(
                        operationId, IslandRecycleState.SLOT_RELEASED, null, null, host.clock.instant());
            }

            // 7. Transaction State: COMPLETED
            if (host.recycleOperationPort != null) {
                host.recycleOperationPort.updateState(
                        operationId, IslandRecycleState.COMPLETED, null, null, host.clock.instant());
            }

            // The island is gone, so everything keeping something for it in memory is told to let
            // it go. An island id is never reused, so a cache that keeps one is holding memory for
            // an island that cannot come back.
            host.forgetEverywhere(islandId);
            return new IslandRecycleService.RecycleResult.Success(
                    islandId, finalSlotIndex, finalWorldName, finalGridX, finalGridZ);
        });
    }
    /**
     * Recovers incomplete island recycle operations on system startup.
     *
     * <p>Enforces:
     * <ul>
     *   <li>Operations in {@link IslandRecycleState#CANONICAL_DELETE}: canonical deletion already succeeded in SQL,
     *       so release the Archimedean spiral slot and advance state to {@code SLOT_RELEASED} and {@code COMPLETED}.</li>
     *   <li>Operations in {@link IslandRecycleState#SLOT_RELEASED}: slot was already released, so advance to {@code COMPLETED}.</li>
     * </ul>
     */
    public void recoverIncompleteOperations() {
        if (host.recycleOperationPort == null) {
            return;
        }

        List<IslandRecycleOperation> pendingDeletes =
                host.recycleOperationPort.findOperationsByState(IslandRecycleState.CANONICAL_DELETE);
        for (IslandRecycleOperation op : pendingDeletes) {
            try {
                host.releaseSlotOnRecovery(op.targetSlot());
                host.recycleOperationPort.updateState(
                        op.operationId(), IslandRecycleState.SLOT_RELEASED, null, null, host.clock.instant());
                host.recycleOperationPort.updateState(
                        op.operationId(), IslandRecycleState.COMPLETED, null, null, host.clock.instant());
            } catch (Exception e) {
                host.recycleOperationPort.updateState(
                        op.operationId(),
                        IslandRecycleState.FAILED,
                        null,
                        "Startup recovery slot release failed: " + e.getMessage(),
                        host.clock.instant());
            }
        }

        List<IslandRecycleOperation> pendingCompletions =
                host.recycleOperationPort.findOperationsByState(IslandRecycleState.SLOT_RELEASED);
        for (IslandRecycleOperation op : pendingCompletions) {
            host.recycleOperationPort.updateState(
                    op.operationId(), IslandRecycleState.COMPLETED, null, null, host.clock.instant());
        }
    }
}
