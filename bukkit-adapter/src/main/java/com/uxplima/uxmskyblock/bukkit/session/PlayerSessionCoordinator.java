package com.uxplima.uxmskyblock.bukkit.session;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.inventory.BukkitInventorySerializer;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.core.application.inventory.ProfileHandoffFinalizationPort;
import com.uxplima.uxmskyblock.core.application.inventory.ProfileInventoryCheckpointPort;
import com.uxplima.uxmskyblock.core.application.profile.SwitchProfileUseCase;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.session.PlayerSessionAuthorityPort;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.inventory.ProfileInventoryMutationOutcome;
import com.uxplima.uxmskyblock.core.domain.inventory.ProfileInventoryRecord;
import com.uxplima.uxmskyblock.core.domain.session.PlayerSessionRecord;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import com.uxplima.uxmskyblock.core.domain.session.SessionAuthorityOutcome;
import org.jspecify.annotations.Nullable;

/**
 * Coordinates player session lifecycle, heartbeat renewals, periodic checkpoints,
 * clean handoff flushes, and profile switches on Folia/Paper.
 */
public final class PlayerSessionCoordinator {

    /** A player's join: the session claimed, their profile's inventory loaded and written, retried while another server still holds them. */
    private final SessionJoins joins = new SessionJoins(this);

    private static final Logger LOGGER = Logger.getLogger(PlayerSessionCoordinator.class.getName());

    final ServerNodeId nodeId;
    final PlayerSessionAuthorityPort sessionAuthorityPort;
    final ProfileInventoryCheckpointPort inventoryCheckpointPort;
    private final ProfileHandoffFinalizationPort handoffFinalizationPort;
    final SwitchProfileUseCase switchProfileUseCase;
    final CutShortOperations cutShort;
    final SchedulerPort schedulerPort;
    final IslandProtectionListener protectionListener;
    final Duration heartbeatInterval;
    final Duration checkpointInterval;

    final ConcurrentMap<UUID, ActiveSession> activeSessions = new ConcurrentHashMap<>();
    final Messages messages;
    final java.util.function.LongSupplier nanoClock;
    private final ProfileSwitchFlow switchFlow;
    private final SessionShutdown shutdownFlow;
    private final ServerHandoff serverHandoff;
    private final SessionHooks hooks = new SessionHooks();
    final LoginQueue loginQueue;

    public PlayerSessionCoordinator(
            ServerNodeId nodeId,
            PlayerSessionAuthorityPort sessionAuthorityPort,
            ProfileInventoryCheckpointPort inventoryCheckpointPort,
            ProfileHandoffFinalizationPort handoffFinalizationPort,
            SwitchProfileUseCase switchProfileUseCase,
            com.uxplima.uxmskyblock.core.application.inventory.InventoryJournalRecovery journalRecovery,
            SchedulerPort schedulerPort,
            IslandProtectionListener protectionListener,
            Duration heartbeatInterval,
            Duration checkpointInterval,
            Messages messages) {
        this(
                nodeId,
                sessionAuthorityPort,
                inventoryCheckpointPort,
                handoffFinalizationPort,
                switchProfileUseCase,
                journalRecovery,
                schedulerPort,
                protectionListener,
                heartbeatInterval,
                checkpointInterval,
                messages,
                System::nanoTime);
    }

    /**
     * The same, counting the lease on {@code nanoClock}, a monotonic clock in nanoseconds: a test
     * moves it, a server uses {@link System#nanoTime}.
     */
    public PlayerSessionCoordinator(
            ServerNodeId nodeId,
            PlayerSessionAuthorityPort sessionAuthorityPort,
            ProfileInventoryCheckpointPort inventoryCheckpointPort,
            ProfileHandoffFinalizationPort handoffFinalizationPort,
            SwitchProfileUseCase switchProfileUseCase,
            com.uxplima.uxmskyblock.core.application.inventory.InventoryJournalRecovery journalRecovery,
            SchedulerPort schedulerPort,
            IslandProtectionListener protectionListener,
            Duration heartbeatInterval,
            Duration checkpointInterval,
            Messages messages,
            java.util.function.LongSupplier nanoClock) {
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
        this.sessionAuthorityPort = Objects.requireNonNull(sessionAuthorityPort, "sessionAuthorityPort");
        this.inventoryCheckpointPort = Objects.requireNonNull(inventoryCheckpointPort, "inventoryCheckpointPort");
        this.handoffFinalizationPort = Objects.requireNonNull(handoffFinalizationPort, "handoffFinalizationPort");
        this.switchProfileUseCase = Objects.requireNonNull(switchProfileUseCase, "switchProfileUseCase");
        this.cutShort = new CutShortOperations(journalRecovery, nodeId);
        this.schedulerPort = Objects.requireNonNull(schedulerPort, "schedulerPort");
        this.protectionListener = Objects.requireNonNull(protectionListener, "protectionListener");
        this.heartbeatInterval = Objects.requireNonNull(heartbeatInterval, "heartbeatInterval");
        this.checkpointInterval = Objects.requireNonNull(checkpointInterval, "checkpointInterval");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
        this.switchFlow = new ProfileSwitchFlow(
                this.nodeId,
                this.switchProfileUseCase,
                this.cutShort,
                this.schedulerPort,
                this.protectionListener,
                this.messages,
                activeSessions::get,
                this::runLeftHooks,
                this::runActiveHooks,
                this.nanoClock);
        this.shutdownFlow = new SessionShutdown(
                this.nodeId, this.sessionAuthorityPort, this.handoffFinalizationPort, this.schedulerPort, hooks);
        this.loginQueue = new LoginQueue(this.schedulerPort, this.messages, this.nanoClock, joins::attemptJoin);
        this.serverHandoff = new ServerHandoff(
                this.nodeId,
                this.sessionAuthorityPort,
                this.handoffFinalizationPort,
                this.schedulerPort,
                this.protectionListener,
                this.nanoClock,
                activeSessions,
                this::selfFencePlayer,
                player -> joins.attemptJoin(player, this.nanoClock.getAsLong(), true));
    }

    /** The node this coordinator holds sessions for. */
    public ServerNodeId nodeId() {
        return nodeId;
    }

    public @Nullable ActiveSession getActiveSession(UUID playerUuid) {
        return activeSessions.get(playerUuid);
    }

    /** Whether {@code playerUuid} holds their session's state here and may use it. */
    public boolean inPlay(UUID playerUuid) {
        ActiveSession session = activeSessions.get(playerUuid);
        return session != null && session.inPlay(nanoClock.getAsLong());
    }

    /**
     * Resolves the canonical active profile for a connected player, if one is currently active and not fenced.
     */
    public Optional<ProfileId> activeProfile(PlayerUuid playerUuid) {
        ActiveSession session = activeSessions.get(playerUuid.value());
        if (session != null && !session.isFenced()) {
            return Optional.of(session.activeProfileId());
        }
        return Optional.empty();
    }

    /**
     * Resolves the canonical active profile for a connected player by Bukkit UUID.
     */
    public Optional<ProfileId> activeProfile(UUID rawUuid) {
        return activeProfile(new PlayerUuid(rawUuid));
    }

    public Optional<ProfileId> findDurableActiveProfile(UUID playerUuid) {
        ActiveSession session = activeSessions.get(playerUuid);
        if (session != null && !session.isFenced()) {
            return Optional.of(session.activeProfileId());
        }
        return sessionAuthorityPort.findSession(new PlayerUuid(playerUuid)).map(PlayerSessionRecord::activeProfileId);
    }

    /** Settles the cargo moves a crash cut short too, as each player's session starts here. */
    public void settleCargoWith(com.uxplima.uxmskyblock.core.application.inventory.CargoJournalRecovery recovery) {
        cutShort.settleCargoWith(recovery);
    }

    /** Settles the trades a crash cut short too, each side as its player's session starts here. */
    public void settleTradesWith(com.uxplima.uxmskyblock.core.application.inventory.TradeJournalRecovery recovery) {
        cutShort.settleTradesWith(recovery);
    }

    /**
     * Executes runtime self-fencing when lease renewal fails or is rejected:
     * transitions session to LOCAL_FENCED, cancels timers, and disconnects player fail-closed.
     */
    public void selfFencePlayer(PlayerUuid playerUuid, String reason) {
        ActiveSession session = activeSessions.remove(playerUuid.value());
        if (session != null) {
            session.fence();
            protectionListener.removeActiveProfile(playerUuid);
        }
        LOGGER.log(Level.SEVERE, "Runtime self-fencing player {0}: {1}", new Object[] {playerUuid, reason});
        schedulerPort.onEntity(playerUuid, () -> {
            Player player = Bukkit.getPlayer(playerUuid.value());
            if (player != null && player.isOnline()) {
                player.kick(messages.render(player, "session.lease_lost"));
            }
        });
    }

    /**
     * Runs {@code hook} for each player whose session is made, on the player's own thread.
     *
     * <p>A session is made off the join thread, after the join event, so anything that needs the
     * player's profile cannot ask for it at join.
     */
    public void whenSessionActive(java.util.function.Consumer<Player> hook) {
        hooks.whenActive(hook);
    }

    /**
     * Runs {@code hook} when a player switches away from a profile, before the session hooks run for
     * the one they switched to.
     */
    public void whenProfileLeft(java.util.function.BiConsumer<Player, ProfileId> hook) {
        hooks.whenLeft(hook);
    }

    /**
     * Runs {@code hook} for each player still online when the server stops, on the stopping thread and
     * before their state is written, for a feature that writes that state itself.
     */
    public void whenStopping(java.util.function.Consumer<Player> hook) {
        hooks.whenStopping(hook);
    }

    void runActiveHooks(Player player) {
        hooks.runActive(player);
    }

    private void runLeftHooks(Player player, ProfileId left) {
        hooks.runLeft(player, left);
    }

    /**
     * Handles player join: ensures session authority, applies saved inventory, starts heartbeats & checkpoints.
     */
    public void handlePlayerJoin(Player player) {
        Objects.requireNonNull(player, "player");
        joins.attemptJoin(player, nanoClock.getAsLong(), false);
    }

    /** How long a player may wait for their session to leave another server; see {@link LoginQueue}. */
    static final Duration LOGIN_WAIT = LoginQueue.LOGIN_WAIT;

    /**
     * Ambient checkpoint of player inventory.
     */
    public void checkpointPlayer(PlayerUuid playerUuid) {
        ActiveSession session = activeSessions.get(playerUuid.value());
        if (session == null || session.isFenced()) {
            return;
        }

        schedulerPort.onEntity(playerUuid, () -> {
            Player player = Bukkit.getPlayer(playerUuid.value());
            if (player == null || !player.isOnline() || session.isFenced()) {
                return;
            }
            ProfileInventoryRecord taken = BukkitInventorySerializer.snapshotPlayer(
                    player, session.activeProfileId(), session.lastDurableVersion());
            org.bukkit.inventory.Inventory top = player.getOpenInventory().getTopInventory();
            WritesPlayerStateItself selfWriting =
                    WritesPlayerStateItself.of(top).orElse(null);
            if (top != null && selfWriting != null) {
                // The window writes what the player holds when it closes. Until then the player is
                // written as the stored state leaves them, or not at all; see WritesPlayerStateItself.
                org.bukkit.inventory.ItemStack[] asStored = selfWriting.inventoryAsStored(player, top);
                if (asStored == null) {
                    return;
                }
                taken = taken.withInventoryNbt(
                        taken.version(), BukkitInventorySerializer.serializeItemStacks(asStored));
            }
            ProfileInventoryRecord snapshot = taken;

            schedulerPort.async(() -> {
                if (session.isFenced()) {
                    return;
                }
                if (!session.writes().tryLock()) {
                    // Another write of this player is landing; the next checkpoint writes after it.
                    return;
                }
                try {
                    checkpointNow(playerUuid, session, snapshot);
                } finally {
                    session.writes().unlock();
                }
            });
        });
    }

    /** Writes one checkpoint, holding the session's write lock. */
    private void checkpointNow(PlayerUuid playerUuid, ActiveSession session, ProfileInventoryRecord snapshot) {
        ProfileInventoryMutationOutcome outcome;
        try {
            // Written over the version the snapshot was taken at. A write that landed between the two, a vault
            // save or a crossing between groups of worlds, holds what came after the snapshot, and the snapshot
            // written over it would hand back what the player had before.
            outcome = inventoryCheckpointPort.checkpointInventory(
                    playerUuid,
                    session.activeProfileId(),
                    nodeId,
                    session.sessionEpoch(),
                    snapshot.version(),
                    snapshot);
        } catch (RuntimeException e) {
            // A database that did not answer loses nothing: the session keeps the version it
            // last wrote and the next checkpoint writes the whole state again. Thrown out of
            // the repeating task, this could stop every checkpoint after it.
            LOGGER.log(
                    Level.WARNING,
                    "Ambient checkpoint failed for " + playerUuid.value() + "; the next one writes the same state",
                    e);
            return;
        }

        if (outcome instanceof ProfileInventoryMutationOutcome.Success succ) {
            session.setLastDurableVersion(succ.newVersion());
        } else {
            LOGGER.log(Level.WARNING, "Ambient checkpoint rejected for {0}: {1}", new Object[] {playerUuid, outcome});
        }
    }

    /**
     * Handles player quit: cancels background tasks, drains authority, and flushes final inventory.
     */
    public void handlePlayerQuit(Player player) {
        Objects.requireNonNull(player, "player");
        UUID rawUuid = player.getUniqueId();
        ActiveSession session = activeSessions.remove(rawUuid);
        if (session == null || session.isFenced()) {
            return;
        }

        session.closeTasks();
        protectionListener.removeActiveProfile(session.playerUuid());

        // Snapshot current inventory on entity thread before player disconnects
        ProfileInventoryRecord snapshot = BukkitInventorySerializer.snapshotPlayer(
                player, session.activeProfileId(), session.lastDurableVersion());

        schedulerPort.async(() -> {
            try {
                // Drain session first
                SessionAuthorityOutcome drainOutcome =
                        sessionAuthorityPort.drain(session.playerUuid(), nodeId, session.sessionEpoch());
                if (!(drainOutcome instanceof SessionAuthorityOutcome.Success)) {
                    LOGGER.log(Level.SEVERE, "Failed to drain session on quit for {0}: {1}", new Object[] {
                        session.playerUuid(), drainOutcome
                    });
                    return;
                }

                // Authoritatively flush final snapshot
                ProfileInventoryMutationOutcome outcome = handoffFinalizationPort.finalizeHandoffFlush(
                        session.playerUuid(),
                        session.activeProfileId(),
                        nodeId,
                        session.sessionEpoch(),
                        session.lastDurableVersion(),
                        snapshot);

                if (outcome instanceof ProfileInventoryMutationOutcome.Success succ) {
                    session.setLastDurableVersion(succ.newVersion());
                    // Release lease to OFFLINE state (SES-003)
                    sessionAuthorityPort.releaseToOffline(session.playerUuid(), nodeId, session.sessionEpoch());
                } else {
                    LOGGER.log(Level.SEVERE, "Handoff finalization flush failed on quit for {0}: {1}", new Object[] {
                        session.playerUuid(), outcome
                    });
                }
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Error finalizing handoff flush on quit for " + session.playerUuid(), e);
            }
        });
    }

    /**
     * Hands the player's session to {@code target} before the proxy moves them there; see
     * {@link ServerHandoff}.
     */
    public java.util.concurrent.CompletableFuture<Boolean> handOff(Player player, ServerNodeId target) {
        return serverHandoff.handOff(player, target);
    }

    /** Takes the session back for a player the proxy did not move, once the handoff has lapsed. */
    void reclaimIfStillHere(Player player) {
        serverHandoff.reclaimIfStillHere(player);
    }

    /**
     * Executes the crash-consistent 2-phase profile switch protocol.
     */
    public void switchProfile(Player player, ProfileId targetProfileId) {
        switchFlow.switchProfile(player, targetProfileId);
    }

    /**
     * Gracefully shuts down all active player sessions during plugin disable.
     */
    public void shutdown() {
        shutdownFlow.drain(activeSessions.values());
        activeSessions.clear();
    }
}
