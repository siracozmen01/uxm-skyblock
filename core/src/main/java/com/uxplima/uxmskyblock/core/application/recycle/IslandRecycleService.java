package com.uxplima.uxmskyblock.core.application.recycle;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import com.uxplima.uxmskyblock.core.application.event.OutboxPort;
import com.uxplima.uxmskyblock.core.application.island.IslandCacheEviction;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.world.SpiralSlotPoolPort;
import com.uxplima.uxmskyblock.core.application.world.WorldGridAllocationPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.recycle.IslandRecycleState;
import com.uxplima.uxmskyblock.core.domain.recycle.ResetChallenge;
import com.uxplima.uxmskyblock.core.domain.world.RecycledSlot;
import org.jspecify.annotations.Nullable;

/**
 * Enterprise domain application service orchestrating safe island deletion, multi-step
 * cryptographic confirmation challenges, pre-deletion disaster recovery backups,
 * Folia-native asynchronous chunk voiding, transactional state tracking, and Archimedean spiral slot recycling.
 */
public final class IslandRecycleService {

    /** One island reset carried out step by step under its journal, and the steps a crash left half done carried on at start. */
    private final RecycleRuns runs = new RecycleRuns(this);

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

    final IslandStoragePort islandStoragePort;
    final WorldGridAllocationPort worldGridAllocationPort;
    final SpiralSlotPoolPort spiralSlotPoolPort;
    final @Nullable IslandVoidingPort voidingPort;
    final @Nullable IslandBackupPort backupPort;
    final @Nullable OutboxPort outboxPort;
    final @Nullable IslandRecycleOperationPort recycleOperationPort;
    final Clock clock;
    private final Duration challengeTtl;
    private final IslandCacheEviction cacheEviction;
    private final SecureRandom secureRandom;
    private final Map<ProfileId, ChallengeEntry> pendingChallenges = new ConcurrentHashMap<>();

    /** The islands being erased right now, so a second erasure of one island is refused. */
    private final java.util.Set<IslandId> resetsInFlight = ConcurrentHashMap.newKeySet();

    /** Every square an island is cleared over: its border, and wherever its game mode wrote past it. */
    volatile IslandWipeAreas wipeAreas = IslandWipeAreas.bordersOnly();

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
    volatile BeforeDelete beforeDelete = (island, byItsOwner) -> {};

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
            return runs.runReset(island, islandId, !adminBypass)
                    .whenComplete((result, error) -> resetsInFlight.remove(islandId));
        } catch (RuntimeException e) {
            resetsInFlight.remove(islandId);
            throw e;
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
    void releaseSlotOnRecovery(long slotIndex) {
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
        runs.recoverIncompleteOperations();
    }

    /** Lets every per island cache go of an island the reset just erased. */
    void forgetEverywhere(IslandId islandId) {
        cacheEviction.forget(islandId);
    }
}
