package com.uxplima.uxmskyblock.bukkit.session;

import java.util.Objects;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.entity.Player;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.inventory.BukkitInventorySerializer;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.core.application.profile.SwitchProfileUseCase;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.inventory.ProfileInventoryRecord;
import com.uxplima.uxmskyblock.core.domain.result.Result;
import com.uxplima.uxmskyblock.core.domain.result.Unit;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import org.jspecify.annotations.Nullable;

/**
 * A player's switch from one profile to another, on the threads each step belongs to.
 *
 * <p>The switch reads what the player holds on their own thread, prepares the switch off it, puts the
 * other profile's state on the player back on their thread and commits off it again. The session
 * coordinator hands it the sessions and hooks it needs and keeps the rest of a session's life.
 */
final class ProfileSwitchFlow {

    private static final Logger LOGGER = Logger.getLogger(ProfileSwitchFlow.class.getName());

    private final ServerNodeId nodeId;
    private final SwitchProfileUseCase switchProfileUseCase;
    private final CutShortOperations cutShort;
    private final SchedulerPort schedulerPort;
    private final IslandProtectionListener protectionListener;
    private final Messages messages;
    private final Function<UUID, @Nullable ActiveSession> sessions;
    private final BiConsumer<Player, ProfileId> leftHooks;
    private final Consumer<Player> activeHooks;
    private final java.util.function.LongSupplier nanoClock;

    ProfileSwitchFlow(
            ServerNodeId nodeId,
            SwitchProfileUseCase switchProfileUseCase,
            CutShortOperations cutShort,
            SchedulerPort schedulerPort,
            IslandProtectionListener protectionListener,
            Messages messages,
            Function<UUID, @Nullable ActiveSession> sessions,
            BiConsumer<Player, ProfileId> leftHooks,
            Consumer<Player> activeHooks,
            java.util.function.LongSupplier nanoClock) {
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
        this.switchProfileUseCase = Objects.requireNonNull(switchProfileUseCase, "switchProfileUseCase");
        this.cutShort = Objects.requireNonNull(cutShort, "cutShort");
        this.schedulerPort = Objects.requireNonNull(schedulerPort, "schedulerPort");
        this.protectionListener = Objects.requireNonNull(protectionListener, "protectionListener");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.leftHooks = Objects.requireNonNull(leftHooks, "leftHooks");
        this.activeHooks = Objects.requireNonNull(activeHooks, "activeHooks");
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
    }

    /**
     * Executes the crash-consistent 2-phase profile switch protocol.
     */
    void switchProfile(Player player, ProfileId targetProfileId) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(targetProfileId, "targetProfileId");

        UUID rawUuid = player.getUniqueId();
        ActiveSession session = sessions.apply(rawUuid);
        if (session == null || session.isFenced()) {
            messages.send(player, "session.none_active");
            return;
        }

        if (session.activeProfileId().equals(targetProfileId)) {
            messages.send(player, "session.already_on_profile");
            return;
        }

        PlayerUuid playerUuid = session.playerUuid();
        ProfileId currentProfile = session.activeProfileId();

        // 1. Snapshot source inventory on entity thread
        schedulerPort.onEntity(playerUuid, () -> {
            if (!player.isOnline() || session.isFenced()) {
                return;
            }
            // Closed first: a window that writes the player's state itself writes as it closes, and the switch
            // waits for that write to land.
            player.closeInventory();
            if (!session.inPlay(nanoClock.getAsLong()) || session.selfWriteOutstanding()) {
                // Another change of what the player holds is under way: a crossing between groups of worlds,
                // or a window's write. A switch started now would land in its middle.
                messages.send(player, "session.switch_failed");
                return;
            }

            // Nothing is done with what the player holds until the other profile's state replaces it.
            session.leavePlay();
            ProfileInventoryRecord srcSnapshot =
                    BukkitInventorySerializer.snapshotPlayer(player, currentProfile, session.lastDurableVersion());

            // 2. Prepare switch asynchronously
            schedulerPort.async(() -> {
                if (session.isFenced()) {
                    return;
                }
                UUID opId = UUID.randomUUID();
                Result<SwitchProfileUseCase.PreparedSwitch, String> prepRes = switchProfileUseCase.prepareSwitch(
                        opId, playerUuid, currentProfile, targetProfileId, nodeId, session.sessionEpoch(), srcSnapshot);

                if (prepRes.isErr()) {
                    LOGGER.log(Level.WARNING, "Failed to prepare profile switch: {0}", prepRes.errorOrThrow());
                    schedulerPort.onEntity(playerUuid, () -> {
                        session.enterPlay();
                        if (player.isOnline()) {
                            // The reason is a code for the log, and it used to be shown as it was.
                            messages.send(player, "session.switch_failed");
                        }
                    });
                    return;
                }

                SwitchProfileUseCase.PreparedSwitch prepared = prepRes.orElseThrow();

                // 3. Apply target state on entity thread (restores full inventory, enderchest, stats, potion effects,
                // gamemode, flight)
                schedulerPort.onEntity(playerUuid, () -> {
                    if (!player.isOnline() || session.isFenced()) {
                        return;
                    }

                    if (prepared.targetRecord() != null) {
                        BukkitInventorySerializer.applyToPlayer(player, prepared.targetRecord());
                    } else if (prepared.targetInventoryNbt().length > 0) {
                        var items = BukkitInventorySerializer.deserializeItemStacks(prepared.targetInventoryNbt());
                        player.getInventory().setContents(items);
                    } else {
                        player.getInventory().clear();
                        player.getEnderChest().clear();
                    }

                    protectionListener.setActiveProfile(playerUuid, targetProfileId);
                    session.enterPlay();

                    // 4. Complete switch asynchronously
                    schedulerPort.async(() -> {
                        if (session.isFenced()) {
                            return;
                        }
                        Result<Unit, String> compRes = switchProfileUseCase.completeSwitch(prepared);
                        if (compRes.isOk()) {
                            session.setActiveProfileId(targetProfileId);
                            cutShort.settle(playerUuid, targetProfileId, session.sessionEpoch());
                            long newVersion = prepared.targetRecord() != null
                                    ? prepared.targetRecord().version()
                                    : 1L;
                            session.setLastDurableVersion(newVersion);
                            schedulerPort.onEntity(playerUuid, () -> {
                                if (player.isOnline()) {
                                    messages.send(
                                            player,
                                            "session.switched",
                                            Placeholder.unparsed(
                                                    "profile",
                                                    targetProfileId.value().toString()));
                                    // The old profile has left and the new one has arrived.
                                    leftHooks.accept(player, currentProfile);
                                    activeHooks.accept(player);
                                }
                            });
                        } else {
                            LOGGER.log(Level.SEVERE, "Failed to complete profile switch: {0}", compRes.errorOrThrow());
                        }
                    });
                });
            });
        });
    }
}
