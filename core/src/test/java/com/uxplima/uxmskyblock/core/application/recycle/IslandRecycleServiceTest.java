package com.uxplima.uxmskyblock.core.application.recycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.uxplima.uxmskyblock.core.application.event.OutboxPort;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.recycle.IslandRecycleService.RecycleResult;
import com.uxplima.uxmskyblock.core.application.world.SpiralSlotPoolPort;
import com.uxplima.uxmskyblock.core.application.world.WorldGridAllocationPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;
import com.uxplima.uxmskyblock.core.domain.recycle.IslandRecycleOperation;
import com.uxplima.uxmskyblock.core.domain.recycle.IslandRecycleState;
import com.uxplima.uxmskyblock.core.domain.recycle.ResetChallenge;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import com.uxplima.uxmskyblock.core.domain.world.WorldGridAllocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class IslandRecycleServiceTest {

    private IslandStoragePort islandStoragePort;
    private WorldGridAllocationPort worldGridAllocationPort;
    private SpiralSlotPoolPort spiralSlotPoolPort;
    private IslandVoidingPort voidingPort;
    private IslandBackupPort backupPort;
    private OutboxPort outboxPort;
    private IslandRecycleOperationPort recycleOperationPort;
    private MutableClock clock;

    private IslandRecycleService service;

    private IslandId islandId;
    private PlayerUuid ownerUuid;
    private ProfileId ownerProfileId;
    private ProfileId visitorProfileId;
    private Island island;
    private IslandLocation location;
    private WorldGridAllocation allocation;

    @BeforeEach
    void setUp() {
        islandStoragePort = mock(IslandStoragePort.class);
        worldGridAllocationPort = mock(WorldGridAllocationPort.class);
        spiralSlotPoolPort = mock(SpiralSlotPoolPort.class);
        voidingPort = mock(IslandVoidingPort.class);
        backupPort = mock(IslandBackupPort.class);
        outboxPort = mock(OutboxPort.class);
        recycleOperationPort = mock(IslandRecycleOperationPort.class);
        clock = new MutableClock(Instant.parse("2026-06-01T12:00:00Z"));

        service = new IslandRecycleService(
                islandStoragePort,
                worldGridAllocationPort,
                spiralSlotPoolPort,
                voidingPort,
                backupPort,
                outboxPort,
                recycleOperationPort,
                clock);

        islandId = IslandId.of(UUID.randomUUID());
        ownerUuid = new PlayerUuid(UUID.randomUUID());
        ownerProfileId = new ProfileId(ownerUuid.value());
        visitorProfileId = new ProfileId(UUID.randomUUID());

        IslandBounds bounds = IslandBounds.fromCenterAndRadius(100, 200, 50);
        island = Island.create(islandId, bounds, ownerUuid, ownerProfileId, clock.instant());
        location = IslandLocation.fromCenterAndRadius(islandId, "skyblock_world", 100, 200, 50);
        allocation = new WorldGridAllocation(
                42L, "skyblock_world", 100, 200, Optional.of(islandId), ServerNodeId.of("node-1"), clock.instant());

        when(islandStoragePort.findIslandById(islandId)).thenReturn(Optional.of(island));
        when(islandStoragePort.findLocationByIslandId(islandId)).thenReturn(Optional.of(location));
        when(worldGridAllocationPort.findByIslandId(islandId)).thenReturn(Optional.of(allocation));
        when(backupPort.createPreDeletionBackup(any(), any())).thenReturn("/backups/islands/island.schem");
        when(voidingPort.voidIslandChunks(any(), any(), any())).thenReturn(CompletableFuture.completedFuture(null));
    }

    @Test
    @DisplayName("generateResetChallenge produces valid 4-digit code with 60-second TTL")
    void generateResetChallengeProducesValidCode() {
        ResetChallenge challenge = service.generateResetChallenge(ownerProfileId, islandId);

        assertThat(challenge.code()).matches("\\d{4}");
        assertThat(challenge.expiresAt()).isEqualTo(clock.instant().plus(Duration.ofSeconds(60)));
        assertThat(service.verifyResetChallenge(ownerProfileId, challenge.code()))
                .isTrue();
    }

    @Test
    @DisplayName("verifyResetChallenge returns false for incorrect code or expired challenge")
    void verifyResetChallengeFailsOnMismatchOrExpiry() {
        ResetChallenge challenge = service.generateResetChallenge(ownerProfileId, islandId);

        assertThat(service.verifyResetChallenge(ownerProfileId, "99999")).isFalse();
        assertThat(service.verifyResetChallenge(ownerProfileId, "wrong")).isFalse();
        assertThat(service.verifyResetChallenge(visitorProfileId, challenge.code()))
                .isFalse();

        // Advance past expiry
        clock.advance(Duration.ofSeconds(61));
        assertThat(service.verifyResetChallenge(ownerProfileId, challenge.code()))
                .isFalse();
    }

    @Test
    @DisplayName("cancelResetChallenge clears active challenge")
    void cancelResetChallengeClearsPending() {
        ResetChallenge challenge = service.generateResetChallenge(ownerProfileId, islandId);
        service.cancelResetChallenge(ownerProfileId);

        assertThat(service.verifyResetChallenge(ownerProfileId, challenge.code()))
                .isFalse();
    }

    @Test
    @DisplayName("executeReset fails if island does not exist")
    void executeResetFailsWhenIslandNotFound() {
        IslandId nonExistent = IslandId.of(UUID.randomUUID());
        when(islandStoragePort.findIslandById(nonExistent)).thenReturn(Optional.empty());

        RecycleResult result =
                service.executeReset(ownerProfileId, nonExistent, "1234", false).join();
        assertThat(result).isInstanceOf(RecycleResult.IslandNotFound.class);
    }

    @Test
    @DisplayName("executeReset fails if requester is not island owner without admin bypass")
    void executeResetFailsWhenNotOwner() {
        RecycleResult result =
                service.executeReset(visitorProfileId, islandId, "1234", false).join();
        assertThat(result).isInstanceOf(RecycleResult.NotOwner.class);
    }

    @Test
    @DisplayName("executeReset fails if challenge code is missing or invalid")
    void executeResetFailsWhenInvalidChallenge() {
        RecycleResult resultNull =
                service.executeReset(ownerProfileId, islandId, null, false).join();
        assertThat(resultNull).isInstanceOf(RecycleResult.InvalidChallenge.class);

        RecycleResult resultWrong =
                service.executeReset(ownerProfileId, islandId, "0000", false).join();
        assertThat(resultWrong).isInstanceOf(RecycleResult.InvalidChallenge.class);
    }

    @Test
    @DisplayName("executeReset succeeds with valid challenge code, triggers backup, voiding, recycling, and deletion")
    void executeResetSucceedsWithValidChallenge() {
        ResetChallenge challenge = service.generateResetChallenge(ownerProfileId, islandId);

        RecycleResult result = service.executeReset(ownerProfileId, islandId, challenge.code(), false)
                .join();
        assertThat(result).isInstanceOf(RecycleResult.Success.class);

        RecycleResult.Success success = (RecycleResult.Success) result;
        assertThat(success.islandId()).isEqualTo(islandId);
        assertThat(success.slotIndex()).isEqualTo(42L);
        assertThat(success.worldName()).isEqualTo("skyblock_world");
        assertThat(success.gridX()).isEqualTo(100);
        assertThat(success.gridZ()).isEqualTo(200);

        verify(backupPort).createPreDeletionBackup(eq(island), eq(location));
        verify(voidingPort).voidIslandChunks(eq(islandId), eq("skyblock_world"), eq(island.bounds()));
        verify(islandStoragePort)
                .deleteIsland(
                        eq(islandId), argThat(event -> event != null && "ISLAND_RECYCLED".equals(event.eventType())));
        verify(spiralSlotPoolPort).releaseSlot(eq(42L), eq("skyblock_world"), eq(100), eq(200));

        // Challenge should be consumed
        assertThat(service.verifyResetChallenge(ownerProfileId, challenge.code()))
                .isFalse();
    }

    @Test
    @DisplayName("executeReset with admin bypass succeeds without challenge or owner identity")
    void executeResetWithAdminBypassSucceeds() {
        ProfileId staffProfile = new ProfileId(UUID.randomUUID());

        RecycleResult result =
                service.executeReset(staffProfile, islandId, null, true).join();
        assertThat(result).isInstanceOf(RecycleResult.Success.class);

        verify(backupPort).createPreDeletionBackup(eq(island), eq(location));
        verify(voidingPort).voidIslandChunks(eq(islandId), eq("skyblock_world"), eq(island.bounds()));
        verify(islandStoragePort).deleteIsland(eq(islandId), any());
        verify(spiralSlotPoolPort).releaseSlot(eq(42L), eq("skyblock_world"), eq(100), eq(200));
    }

    @Test
    @DisplayName("executeReset tracks state transitions in strict order")
    void executeResetTracksStateTransitionsInOrder() {
        ResetChallenge challenge = service.generateResetChallenge(ownerProfileId, islandId);

        RecycleResult result = service.executeReset(ownerProfileId, islandId, challenge.code(), false)
                .join();
        assertThat(result).isInstanceOf(RecycleResult.Success.class);

        InOrder inOrder = inOrder(recycleOperationPort, islandStoragePort, spiralSlotPoolPort);
        inOrder.verify(recycleOperationPort).recordOperation(argThat(op -> op.state() == IslandRecycleState.REQUESTED));
        inOrder.verify(recycleOperationPort)
                .updateState(
                        any(),
                        eq(IslandRecycleState.BACKUP_COMPLETE),
                        eq("/backups/islands/island.schem"),
                        any(),
                        any());
        inOrder.verify(recycleOperationPort).updateState(any(), eq(IslandRecycleState.VOIDING), any(), any(), any());
        inOrder.verify(recycleOperationPort)
                .updateState(any(), eq(IslandRecycleState.VOID_COMPLETE), any(), any(), any());
        inOrder.verify(islandStoragePort).deleteIsland(eq(islandId), any());
        inOrder.verify(spiralSlotPoolPort).releaseSlot(eq(42L), eq("skyblock_world"), eq(100), eq(200));
        inOrder.verify(recycleOperationPort)
                .updateState(any(), eq(IslandRecycleState.SLOT_RELEASED), any(), any(), any());
        inOrder.verify(recycleOperationPort).updateState(any(), eq(IslandRecycleState.COMPLETED), any(), any(), any());
    }

    @Test
    @DisplayName("recoverIncompleteOperations recovers CANONICAL_DELETE and SLOT_RELEASED operations on startup")
    void testRecoverIncompleteOperations() {
        IslandRecycleOperation opDelete = new IslandRecycleOperation(
                "op-delete-1",
                islandId,
                PlayerUuid.of(UUID.randomUUID()),
                55L,
                IslandRecycleState.CANONICAL_DELETE,
                null,
                null,
                Instant.now(),
                Instant.now());
        IslandRecycleOperation opReleased = new IslandRecycleOperation(
                "op-rel-2",
                islandId,
                PlayerUuid.of(UUID.randomUUID()),
                66L,
                IslandRecycleState.SLOT_RELEASED,
                null,
                null,
                Instant.now(),
                Instant.now());

        when(recycleOperationPort.findOperationsByState(IslandRecycleState.CANONICAL_DELETE))
                .thenReturn(List.of(opDelete));
        when(recycleOperationPort.findOperationsByState(IslandRecycleState.SLOT_RELEASED))
                .thenReturn(List.of(opReleased));
        // Where the slot is comes off the slot. The island row is gone by the time recovery runs,
        // so the world and the coordinates the normal path reads off the island are not there.
        when(spiralSlotPoolPort.findBySlotIndex(55L))
                .thenReturn(Optional.of(new com.uxplima.uxmskyblock.core.domain.world.RecycledSlot(
                        55L, "islands_alpha", 2000, -4000, true, null)));

        service.recoverIncompleteOperations();

        verify(spiralSlotPoolPort).releaseSlot(55L, "islands_alpha", 2000, -4000);
        verify(recycleOperationPort)
                .updateState(eq("op-delete-1"), eq(IslandRecycleState.SLOT_RELEASED), any(), any(), any());
        verify(recycleOperationPort)
                .updateState(eq("op-delete-1"), eq(IslandRecycleState.COMPLETED), any(), any(), any());
        verify(recycleOperationPort).updateState(eq("op-rel-2"), eq(IslandRecycleState.COMPLETED), any(), any(), any());
    }

    @Test
    @DisplayName("executeReset fails-closed and aborts deletion if pre-deletion backup throws")
    void executeResetFailsClosedWhenBackupFails() {
        ResetChallenge challenge = service.generateResetChallenge(ownerProfileId, islandId);
        doThrow(new RuntimeException("Disk full")).when(backupPort).createPreDeletionBackup(any(), any());

        RecycleResult result = service.executeReset(ownerProfileId, islandId, challenge.code(), false)
                .join();
        assertThat(result).isInstanceOf(RecycleResult.Failure.class);
        assertThat(((RecycleResult.Failure) result).reason()).contains("Disk full");

        verify(recycleOperationPort)
                .updateState(
                        any(), eq(IslandRecycleState.FAILED), any(), argThat(msg -> msg.contains("Disk full")), any());
        verify(spiralSlotPoolPort, never()).releaseSlot(anyLong(), any(), anyInt(), anyInt());
        verify(islandStoragePort, never()).deleteIsland(any(), any());
    }

    @Test
    @DisplayName("executeReset with admin bypass fails-closed and aborts deletion if backup throws")
    void executeResetWithAdminBypassFailsClosedWhenBackupThrows() {
        ProfileId staffProfile = new ProfileId(UUID.randomUUID());
        doThrow(new RuntimeException("S3 bucket down")).when(backupPort).createPreDeletionBackup(any(), any());

        RecycleResult result =
                service.executeReset(staffProfile, islandId, null, true).join();
        assertThat(result).isInstanceOf(RecycleResult.Failure.class);
        assertThat(((RecycleResult.Failure) result).reason()).contains("S3 bucket down");

        verify(recycleOperationPort)
                .updateState(
                        any(),
                        eq(IslandRecycleState.FAILED),
                        any(),
                        argThat(msg -> msg.contains("S3 bucket down")),
                        any());
        verify(voidingPort, never()).voidIslandChunks(any(), any(), any());
        verify(spiralSlotPoolPort, never()).releaseSlot(anyLong(), any(), anyInt(), anyInt());
        verify(islandStoragePort, never()).deleteIsland(any(), any());
    }

    @Test
    @DisplayName("executeReset aborts slot release if canonical database delete fails")
    void executeResetAbortsSlotReleaseIfCanonicalDeleteFails() {
        ResetChallenge challenge = service.generateResetChallenge(ownerProfileId, islandId);
        doThrow(new RuntimeException("Database connection dead"))
                .when(islandStoragePort)
                .deleteIsland(any(), any());

        RecycleResult result = service.executeReset(ownerProfileId, islandId, challenge.code(), false)
                .join();
        assertThat(result).isInstanceOf(RecycleResult.Failure.class);
        assertThat(((RecycleResult.Failure) result).reason()).contains("Canonical island deletion failed");

        verify(spiralSlotPoolPort, never()).releaseSlot(anyLong(), any(), anyInt(), anyInt());
        verify(recycleOperationPort)
                .updateState(
                        any(),
                        eq(IslandRecycleState.FAILED),
                        any(),
                        argThat(msg -> msg.contains("Canonical island deletion failed")),
                        any());
    }

    @Test
    @DisplayName("executeReset fails-closed and aborts deletion if voiding completes exceptionally")
    void executeResetFailsClosedWhenVoidingFails() {
        ResetChallenge challenge = service.generateResetChallenge(ownerProfileId, islandId);
        CompletableFuture<Void> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("Folia region timeout"));
        when(voidingPort.voidIslandChunks(any(), any(), any())).thenReturn(failedFuture);

        RecycleResult result = service.executeReset(ownerProfileId, islandId, challenge.code(), false)
                .join();
        assertThat(result).isInstanceOf(RecycleResult.Failure.class);

        verify(spiralSlotPoolPort, never()).releaseSlot(anyLong(), any(), anyInt(), anyInt());
        verify(islandStoragePort, never()).deleteIsland(any(), any());
    }

    @Test
    @DisplayName(
            "The step before delete sees the island once its world is cleared, and a failing step does not stop the delete")
    void theStepBeforeDeleteRuns() {
        java.util.List<String> seen = new java.util.ArrayList<>();
        when(voidingPort.voidIslandChunks(any(), any(), any())).thenAnswer(call -> {
            seen.add("cleared");
            return CompletableFuture.completedFuture(null);
        });
        service.useBeforeDelete(gone -> {
            seen.add("before " + gone.id().value());
            throw new IllegalStateException("the wallet is down");
        });

        RecycleResult result = service.executeReset(new ProfileId(UUID.randomUUID()), islandId, null, true)
                .join();

        assertThat(result).isInstanceOf(RecycleResult.Success.class);
        assertThat(seen).containsExactly("cleared", "before " + islandId.value());
        verify(islandStoragePort).deleteIsland(eq(islandId), any());
    }

    @Test
    @DisplayName("A reset that fails before the delete runs no step before it")
    void aFailedResetRunsNoStep() {
        java.util.List<Object> seen = new java.util.ArrayList<>();
        service.useBeforeDelete(seen::add);
        CompletableFuture<Void> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("Folia region timeout"));
        when(voidingPort.voidIslandChunks(any(), any(), any())).thenReturn(failedFuture);

        service.executeReset(new ProfileId(UUID.randomUUID()), islandId, null, true)
                .join();

        assertThat(seen).isEmpty();
    }

    private static final class MutableClock extends Clock {
        private Instant current;

        MutableClock(Instant initial) {
            this.current = initial;
        }

        void advance(Duration duration) {
            this.current = this.current.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }
}
