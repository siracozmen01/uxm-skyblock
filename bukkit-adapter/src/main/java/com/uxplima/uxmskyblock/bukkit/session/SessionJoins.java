package com.uxplima.uxmskyblock.bukkit.session;

import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;

import org.bukkit.entity.Player;

import com.uxplima.uxmskyblock.bukkit.inventory.BukkitInventorySerializer;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.inventory.ProfileInventoryRecord;
import com.uxplima.uxmskyblock.core.domain.session.PlayerSessionRecord;
import com.uxplima.uxmskyblock.core.domain.session.SessionAuthorityOutcome;
import com.uxplima.uxmskyblock.core.domain.session.SessionLease;
import com.uxplima.uxmskyblock.core.domain.session.SessionState;

/**
 * A player's join: the session claimed, their profile's inventory loaded and written, retried while another server still holds them. Split out of {@link PlayerSessionCoordinator}, whose services and words it uses, so neither file is over the size the
 * standards draw a line at.
 */
final class SessionJoins {

    private static final java.util.logging.Logger LOGGER =
            java.util.logging.Logger.getLogger(SessionJoins.class.getName());

    private final PlayerSessionCoordinator host;

    SessionJoins(PlayerSessionCoordinator host) {
        this.host = host;
    }

    void attemptJoin(Player player, long queuedAt, boolean told) {
        UUID rawUuid = player.getUniqueId();
        PlayerUuid playerUuid = new PlayerUuid(rawUuid);
        ProfileId defaultProfileId = new ProfileId(rawUuid);

        host.schedulerPort.async(() -> {
            try {
                long asked = host.nanoClock.getAsLong();
                SessionAuthorityOutcome outcome =
                        host.sessionAuthorityPort.ensureSession(playerUuid, defaultProfileId, host.nodeId);

                if (!outcome.isSuccess()) {
                    host.loginQueue.waitOrRefuse(player, queuedAt, told);
                    return;
                }

                SessionAuthorityOutcome.Success success = (SessionAuthorityOutcome.Success) outcome;
                long epoch = success.epoch();

                // If node took over an expired lease or crash occurred, reconcile in-flight profile switch operations
                host.switchProfileUseCase.recoverInFlightSwitch(playerUuid, host.nodeId, epoch);

                if (success.isRecovering()) {
                    host.sessionAuthorityPort.markRecoveredActive(playerUuid, host.nodeId, epoch);
                }

                Optional<PlayerSessionRecord> sessionOpt = host.sessionAuthorityPort.findSession(playerUuid);
                ProfileId activeProfile =
                        sessionOpt.map(PlayerSessionRecord::activeProfileId).orElse(defaultProfileId);

                host.cutShort.settle(playerUuid, activeProfile, epoch);

                Optional<ProfileInventoryRecord> invOpt = host.inventoryCheckpointPort.loadInventory(activeProfile);
                long initialVersion =
                        invOpt.map(ProfileInventoryRecord::version).orElse(1L);

                ActiveSession session =
                        new ActiveSession(playerUuid, activeProfile, epoch, initialVersion, SessionState.ACTIVE);
                session.holdUntil(asked + SessionLease.locallyHeld().toNanos());
                host.activeSessions.put(rawUuid, session);

                // Apply inventory and bind protection profile on entity thread
                host.schedulerPort.onEntity(playerUuid, () -> {
                    if (!player.isOnline() || session.isFenced()) {
                        return;
                    }
                    if (invOpt.isPresent()) {
                        BukkitInventorySerializer.applyToPlayer(player, invOpt.get());
                    }
                    session.enterPlay();
                    host.protectionListener.setActiveProfile(playerUuid, activeProfile);
                    host.runActiveHooks(player);
                });

                // Start async heartbeat task with fail-closed self-fencing
                AutoCloseable hbTask = host.schedulerPort.repeatAsync(
                        () -> {
                            if (session.isFenced()) {
                                return;
                            }
                            // The deadline counts from the moment the renewal was asked for, not from
                            // its answer: the database extended the lease no earlier than that.
                            long renewAsked = host.nanoClock.getAsLong();
                            SessionAuthorityOutcome renewOutcome;
                            try {
                                renewOutcome = host.sessionAuthorityPort.renew(
                                        playerUuid, host.nodeId, session.sessionEpoch());
                            } catch (RuntimeException unreachable) {
                                // A renewal the database never answered is a failed one. Thrown out of
                                // the task, it left the session standing until its deadline ran out.
                                LOGGER.log(Level.WARNING, "Lease renewal for " + playerUuid + " failed", unreachable);
                                host.selfFencePlayer(playerUuid, "Lease renewal could not reach the database");
                                return;
                            }
                            long deadline =
                                    renewAsked + SessionLease.locallyHeld().toNanos();
                            if (!renewOutcome.isSuccess()) {
                                host.selfFencePlayer(playerUuid, "Lease renewal failed or rejected: " + renewOutcome);
                            } else if (host.nanoClock.getAsLong() - deadline >= 0) {
                                // An answer later than the deadline it would set: this node stopped
                                // acting on the lease while it waited, and the player is safer moved on.
                                host.selfFencePlayer(playerUuid, "Lease renewal answered after the local deadline");
                            } else {
                                session.holdUntil(deadline);
                            }
                        },
                        host.heartbeatInterval,
                        host.heartbeatInterval);

                // Start async periodic checkpoint task
                AutoCloseable cpTask = host.schedulerPort.repeatAsync(
                        () -> host.checkpointPlayer(playerUuid), host.checkpointInterval, host.checkpointInterval);

                session.attachTasks(hbTask, cpTask);

            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Unexpected error during player join for " + playerUuid, e);
                host.schedulerPort.onEntity(playerUuid, () -> {
                    if (player.isOnline()) {
                        player.kick(host.messages.render(player, "session.init_error"));
                    }
                });
            }
        });
    }
}
