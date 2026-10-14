package com.uxplima.uxmskyblock.core.application.recycle;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import com.uxplima.uxmskyblock.core.application.event.OutboxPort;
import com.uxplima.uxmskyblock.core.application.island.IslandCacheEviction;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.world.SpiralSlotPoolPort;
import com.uxplima.uxmskyblock.core.application.world.WorldGridAllocationPort;
import com.uxplima.uxmskyblock.core.domain.event.EventId;
import com.uxplima.uxmskyblock.core.domain.event.JsonText;
import com.uxplima.uxmskyblock.core.domain.event.StagedOutboxEvent;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;
import com.uxplima.uxmskyblock.core.domain.recycle.IslandRecycleOperation;
import com.uxplima.uxmskyblock.core.domain.recycle.IslandRecycleState;
import com.uxplima.uxmskyblock.core.domain.recycle.ResetChallenge;
import com.uxplima.uxmskyblock.core.domain.world.RecycledSlot;
import com.uxplima.uxmskyblock.core.domain.world.WorldGridAllocation;
import org.jspecify.annotations.Nullable;

/**
 * Enterprise domain application service orchestrating safe island deletion, multi-step
 * cryptographic confirmation challenges, pre-deletion disaster recovery backups,
 * Folia-native asynchronous chunk voiding, transactional state tracking, and Archimedean spiral slot recycling.
 */
public final class IslandRecycleService {

    private static final java.util.logging.Logger LOGGER =
            java.util.logging.Logger.getLogger(IslandRecycleService.class.getName());

    public sealed interface RecycleResult {
        record Success(IslandId islandId, long slotIndex, String worldName, int gridX, int gridZ)
                implements RecycleResult {}

        record IslandNotFound(IslandId islandId) implements RecycleResult {}

        record NotOwner(IslandId islandId, ProfileId requester) implements RecycleResult {}

        record InvalidChallenge(String reason) implements RecycleResult {}

        record Failure(String reason) implements RecycleResult {}

        /** The island is already being erased, by this caller's earlier attempt or by another. */
        record AlreadyRunning(IslandId islandId) implements RecycleResult {}
    }

    private record ChallengeEntry(IslandId islandId, ResetChallenge challenge) {}

    /** How long a reset confirmation code stays good, when the operator names no other number. */
    public static final Duration DEFAULT_CHALLENGE_TTL = Duration.ofSeconds(60);

    private final IslandStoragePort islandStoragePort;
    private final WorldGridAllocationPort worldGridAllocationPort;
    private final SpiralSlotPoolPort spiralSlotPoolPort;
    private final @Nullable IslandVoidingPort voidingPort;
    private final @Nullable IslandBackupPort backupPort;
    private final @Nullable OutboxPort outboxPort;
    private final @Nullable IslandRecycleOperationPort recycleOperationPort;
    private final Clock clock;
    private final Duration challengeTtl;
    private final IslandCacheEviction cacheEviction;
    private final SecureRandom secureRandom;
    private final Map<ProfileId, ChallengeEntry> pendingChallenges = new ConcurrentHashMap<>();

    /** The islands being erased right now, so a second erasure of one island is refused. */
    private final java.util.Set<IslandId> resetsInFlight = ConcurrentHashMap.newKeySet();

    /** Every square an island is cleared over: its border, and wherever its game mode wrote past it. */
    private volatile IslandWipeAreas wipeAreas = IslandWipeAreas.bordersOnly();

    /** Clears an island over every square it wrote in, not only its border. */
    public void useWipeAreas(IslandWipeAreas areas) {
        this.wipeAreas = java.util.Objects.requireNonNull(areas, "areas must not be null");
    }

    /** What is done with an island just before its record is deleted. */
    @FunctionalInterface
    public interface BeforeDelete {
        /**
         * Does this step with {@code island}.
         *
         * @param byItsOwner whether the island's owner deleted it, rather than staff
         */
        void run(Island island, boolean byItsOwner);
    }

    /** What is done with an island just before its record is deleted: nothing, until the wiring says. */
    private volatile BeforeDelete beforeDelete = (island, byItsOwner) -> {};

    /**
     * Has {@code step} done with every island just before its record is deleted, once its world is cleared. A step
     * that fails is logged and the delete goes on.
     */
    public void useBeforeDelete(BeforeDelete step) {
        this.beforeDelete = java.util.Objects.requireNonNull(step, "step must not be null");
    }

    public IslandRecycleService(
            IslandStoragePort islandStoragePort,
            WorldGridAllocationPort worldGridAllocationPort,
            SpiralSlotPoolPort spiralSlotPoolPort,
            @Nullable IslandVoidingPort voidingPort,
            @Nullable IslandBackupPort backupPort,
            @Nullable OutboxPort outboxPort,
            @Nullable IslandRecycleOperationPort recycleOperationPort,
            Clock clock) {
        this(
                islandStoragePort,
                worldGridAllocationPort,
                spiralSlotPoolPort,
                voidingPort,
                backupPort,
                outboxPort,
                recycleOperationPort,
                clock,
                DEFAULT_CHALLENGE_TTL);
    }

    /**
     * The canonical constructor, carrying the window a reset confirmation code stays good for.
     *
     * <p>That window was sixty seconds written in the code. It is how long a player has to read a
     * four digit code and type it back before the island they are about to erase stops listening,
     * which is exactly the sort of number an operator has an opinion about.
     */
    public IslandRecycleService(
            IslandStoragePort islandStoragePort,
            WorldGridAllocationPort worldGridAllocationPort,
            SpiralSlotPoolPort spiralSlotPoolPort,
            @Nullable IslandVoidingPort voidingPort,
            @Nullable IslandBackupPort backupPort,
            @Nullable OutboxPort outboxPort,
            @Nullable IslandRecycleOperationPort recycleOperationPort,
            Clock clock,
            Duration challengeTtl) {
        this(
                islandStoragePort,
                worldGridAllocationPort,
                spiralSlotPoolPort,
                voidingPort,
                backupPort,
                outboxPort,
                recycleOperationPort,
                clock,
                challengeTtl,
                new IslandCacheEviction());
    }

    /**
     * The constructor that also says what to forget when an island is erased.
     *
     * <p>Half a dozen services keep something per island in memory and several had a method to
     * forget one that nothing called. An erased island is the moment they should.
     */
    public IslandRecycleService(
            IslandStoragePort islandStoragePort,
            WorldGridAllocationPort worldGridAllocationPort,
            SpiralSlotPoolPort spiralSlotPoolPort,
            @Nullable IslandVoidingPort voidingPort,
            @Nullable IslandBackupPort backupPort,
            @Nullable OutboxPort outboxPort,
            @Nullable IslandRecycleOperationPort recycleOperationPort,
            Clock clock,
            Duration challengeTtl,
            IslandCacheEviction cacheEviction) {
        this.cacheEviction = Objects.requireNonNull(cacheEviction, "cacheEviction must not be null");
        this.challengeTtl = Objects.requireNonNull(challengeTtl, "challengeTtl must not be null");
        this.islandStoragePort = Objects.requireNonNull(islandStoragePort, "islandStoragePort must not be null");
        this.worldGridAllocationPort =
                Objects.requireNonNull(worldGridAllocationPort, "worldGridAllocationPort must not be null");
        this.spiralSlotPoolPort = Objects.requireNonNull(spiralSlotPoolPort, "spiralSlotPoolPort must not be null");
        this.voidingPort = voidingPort;
        this.backupPort = backupPort;
        this.outboxPort = outboxPort;
        this.recycleOperationPort = recycleOperationPort;
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.secureRandom = new SecureRandom();
    }

    public IslandRecycleService(
            IslandStoragePort islandStoragePort,
            WorldGridAllocationPort worldGridAllocationPort,
            SpiralSlotPoolPort spiralSlotPoolPort,
            @Nullable IslandVoidingPort voidingPort,
            @Nullable IslandBackupPort backupPort,
            @Nullable OutboxPort outboxPort,
            @Nullable IslandRecycleOperationPort recycleOperationPort) {
        this(
                islandStoragePort,
                worldGridAllocationPort,
                spiralSlotPoolPort,
                voidingPort,
                backupPort,
                outboxPort,
                recycleOperationPort,
                Clock.systemUTC());
    }

    public IslandRecycleService(
            IslandStoragePort islandStoragePort,
            WorldGridAllocationPort worldGridAllocationPort,
            SpiralSlotPoolPort spiralSlotPoolPort,
            @Nullable IslandVoidingPort voidingPort,
            @Nullable IslandBackupPort backupPort,
            @Nullable OutboxPort outboxPort,
            Clock clock) {
        this(
                islandStoragePort,
                worldGridAllocationPort,
                spiralSlotPoolPort,
                voidingPort,
                backupPort,
                outboxPort,
                null,
                clock);
    }

    public IslandRecycleService(
            IslandStoragePort islandStoragePort,
            WorldGridAllocationPort worldGridAllocationPort,
            SpiralSlotPoolPort spiralSlotPoolPort,
            @Nullable IslandVoidingPort voidingPort,
            @Nullable IslandBackupPort backupPort,
            @Nullable OutboxPort outboxPort) {
        this(
                islandStoragePort,
                worldGridAllocationPort,
                spiralSlotPoolPort,
                voidingPort,
                backupPort,
                outboxPort,
                null,
                Clock.systemUTC());
    }

    /**
     * Issues a temporary 4-digit cryptographic verification challenge for island deletion/reset.
     *
     * @param requesterProfileId profile of player requesting reset
     * @param islandId target island ID
     * @return generated challenge with 60-second expiry
     */
    public ResetChallenge generateResetChallenge(ProfileId requesterProfileId, IslandId islandId) {
        Objects.requireNonNull(requesterProfileId, "requesterProfileId must not be null");
        Objects.requireNonNull(islandId, "islandId must not be null");

        int randomDigits = secureRandom.nextInt(10000);
        // Locale.ROOT: a server whose default locale writes its own digits would issue a code that
        // no keyboard types back and no answer ever equals.
        String code = String.format(Locale.ROOT, "%04d", randomDigits);
        Instant expiresAt = clock.instant().plus(challengeTtl);
        ResetChallenge challenge = new ResetChallenge(code, expiresAt);

        pendingChallenges.put(requesterProfileId, new ChallengeEntry(islandId, challenge));
        return challenge;
    }

    /**
     * Verifies if a pending challenge matches and has not expired.
     *
     * @param requesterProfileId profile ID
     * @param code 4-digit code provided
     * @return true if valid
     */
    public boolean verifyResetChallenge(ProfileId requesterProfileId, @Nullable String code) {
        if (code == null) {
            return false;
        }
        ChallengeEntry entry = pendingChallenges.get(requesterProfileId);
        if (entry == null) {
            return false;
        }
        if (entry.challenge().isExpired(clock.instant())) {
            pendingChallenges.remove(requesterProfileId);
            return false;
        }
        return entry.challenge().code().equals(code.trim());
    }

    /**
     * Clears any pending challenge for the specified requester.
     *
     * @param requesterProfileId profile ID
     */
    public void cancelResetChallenge(ProfileId requesterProfileId) {
        pendingChallenges.remove(requesterProfileId);
    }

    /**
     * Safely executes an asynchronous island reset or deletion following the strict transactional lifecycle:
     * REQUESTED -&gt; BACKUP_COMPLETE -&gt; VOIDING -&gt; VOID_COMPLETE -&gt; CANONICAL_DELETE -&gt; SLOT_RELEASED -&gt; COMPLETED.
     *
     * <p>Enforces:
     * <ul>
     *   <li>Ownership and cryptographic challenge verification.</li>
     *   <li>Fail-closed pre-deletion backup (fails the reset if backup throws, even under admin bypass).</li>
     *   <li>Folia-safe asynchronous chunk voiding awaited BEFORE database deletion.</li>
     *   <li>Canonical database deletion performed BEFORE releasing the spiral slot pool reservation.</li>
     *   <li>Durable state machine logging to {@link IslandRecycleOperationPort}.</li>
     * </ul>
     *
     * @param requester profile ID of the initiator
     * @param islandId target island ID
     * @param verificationCode verification code string, or null if admin bypass
     * @param adminBypass true if initiated by staff bypass permission
     * @return CompletableFuture completing with the final RecycleResult
     */
    public CompletableFuture<RecycleResult> executeReset(
            ProfileId requester, IslandId islandId, @Nullable String verificationCode, boolean adminBypass) {
        Objects.requireNonNull(requester, "requester must not be null");
        Objects.requireNonNull(islandId, "islandId must not be null");

        Optional<Island> optIsland = islandStoragePort.findIslandById(islandId);
        if (optIsland.isEmpty()) {
            return CompletableFuture.completedFuture(new RecycleResult.IslandNotFound(islandId));
        }
        Island island = optIsland.get();

        if (!adminBypass) {
            if (!island.ownerProfileId().equals(requester)) {
                return CompletableFuture.completedFuture(new RecycleResult.NotOwner(islandId, requester));
            }
            if (!verifyResetChallenge(requester, verificationCode)) {
                return CompletableFuture.completedFuture(new RecycleResult.InvalidChallenge(
                        verificationCode == null ? "Confirmation required." : "Invalid or expired confirmation code."));
            }
            pendingChallenges.remove(requester);
        }

        // One erasure per island, whoever asked for it.
        //
        // The confirmation code is removed after it is checked, not with it, so two confirmations
        // arriving together both passed. An administrator's reset carries no code at all, so two of
        // those never had anything between them either. Two erasures of one island at once take a
        // backup of a world the other is deleting, release the same grid slot twice and write two
        // recycle operations for one island.
        if (!resetsInFlight.add(islandId)) {
            return CompletableFuture.completedFuture(new RecycleResult.AlreadyRunning(islandId));
        }
        try {
            return runReset(island, islandId, !adminBypass)
                    .whenComplete((result, error) -> resetsInFlight.remove(islandId));
        } catch (RuntimeException e) {
            resetsInFlight.remove(islandId);
            throw e;
        }
    }

    /** The erasure itself, with the island's slot already taken. */
    private CompletableFuture<RecycleResult> runReset(Island island, IslandId islandId, boolean byItsOwner) {
        Optional<IslandLocation> optLocation = islandStoragePort.findLocationByIslandId(islandId);
        if (optLocation.isEmpty()) {
            return CompletableFuture.completedFuture(
                    new RecycleResult.Failure("Island location record missing for islandId=" + islandId));
        }
        IslandLocation location = optLocation.get();

        // Resolve world grid allocation
        long slotIndex = 0L;
        String worldName = location.worldName();
        int gridX = location.bounds().centerX();
        int gridZ = location.bounds().centerZ();

        Optional<WorldGridAllocation> optAlloc = worldGridAllocationPort.findByIslandId(islandId);
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
        Instant now = clock.instant();

        // 1. Transaction State: REQUESTED
        if (recycleOperationPort != null) {
            recycleOperationPort.recordOperation(new IslandRecycleOperation(
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
        if (backupPort != null) {
            try {
                backupPath = backupPort.createPreDeletionBackup(island, location);
                if (recycleOperationPort != null) {
                    recycleOperationPort.updateState(
                            operationId, IslandRecycleState.BACKUP_COMPLETE, backupPath, null, clock.instant());
                }
            } catch (Exception e) {
                if (recycleOperationPort != null) {
                    recycleOperationPort.updateState(
                            operationId, IslandRecycleState.FAILED, null, e.getMessage(), clock.instant());
                }
                return CompletableFuture.completedFuture(
                        new RecycleResult.Failure("Failed to create pre-deletion backup: " + e.getMessage()));
            }
        }

        // 3. Transaction State: VOIDING
        if (recycleOperationPort != null) {
            recycleOperationPort.updateState(operationId, IslandRecycleState.VOIDING, null, null, clock.instant());
        }

        IslandWipeAreas areas = this.wipeAreas;
        CompletableFuture<Void> voidFuture = (voidingPort != null)
                ? CompletableFuture.allOf(areas.of(islandId, finalWorldName, island.bounds()).stream()
                        .map(area -> voidingPort.voidIslandChunks(islandId, area.world(), area.bounds()))
                        .toArray(CompletableFuture[]::new))
                : CompletableFuture.completedFuture(null);

        // 4. Defer canonical delete, slot release and completion until voiding completes
        return voidFuture.handle((v, ex) -> {
            if (ex != null) {
                if (recycleOperationPort != null) {
                    recycleOperationPort.updateState(
                            operationId, IslandRecycleState.FAILED, null, ex.getMessage(), clock.instant());
                }
                return new RecycleResult.Failure("Asynchronous chunk voiding failed: " + ex.getMessage());
            }

            if (recycleOperationPort != null) {
                recycleOperationPort.updateState(
                        operationId, IslandRecycleState.VOID_COMPLETE, null, null, clock.instant());
            }
            areas.cleared(islandId);

            try {
                beforeDelete.run(island, byItsOwner);
            } catch (RuntimeException failed) {
                java.util.logging.Logger.getLogger(IslandRecycleService.class.getName())
                        .warning(() -> "The step before deleting island " + islandId.value() + " failed: "
                                + failed.getMessage());
            }

            // 5. Transaction State: CANONICAL_DELETE (Must succeed before releasing slot)
            if (recycleOperationPort != null) {
                recycleOperationPort.updateState(
                        operationId, IslandRecycleState.CANONICAL_DELETE, null, null, clock.instant());
            }

            String payload = String.format(
                    "{\"islandId\":\"%s\",\"ownerProfileId\":\"%s\",\"slotIndex\":%d,\"worldName\":\"%s\",\"gridX\":%d,\"gridZ\":%d}",
                    islandId.value(),
                    island.ownerProfileId().value(),
                    finalSlotIndex,
                    JsonText.escaped(finalWorldName),
                    finalGridX,
                    finalGridZ);

            StagedOutboxEvent outboxEvent = (outboxPort != null)
                    ? new StagedOutboxEvent(
                            EventId.random(),
                            "ISLAND_RECYCLED",
                            islandId.value().toString(),
                            payload)
                    : null;

            try {
                islandStoragePort.deleteIsland(islandId, outboxEvent);
            } catch (Exception e) {
                if (recycleOperationPort != null) {
                    recycleOperationPort.updateState(
                            operationId,
                            IslandRecycleState.FAILED,
                            null,
                            "Canonical island deletion failed: " + e.getMessage(),
                            clock.instant());
                }
                return new RecycleResult.Failure("Canonical island deletion failed: " + e.getMessage());
            }

            // 6. Release Archimedean spiral slot
            try {
                spiralSlotPoolPort.releaseSlot(finalSlotIndex, finalWorldName, finalGridX, finalGridZ);
            } catch (Exception e) {
                if (recycleOperationPort != null) {
                    recycleOperationPort.updateState(
                            operationId,
                            IslandRecycleState.FAILED,
                            null,
                            "Slot release failed: " + e.getMessage(),
                            clock.instant());
                }
                return new RecycleResult.Failure("Slot release failed: " + e.getMessage());
            }

            // Transaction State: SLOT_RELEASED (Only after slot release actually succeeds)
            if (recycleOperationPort != null) {
                recycleOperationPort.updateState(
                        operationId, IslandRecycleState.SLOT_RELEASED, null, null, clock.instant());
            }

            // 7. Transaction State: COMPLETED
            if (recycleOperationPort != null) {
                recycleOperationPort.updateState(
                        operationId, IslandRecycleState.COMPLETED, null, null, clock.instant());
            }

            // The island is gone, so everything keeping something for it in memory is told to let
            // it go. An island id is never reused, so a cache that keeps one is holding memory for
            // an island that cannot come back.
            cacheEviction.forget(islandId);
            return new RecycleResult.Success(islandId, finalSlotIndex, finalWorldName, finalGridX, finalGridZ);
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
        if (recycleOperationPort == null) {
            return;
        }

        List<IslandRecycleOperation> pendingDeletes =
                recycleOperationPort.findOperationsByState(IslandRecycleState.CANONICAL_DELETE);
        for (IslandRecycleOperation op : pendingDeletes) {
            try {
                releaseSlotOnRecovery(op.targetSlot());
                recycleOperationPort.updateState(
                        op.operationId(), IslandRecycleState.SLOT_RELEASED, null, null, clock.instant());
                recycleOperationPort.updateState(
                        op.operationId(), IslandRecycleState.COMPLETED, null, null, clock.instant());
            } catch (Exception e) {
                recycleOperationPort.updateState(
                        op.operationId(),
                        IslandRecycleState.FAILED,
                        null,
                        "Startup recovery slot release failed: " + e.getMessage(),
                        clock.instant());
            }
        }

        List<IslandRecycleOperation> pendingCompletions =
                recycleOperationPort.findOperationsByState(IslandRecycleState.SLOT_RELEASED);
        for (IslandRecycleOperation op : pendingCompletions) {
            recycleOperationPort.updateState(
                    op.operationId(), IslandRecycleState.COMPLETED, null, null, clock.instant());
        }
    }

    /**
     * Hands one slot back, reading where it is from the slot itself.
     *
     * <p>The island row is gone by the time recovery runs, so the world and the coordinates the
     * normal path reads off the island are not there to read. This used to pass the world name
     * {@code "skyblock_world"} and the coordinates 0, 0, which is a world name written in the code
     * on a server whose world the operator names. The release keys on the slot index, so those
     * three arguments only matter where the pool has no row yet, and there they would have written
     * a row claiming a slot sits at 0, 0 in a world that may not exist: the next island recycled
     * into it would be built on top of whatever is actually there.
     *
     * <p>The pool row is the only thing that knows. When it is there, it says where. When it is
     * not, there is nothing allocated to hand back and inventing a row is worse than doing nothing.
     */
    private void releaseSlotOnRecovery(long slotIndex) {
        Optional<RecycledSlot> slot = spiralSlotPoolPort.findBySlotIndex(slotIndex);
        if (slot.isEmpty()) {
            LOGGER.fine(() -> "Recovering a recycle that had no pool row for slot " + slotIndex
                    + ". There is nothing allocated to hand back.");
            return;
        }
        RecycledSlot held = slot.get();
        spiralSlotPoolPort.releaseSlot(held.slotIndex(), held.worldName(), held.gridX(), held.gridZ());
    }

    /**
     * Synchronous blocking convenience method for test harnesses and synchronous callers.
     */
    public RecycleResult executeResetSync(
            ProfileId requester, IslandId islandId, @Nullable String verificationCode, boolean adminBypass) {
        return executeReset(requester, islandId, verificationCode, adminBypass).join();
    }
}
