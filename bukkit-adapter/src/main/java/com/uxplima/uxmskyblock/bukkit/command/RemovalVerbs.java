package com.uxplima.uxmskyblock.bukkit.command;

import java.util.Map;
import java.util.Optional;

import org.bukkit.entity.Player;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.uxplima.uxmskyblock.core.application.membership.IslandMembershipService;
import com.uxplima.uxmskyblock.core.domain.activity.ActivityEventType;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.notification.NotificationCategory;

/**
 * {@code /is kick} and {@code /is leave}: a member taken off an island, or leaving it, and what each of them is told. Split out of {@link IslandMembershipCommands}, whose services and words it uses, so neither file is over the size the
 * standards draw a line at.
 */
final class RemovalVerbs {

    private final IslandMembershipCommands host;

    RemovalVerbs(IslandMembershipCommands host) {
        this.host = host;
    }

    int executeKick(CommandContext<CommandSourceStack> ctx) {
        String target = StringArgumentType.getString(ctx, "player");
        return host.withService(
                ctx,
                (player, service, actor) -> host.schedulerPort.async(() -> {
                    Optional<ProfileId> optTarget = host.resolveProfile(target);
                    if (optTarget.isEmpty()) {
                        host.send(player, "member.unknown_player", Placeholder.unparsed("player", target));
                        return;
                    }
                    Optional<PlayerUuid> optTargetUuid = host.resolveUuid(target);
                    IslandMembershipService.RemovalOutcome outcome = service.kick(actor, optTarget.get());
                    if (outcome instanceof IslandMembershipService.RemovalOutcome.Removed removed) {
                        optTargetUuid.ifPresent(host.invites::lockOutOfTheNextIsland);
                        com.uxplima.uxmskyblock.bukkit.lifecycle.PlayerLifecycle rules = host.lifecycle.get();
                        if (rules != null && optTargetUuid.isPresent()) {
                            rules.happened(
                                    com.uxplima.uxmskyblock.core.domain.lifecycle.LifecycleEvent.KICK,
                                    optTargetUuid.get(),
                                    optTarget.get());
                        }
                        host.activityLog.recordForMembers(
                                removed.islandId(),
                                actor,
                                ActivityEventType.MEMBER_LEFT,
                                "activity.member_kicked",
                                Map.of("player", target, "actor", player.getName()));
                        host.tellOrLeave(
                                optTarget.get(),
                                "member.kicked_you",
                                NotificationCategory.KICK,
                                "notification.kicked",
                                Map.of("player", player.getName()));
                    }
                    reportRemoval(player, outcome, target);
                }));
    }

    int executeLeave(CommandContext<CommandSourceStack> ctx) {
        return host.withService(
                ctx,
                (player, service, actor) -> host.schedulerPort.async(() -> {
                    IslandMembershipService.RemovalOutcome outcome = service.leave(actor);
                    if (outcome instanceof IslandMembershipService.RemovalOutcome.Removed removed) {
                        host.invites.lockOutOfTheNextIsland(new PlayerUuid(player.getUniqueId()));
                        com.uxplima.uxmskyblock.bukkit.lifecycle.PlayerLifecycle rules = host.lifecycle.get();
                        if (rules != null) {
                            rules.happened(
                                    com.uxplima.uxmskyblock.core.domain.lifecycle.LifecycleEvent.LEAVE,
                                    new PlayerUuid(player.getUniqueId()),
                                    actor);
                        }
                        host.activityLog.recordForMembers(
                                removed.islandId(),
                                actor,
                                ActivityEventType.MEMBER_LEFT,
                                "activity.member_left",
                                Map.of("player", player.getName()));
                    }
                    reportRemoval(player, outcome, player.getName());
                }));
    }

    void reportRemoval(Player player, IslandMembershipService.RemovalOutcome outcome, String target) {
        switch (outcome) {
            case IslandMembershipService.RemovalOutcome.Removed removed ->
                host.send(player, "member.removed", Placeholder.unparsed("player", target));
            case IslandMembershipService.RemovalOutcome.NotAllowed ignored ->
                host.send(player, "member.kick_no_permission");
            case IslandMembershipService.RemovalOutcome.NoIsland ignored -> host.send(player, "error.no_island");
            case IslandMembershipService.RemovalOutcome.NotAMember ignored ->
                host.send(player, "member.not_a_member", Placeholder.unparsed("player", target));
            case IslandMembershipService.RemovalOutcome.CannotRemoveOwner ignored ->
                host.send(player, "member.owner_stays");
            case IslandMembershipService.RemovalOutcome.OutOfReach ignored ->
                host.send(player, "member.kick_out_of_reach");
        }
    }
}
