package com.uxplima.uxmskyblock.bukkit.command;

import java.util.Map;
import java.util.Optional;

import org.bukkit.entity.Player;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.uxplima.uxmskyblock.bukkit.i18n.DurationText;
import com.uxplima.uxmskyblock.core.application.antiabuse.IslandAntiAbuseService;
import com.uxplima.uxmskyblock.core.application.membership.IslandMembershipService;
import com.uxplima.uxmskyblock.core.domain.activity.ActivityEventType;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.IslandMember;
import com.uxplima.uxmskyblock.core.domain.notification.NotificationCategory;

/**
 * {@code /is invite}, {@code /is accept} and {@code /is deny}: asking a player onto an island, and their answer under the co-op hopping lock. Split out of {@link IslandMembershipCommands}, whose services and words it uses, so neither file is over the size the
 * standards draw a line at.
 */
final class InviteVerbs {

    private final IslandMembershipCommands host;

    InviteVerbs(IslandMembershipCommands host) {
        this.host = host;
    }

    int executeInvite(CommandContext<CommandSourceStack> ctx) {
        String target = StringArgumentType.getString(ctx, "player");
        return host.withService(
                ctx,
                (player, service, actor) -> host.schedulerPort.async(() -> {
                    Optional<ProfileId> optTarget = host.resolveProfile(target);
                    if (optTarget.isEmpty()) {
                        host.send(player, "member.unknown_player", Placeholder.unparsed("player", target));
                        return;
                    }
                    switch (service.invite(actor, optTarget.get())) {
                        case IslandMembershipService.InviteOutcome.Sent sent -> {
                            host.send(player, "member.invited", Placeholder.unparsed("player", target));
                            host.tellOrLeave(
                                    optTarget.get(),
                                    "member.invite_received",
                                    NotificationCategory.INVITE,
                                    null,
                                    Map.of("player", player.getName()));
                        }
                        case IslandMembershipService.InviteOutcome.NotAllowed ignored ->
                            host.send(player, "member.invite_no_permission");
                        case IslandMembershipService.InviteOutcome.NoIsland ignored ->
                            host.send(player, "error.no_island");
                        case IslandMembershipService.InviteOutcome.AlreadyAMember ignored ->
                            host.send(player, "member.already_a_member", Placeholder.unparsed("player", target));
                        case IslandMembershipService.InviteOutcome.AlreadyOnAnotherIsland ignored ->
                            host.send(player, "member.already_elsewhere", Placeholder.unparsed("player", target));
                        case IslandMembershipService.InviteOutcome.IslandFull full ->
                            host.send(
                                    player,
                                    "member.island_full",
                                    Placeholder.unparsed("max", Integer.toString(full.allowed())));
                    }
                }));
    }

    int executeAccept(CommandContext<CommandSourceStack> ctx) {
        return host.withService(
                ctx,
                (player, service, actor) -> host.schedulerPort.async(() -> {
                    PlayerUuid joining = new PlayerUuid(player.getUniqueId());
                    if (heldByTheCoopLock(player, joining)) {
                        return;
                    }
                    switch (service.accept(actor, joining)) {
                        case IslandMembershipService.JoinOutcome.Joined joined -> {
                            host.activityLog.recordForMembers(
                                    joined.islandId(),
                                    actor,
                                    ActivityEventType.MEMBER_JOINED,
                                    "activity.member_joined",
                                    Map.of("player", player.getName()));
                            host.send(player, "member.joined");
                            host.fireMilestone("member-joined", player);
                            // The island's other members on this server hear it at once; the ones
                            // who are not read it in the feed written above.
                            for (IslandMember member : service.members(joined.islandId())) {
                                if (!member.profileId().equals(actor)) {
                                    host.tellOrLeave(
                                            member.profileId(),
                                            "member.joined_your_island",
                                            NotificationCategory.INVITE,
                                            null,
                                            Map.of("player", player.getName()));
                                }
                            }
                        }
                        case IslandMembershipService.JoinOutcome.NoInvite ignored ->
                            host.send(player, "member.no_invite");
                        case IslandMembershipService.JoinOutcome.AlreadyOnAnIsland ignored ->
                            host.send(player, "member.you_have_an_island");
                        case IslandMembershipService.JoinOutcome.IslandMissing ignored ->
                            host.send(player, "error.no_island");
                        case IslandMembershipService.JoinOutcome.IslandFull full ->
                            host.send(
                                    player,
                                    "member.island_full",
                                    Placeholder.unparsed("max", Integer.toString(full.allowed())));
                    }
                }));
    }

    int executeDeny(CommandContext<CommandSourceStack> ctx) {
        return host.withService(
                ctx,
                (player, service, actor) -> host.schedulerPort.async(() -> {
                    host.send(player, service.decline(actor) ? "member.declined" : "member.no_invite");
                }));
    }
    /**
     * Whether this player left an island too recently to be recruited into another.
     *
     * <p>Leaving one island and being taken straight into the next is how a bank and a vault get
     * emptied by somebody who was never going to stay. The file names how long that waits and the
     * permission that lets staff past it, and both were read nowhere.
     *
     * <p>The refusal is sent here, so a caller that gets true has nothing left to say.
     */
    boolean heldByTheCoopLock(Player player, PlayerUuid playerUuid) {
        IslandAntiAbuseService lock = host.coopHoppingLock;
        if (lock == null) {
            return false;
        }
        com.uxplima.uxmskyblock.bukkit.config.AntiAbuseConfiguration rules = host.antiAbuseRules;
        String node = rules == null
                ? com.uxplima.uxmskyblock.bukkit.config.AntiAbuseConfiguration.DEFAULT_COOP_BYPASS_PERMISSION
                : rules.coopBypassPermission();
        boolean bypass = player.hasPermission(node) || player.isOp();

        if (lock.checkCoopJoinAllowed(playerUuid, bypass)
                instanceof com.uxplima.uxmskyblock.core.domain.antiabuse.CoopJoinCheckResult.CooldownActive held) {
            host.send(
                    player,
                    "member.coop_cooldown",
                    Placeholder.unparsed("remaining", DurationText.of(host.messages, player, held.remaining())));
            return true;
        }
        return false;
    }
    /** Starts the wait that stops this player being recruited into another island straight away. */
    void lockOutOfTheNextIsland(PlayerUuid playerUuid) {
        IslandAntiAbuseService lock = host.coopHoppingLock;
        if (lock != null) {
            var unused = lock.recordCoopDeparture(playerUuid, java.time.Instant.now());
        }
    }
}
