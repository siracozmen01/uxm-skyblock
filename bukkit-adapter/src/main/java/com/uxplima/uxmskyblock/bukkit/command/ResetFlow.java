package com.uxplima.uxmskyblock.bukkit.command;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import com.mojang.brigadier.context.CommandContext;
import com.uxplima.uxmlib.command.Cmd;
import com.uxplima.uxmskyblock.bukkit.i18n.DurationText;
import com.uxplima.uxmskyblock.bukkit.menu.IslandResetConfirmationMenu;
import com.uxplima.uxmskyblock.core.application.antiabuse.IslandAntiAbuseService;
import com.uxplima.uxmskyblock.core.application.recycle.IslandRecycleService;
import com.uxplima.uxmskyblock.core.application.recycle.IslandRecycleService.RecycleResult;
import com.uxplima.uxmskyblock.core.domain.antiabuse.ResetCheckResult;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import org.jspecify.annotations.Nullable;

/**
 * {@code /is reset}: the warning, the window that asks erase or keep, or the typed confirmation, and the erasure under
 * every rule a reset is held to. Split out of {@link IslandLifecycleCommands}, whose services and words it uses, so
 * neither file is over the size the standards draw a line at.
 */
final class ResetFlow {

    private static final java.util.logging.Logger LOGGER =
            java.util.logging.Logger.getLogger(ResetFlow.class.getName());

    private final IslandLifecycleCommands host;

    ResetFlow(IslandLifecycleCommands host) {
        this.host = host;
    }

    int executeReset(CommandContext<CommandSourceStack> ctx) {
        Audience sender = ctx.getSource().getSender();
        if (!(sender instanceof Player player)) {
            host.send(sender, "error.players_only");
            return Cmd.OK;
        }
        IslandRecycleService recycleService = host.recycleServiceProvider.get();
        if (recycleService == null) {
            host.send(player, "reset.service_disabled");
            return Cmd.OK;
        }

        Optional<ProfileId> optProfile = host.activeProfile(player);
        if (optProfile.isEmpty()) {
            host.send(player, "error.session_not_active");
            return Cmd.OK;
        }

        ProfileId profileId = optProfile.get();
        PlayerUuid playerUuid = new PlayerUuid(player.getUniqueId());
        IslandAntiAbuseService antiAbuse = host.antiAbuseServiceProvider.get();
        // The permission is read here, on the thread that owns the player, and carried into the
        // scheduler. Everything after it is storage.
        boolean bypass = host.mayBypassTheResetRules(player);

        host.schedulerPort.async(() -> {
            if (host.islandLocationService.findIslandId(profileId).isEmpty()) {
                host.send(player, "error.no_island");
                return;
            }
            if (checkResetBlocked(player, antiAbuse, bypass)) {
                return;
            }

            // The window asks with two buttons, erase and keep, and nothing to copy out of chat. A code to
            // type back proved nothing the button did not: the player who clicks it is the owner who asked.
            IslandResetConfirmationMenu resetMenu = host.resetMenuProvider.get();
            if (resetMenu == null) {
                host.send(player, "reset.warning");
                host.send(player, "reset.warning_detail");
                host.send(player, "reset.confirm_hint");
                return;
            }
            // Opening an inventory belongs to the thread that owns the player.
            host.schedulerPort.onEntity(playerUuid, () -> {
                if (player.isOnline()) {
                    resetMenu.open(player, () -> resetConfirmed(player, recycleService));
                }
            });
        });
        return Cmd.OK;
    }

    private boolean checkResetBlocked(Player player, @Nullable IslandAntiAbuseService antiAbuse, boolean bypass) {
        if (antiAbuse == null) {
            return false;
        }
        ResetCheckResult check = antiAbuse.checkResetAllowed(new PlayerUuid(player.getUniqueId()), bypass);
        if (check instanceof ResetCheckResult.CooldownActive cd) {
            host.send(
                    player,
                    "reset.cooldown",
                    Placeholder.unparsed("remaining", DurationText.of(host.messages, player, cd.remaining())));
            return true;
        } else if (check instanceof ResetCheckResult.DailyLimitExceeded dl) {
            host.send(
                    player,
                    "reset.daily_limit",
                    Placeholder.unparsed("max", Integer.toString(dl.maxDailyResets())),
                    Placeholder.unparsed("remaining", DurationText.of(host.messages, player, dl.remaining())));
            return true;
        }
        return false;
    }

    int executeResetConfirm(CommandContext<CommandSourceStack> ctx) {
        Audience sender = ctx.getSource().getSender();
        if (!(sender instanceof Player player)) {
            host.send(sender, "error.players_only");
            return Cmd.OK;
        }
        IslandRecycleService recycleService = host.recycleServiceProvider.get();
        if (recycleService == null) {
            host.send(player, "reset.service_disabled");
            return Cmd.OK;
        }

        resetConfirmed(player, recycleService);
        return Cmd.OK;
    }
    /**
     * Erases the player's island once they have confirmed, under every rule a reset is held to.
     *
     * <p>The typed confirmation and the confirmation window both come here. The erasure still asks the
     * recycle service for a challenge and answers it at once, so the service's own guard, that only the
     * owner erases their island, holds as it always did. The window's button
     * erased the island on its own, so a reset from the window was never counted against the daily
     * allowance, had no guard against a double click and never emptied the inventory the operator
     * asked to have emptied.
     */
    private void resetConfirmed(Player player, IslandRecycleService recycleService) {
        Optional<ProfileId> optProfile = host.activeProfile(player);
        if (optProfile.isEmpty()) {
            host.send(player, "error.session_not_active");
            return;
        }

        ProfileId profileId = optProfile.get();
        IslandAntiAbuseService antiAbuse = host.antiAbuseServiceProvider.get();
        boolean bypass = host.mayBypassTheResetRules(player);

        host.schedulerPort.async(() -> {
            Optional<IslandId> optIsland = host.islandLocationService.findIslandId(profileId);
            if (optIsland.isEmpty()) {
                host.send(player, "error.no_island");
                return;
            }
            IslandId islandId = optIsland.get();
            if (checkResetBlocked(player, antiAbuse, bypass)) {
                return;
            }
            // The daily limit is recorded when the erasure finishes, seconds after it is checked. A
            // second confirmation arriving in between passed the same check, because nothing had
            // been recorded yet, and erased the island twice for one allowance.
            if (antiAbuse != null && !antiAbuse.beginReset(new PlayerUuid(player.getUniqueId()))) {
                host.send(player, "reset.already_running");
                return;
            }
            String code =
                    recycleService.generateResetChallenge(profileId, islandId).code();
            confirmReset(player, recycleService, antiAbuse, profileId, islandId, code);
        });
    }
    /** Erases the island and reports it, once the caller has been read off the database. */
    private void confirmReset(
            Player player,
            IslandRecycleService recycleService,
            @Nullable IslandAntiAbuseService antiAbuse,
            ProfileId profileId,
            IslandId islandId,
            String code) {
        PlayerUuid playerUuid = new PlayerUuid(player.getUniqueId());
        // Read before the island is gone: once it is erased nothing says who belonged to it.
        java.util.List<com.uxplima.uxmskyblock.core.domain.island.IslandMember> others = host.islandLocationService
                .findIsland(islandId)
                .map(island -> island.members().values().stream()
                        .filter(member -> !member.profileId().equals(profileId))
                        .toList())
                .orElse(java.util.List.of());
        CompletableFuture<RecycleResult> erasure;
        try {
            erasure = recycleService.executeReset(profileId, islandId, code, false);
        } catch (RuntimeException e) {
            // The erasure threw before it handed back anything to wait on. The reset it began has to
            // be given back here, or every later attempt is told one is running for as long as the
            // server runs, and the island is never erased.
            if (antiAbuse != null) {
                antiAbuse.endReset(playerUuid);
            }
            LOGGER.log(
                    java.util.logging.Level.WARNING,
                    e,
                    () -> "Resetting island " + islandId + " for " + player.getName() + " failed before it started.");
            host.send(player, "reset.failed");
            return;
        }
        var unused = erasure
                // Given back whether the erasure finished or threw, so a failure does not leave the
                // player unable to reset for as long as the server runs.
                .whenComplete((result, error) -> {
                    if (antiAbuse != null) {
                        antiAbuse.endReset(playerUuid);
                    }
                })
                .thenAccept(result -> {
                    // Counted here, off any player's thread, whether or not the player is still on.
                    // It was counted on the player's thread, so a player who left while the island
                    // was erased was never counted, and the count was a database write on the region.
                    if (result instanceof RecycleResult.Success) {
                        tellTheMembers(others, player.getName());
                    }
                    if (result instanceof RecycleResult.Success && antiAbuse != null) {
                        antiAbuse.recordReset(playerUuid, Instant.now());
                        // Owed first, paid below if the player is still here, and otherwise when
                        // they next play: a player who left kept everything they carried.
                        if (antiAbuse.purgeInventoryOnReset()) {
                            antiAbuse.oweInventoryPurge(playerUuid);
                        }
                    }
                    com.uxplima.uxmskyblock.bukkit.lifecycle.PlayerLifecycle rules = host.lifecycle.get();
                    if (result instanceof RecycleResult.Success && rules != null) {
                        rules.happened(
                                com.uxplima.uxmskyblock.core.domain.lifecycle.LifecycleEvent.RESET,
                                playerUuid,
                                profileId);
                    }
                    host.schedulerPort.onEntity(new PlayerUuid(player.getUniqueId()), () -> {
                        switch (result) {
                            case RecycleResult.Success s -> {
                                // The island is gone, so its marker must go with it. A web map that
                                // keeps drawing an island nobody can visit is worse than no map.
                                host.islandRemoved(islandId);
                                if (antiAbuse != null && antiAbuse.purgeInventoryOnReset()) {
                                    com.uxplima.uxmskyblock.bukkit.antiabuse.ResetInventoryPurge.purgeAndSettle(
                                            antiAbuse, host.schedulerPort, player);
                                }
                                // Asynchronous, because Folia throws on a synchronous teleport, and
                                // that left the player standing where their island had been with no
                                // word that it was gone.
                                var unusedTeleport =
                                        player.teleportAsync(player.getWorld().getSpawnLocation());
                                host.send(player, "reset.success");
                                host.send(player, "reset.success_hint");
                            }
                            case RecycleResult.NotOwner no -> host.send(player, "reset.not_owner");
                            case RecycleResult.InvalidChallenge ic -> host.send(player, "reset.invalid_challenge");
                            case RecycleResult.IslandNotFound nf -> host.send(player, "reset.island_not_found");
                            case RecycleResult.AlreadyRunning ar -> host.send(player, "reset.already_running");
                            case RecycleResult.Failure f -> {
                                LOGGER.warning(() -> "Resetting island " + islandId + " for " + player.getName()
                                        + " failed: " + f.reason());
                                host.send(player, "reset.failed");
                            }
                        }
                    });
                });
    }
    /**
     * Tells everyone who belonged to a reset island that it is gone: at once when they are on this
     * server, and on their next join otherwise. A member standing on the island was moved to spawn
     * with no word of why, and one who was away found out only by typing a command.
     */
    private void tellTheMembers(
            java.util.List<com.uxplima.uxmskyblock.core.domain.island.IslandMember> members, String owner) {
        for (com.uxplima.uxmskyblock.core.domain.island.IslandMember member : members) {
            Player online = Bukkit.getPlayer(member.playerUuid().value());
            if (online != null) {
                host.send(online, "reset.member_notice", Placeholder.unparsed("player", owner));
                continue;
            }
            var notices = host.notificationService;
            if (notices == null) {
                continue;
            }
            try {
                notices.notify(
                        member.profileId(),
                        com.uxplima.uxmskyblock.core.domain.notification.NotificationCategory.RESET,
                        "notification.island_reset",
                        Map.of("player", owner),
                        null);
            } catch (RuntimeException e) {
                // The island is gone either way. A notice that could not be written is a log line.
                LOGGER.log(
                        java.util.logging.Level.WARNING,
                        e,
                        () -> "Leaving a reset notice for " + member.profileId() + " failed.");
            }
        }
    }
}
