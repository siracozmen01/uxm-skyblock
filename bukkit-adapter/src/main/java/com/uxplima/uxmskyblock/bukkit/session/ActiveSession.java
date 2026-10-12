package com.uxplima.uxmskyblock.bukkit.session;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.session.SessionState;
import org.jspecify.annotations.Nullable;

/**
 * One player's session on this server, as this server sees it.
 *
 * <p>The database holds the truth about who owns a session, and this holds what the server needs
 * between the queries: the profile in play, the epoch the server was given, the last version it
 * durably wrote, and the two repeating tasks that keep the lease alive and the inventory saved.
 *
 * <p>Every field is read from the main thread and written from a scheduler thread, so every one is
 * atomic or volatile. A fence closes both tasks and is not reversible: a session another node has
 * taken must never write again, and the way back is a new session, not a flag flip.
 */
public final class ActiveSession {
    private final PlayerUuid playerUuid;
    private volatile ProfileId activeProfileId;
    private final AtomicLong sessionEpoch;
    private final AtomicLong lastDurableVersion;
    private final java.util.concurrent.locks.ReentrantLock writes = new java.util.concurrent.locks.ReentrantLock();
    private final AtomicReference<SessionState> state = new AtomicReference<>(SessionState.ACTIVE);
    private final AtomicReference<@Nullable AutoCloseable> heartbeatTask = new AtomicReference<>(null);
    private final AtomicReference<@Nullable AutoCloseable> checkpointTask = new AtomicReference<>(null);
    private volatile boolean inPlay;
    private final java.util.concurrent.atomic.AtomicInteger selfWrites =
            new java.util.concurrent.atomic.AtomicInteger();
    /** A lease this node has not been granted yet; the clock can take any value, so this is a marker. */
    private static final long NEVER_HELD = Long.MIN_VALUE;

    private final AtomicLong heldUntilNanos = new AtomicLong(NEVER_HELD);

    public ActiveSession(PlayerUuid playerUuid, ProfileId activeProfileId, long sessionEpoch, long lastDurableVersion) {
        this(playerUuid, activeProfileId, sessionEpoch, lastDurableVersion, SessionState.ACTIVE);
    }

    public ActiveSession(
            PlayerUuid playerUuid,
            ProfileId activeProfileId,
            long sessionEpoch,
            long lastDurableVersion,
            SessionState initialState) {
        this.playerUuid = Objects.requireNonNull(playerUuid, "playerUuid");
        this.activeProfileId = Objects.requireNonNull(activeProfileId, "activeProfileId");
        this.sessionEpoch = new AtomicLong(sessionEpoch);
        this.lastDurableVersion = new AtomicLong(lastDurableVersion);
        this.state.set(Objects.requireNonNull(initialState, "initialState"));
    }

    public PlayerUuid playerUuid() {
        return playerUuid;
    }

    public ProfileId activeProfileId() {
        return activeProfileId;
    }

    public void setActiveProfileId(ProfileId activeProfileId) {
        this.activeProfileId = Objects.requireNonNull(activeProfileId, "activeProfileId");
    }

    public long sessionEpoch() {
        return sessionEpoch.get();
    }

    public long lastDurableVersion() {
        return lastDurableVersion.get();
    }

    public void setLastDurableVersion(long version) {
        this.lastDurableVersion.set(version);
    }

    /**
     * Held by whatever writes this player's state, so two writes never race for the same version.
     *
     * <p>A checkpoint and a vault save each write over the version the session last wrote. Run at
     * once, one of them was refused, and a refused vault save hands the page's items back as though the
     * player never moved them. A checkpoint that finds this held leaves it to the next one; a vault save
     * waits for it and writes over the version the checkpoint left.
     */
    public java.util.concurrent.locks.ReentrantLock writes() {
        return writes;
    }

    /**
     * Whether the player holds what this session says they hold, and may use it.
     *
     * <p>From the join until the durable state is put on the player, and while a profile switch
     * swaps one state for another, what the player holds is about to be replaced. An item dropped
     * then stayed on the ground and came back with the state as well. Past the local deadline of the
     * lease the session may already be someone else's, and nothing is done with it either.
     */
    public boolean inPlay(long nowNanos) {
        long heldUntil = heldUntilNanos.get();
        return inPlay && !isFenced() && heldUntil != NEVER_HELD && nowNanos - heldUntil < 0;
    }

    /**
     * Records that the lease may be acted on until {@code untilNanos} on the monotonic clock.
     *
     * <p>A later deadline never gives way to an earlier one: renewals that answer out of order must
     * not shorten what a newer one granted, and one that granted nothing new changes nothing.
     */
    public void holdUntil(long untilNanos) {
        heldUntilNanos.accumulateAndGet(
                untilNanos, (held, offered) -> held == NEVER_HELD || offered - held > 0 ? offered : held);
    }

    /** When, on the monotonic clock, this node stops acting on the lease. */
    public long heldUntilNanos() {
        return heldUntilNanos.get();
    }

    /** The player now holds this session's state. */
    public void enterPlay() {
        inPlay = true;
    }

    /** What the player holds is about to be replaced; nothing may be done with it until it is. */
    public void leavePlay() {
        inPlay = false;
    }

    /**
     * A window that writes the player's state itself has closed, holding what the player held then, and its write
     * has not landed. Until it has, nothing else moves the player's state: written after a crossing or a switch,
     * it would put back what the player held before it.
     */
    public void selfWriteBegun() {
        selfWrites.incrementAndGet();
    }

    /** The write {@link #selfWriteBegun} announced has landed, or been settled. */
    public void selfWriteLanded() {
        selfWrites.decrementAndGet();
    }

    /** Whether a write of the player's state taken earlier is still to land. */
    public boolean selfWriteOutstanding() {
        return selfWrites.get() > 0;
    }

    public boolean isFenced() {
        return state.get() == SessionState.LOCAL_FENCED;
    }

    public SessionState state() {
        SessionState s = state.get();
        return s != null ? s : SessionState.ACTIVE;
    }

    /**
     * Takes ownership of the two repeating tasks that keep this session alive.
     *
     * <p>A join runs async and a fence can land in the middle of it. A task attached after the
     * fence has closed the previous pair would keep firing forever against a session this node no
     * longer owns, so a fenced session closes them straight away instead of holding them.
     */
    public void attachTasks(AutoCloseable heartbeat, AutoCloseable checkpoint) {
        Objects.requireNonNull(heartbeat, "heartbeat");
        Objects.requireNonNull(checkpoint, "checkpoint");
        heartbeatTask.set(heartbeat);
        checkpointTask.set(checkpoint);
        if (isFenced()) {
            closeTasks();
        }
    }

    public void fence() {
        state.set(SessionState.LOCAL_FENCED);
        closeTasks();
    }

    @SuppressWarnings("EmptyCatch")
    public void closeTasks() {
        AutoCloseable hb = heartbeatTask.getAndSet(null);
        if (hb != null) {
            try {
                hb.close();
            } catch (Exception ignored) {
            }
        }
        AutoCloseable cp = checkpointTask.getAndSet(null);
        if (cp != null) {
            try {
                cp.close();
            } catch (Exception ignored) {
            }
        }
    }
}
