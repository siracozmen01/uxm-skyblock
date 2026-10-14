package com.uxplima.uxmskyblock.bukkit.command;

import java.util.Map;
import java.util.Optional;

import org.bukkit.entity.Player;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.uxplima.uxmlib.command.Cmd;
import com.uxplima.uxmskyblock.core.application.membership.IslandMembershipService;
import com.uxplima.uxmskyblock.core.domain.activity.ActivityEventType;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.notification.NotificationCategory;

/**
 * {@code /is role} and {@code /is permissions}: the roles of an island, what each may do, and the window that shapes them. Split out of {@link IslandMembershipCommands}, whose services and words it uses, so neither file is over the size the
 * standards draw a line at.
 */
final class RoleVerbs {

    private final IslandMembershipCommands host;

    RoleVerbs(IslandMembershipCommands host) {
        this.host = host;
    }

    int executeRole(CommandContext<CommandSourceStack> ctx) {
        String target = StringArgumentType.getString(ctx, "player");
        String role = StringArgumentType.getString(ctx, "role");
        return host.withService(
                ctx,
                (player, service, actor) -> host.schedulerPort.async(() -> {
                    Optional<ProfileId> optTarget = host.resolveProfile(target);
                    if (optTarget.isEmpty()) {
                        host.send(player, "member.unknown_player", Placeholder.unparsed("player", target));
                        return;
                    }
                    switch (service.setRole(actor, optTarget.get(), role)) {
                        case IslandMembershipService.RoleOutcome.Changed changed -> {
                            host.islandLocationService
                                    .findIslandId(actor)
                                    .ifPresent(islandId -> host.activityLog.recordForMembers(
                                            islandId,
                                            actor,
                                            ActivityEventType.ROLE_CHANGED,
                                            "activity.role_changed",
                                            Map.of(
                                                    "player",
                                                    target,
                                                    "role",
                                                    host.messages.stored(
                                                            "roles", changed.roleId(), changed.roleId()))));
                            host.tellOrLeave(
                                    optTarget.get(),
                                    "member.role_changed_you",
                                    NotificationCategory.ROLE_CHANGED,
                                    "notification.role_changed",
                                    Map.of(
                                            "player",
                                            player.getName(),
                                            "role",
                                            host.messages.stored("roles", changed.roleId(), changed.roleId())));
                            host.send(
                                    player,
                                    "member.role_changed",
                                    Placeholder.unparsed("player", target),
                                    Placeholder.unparsed(
                                            "role",
                                            host.messages.named(player, "roles", changed.roleId(), changed.roleId())));
                        }
                        case IslandMembershipService.RoleOutcome.NotAllowed ignored ->
                            host.send(player, "member.role_no_permission");
                        case IslandMembershipService.RoleOutcome.NoIsland ignored ->
                            host.send(player, "error.no_island");
                        case IslandMembershipService.RoleOutcome.NotAMember ignored ->
                            host.send(player, "member.not_a_member", Placeholder.unparsed("player", target));
                        case IslandMembershipService.RoleOutcome.UnknownRole unknown ->
                            host.send(
                                    player,
                                    "member.unknown_role",
                                    Placeholder.unparsed("role", unknown.roleId()),
                                    Placeholder.unparsed("roles", unknown.available()));
                        case IslandMembershipService.RoleOutcome.CannotChangeOwner ignored ->
                            host.send(player, "member.owner_stays");
                        case IslandMembershipService.RoleOutcome.NotAssignable anchor ->
                            host.send(
                                    player,
                                    "member.role_not_assignable",
                                    Placeholder.unparsed("role", anchor.roleId()),
                                    Placeholder.unparsed("roles", anchor.available()));
                        case IslandMembershipService.RoleOutcome.OutOfReach ignored ->
                            host.send(player, "member.role_out_of_reach");
                    }
                }));
    }

    int executeSetPermission(CommandContext<CommandSourceStack> ctx) {
        String role = StringArgumentType.getString(ctx, "role");
        String permission = StringArgumentType.getString(ctx, "permission");
        String state = StringArgumentType.getString(ctx, "state");
        boolean allowed =
                "on".equalsIgnoreCase(state) || "true".equalsIgnoreCase(state) || "yes".equalsIgnoreCase(state);
        boolean denied =
                "off".equalsIgnoreCase(state) || "false".equalsIgnoreCase(state) || "no".equalsIgnoreCase(state);
        if (!allowed && !denied) {
            host.send(ctx.getSource().getSender(), "member.permission_state", Placeholder.unparsed("state", state));
            return Cmd.OK;
        }

        return host.withService(
                ctx,
                (player, service, actor) -> host.schedulerPort.async(() -> {
                    switch (service.setRolePermission(actor, role, permission, allowed)) {
                        case IslandMembershipService.PermissionOutcome.Changed changed ->
                            host.send(
                                    player,
                                    changed.allowed() ? "member.permission_granted" : "member.permission_revoked",
                                    Placeholder.unparsed(
                                            "role",
                                            host.messages.named(player, "roles", changed.roleId(), changed.roleId())),
                                    Placeholder.unparsed(
                                            "permission",
                                            host.messages.named(
                                                    player,
                                                    "permissions",
                                                    changed.permission(),
                                                    changed.permission())));
                        case IslandMembershipService.PermissionOutcome.NotAllowed ignored ->
                            host.send(player, "member.role_no_permission");
                        case IslandMembershipService.PermissionOutcome.OutOfReach ignored ->
                            host.send(player, "member.permission_out_of_reach");
                        case IslandMembershipService.PermissionOutcome.NoIsland ignored ->
                            host.send(player, "error.no_island");
                        case IslandMembershipService.PermissionOutcome.UnknownRole unknown ->
                            host.send(
                                    player,
                                    "member.unknown_role",
                                    Placeholder.unparsed("role", unknown.roleId()),
                                    Placeholder.unparsed("roles", unknown.available()));
                        case IslandMembershipService.PermissionOutcome.UnknownPermission unknown ->
                            host.send(
                                    player,
                                    "member.unknown_permission",
                                    Placeholder.unparsed("permission", unknown.permission()),
                                    Placeholder.unparsed("permissions", unknown.available()));
                        case IslandMembershipService.PermissionOutcome.CannotChangeOwnerRole ignored ->
                            host.send(player, "member.owner_role_stays");
                    }
                }));
    }

    int executePermissionList(CommandContext<CommandSourceStack> ctx) {
        return host.withService(
                ctx,
                (player, service, actor) -> host.schedulerPort.async(() -> {
                    Optional<IslandId> optIsland = host.islandLocationService.findIslandId(actor);
                    Optional<com.uxplima.uxmskyblock.core.domain.island.Island> optIslandRow =
                            optIsland.flatMap(host.islandLocationService::findIsland);
                    if (optIslandRow.isEmpty()) {
                        host.send(player, "error.no_island");
                        return;
                    }
                    com.uxplima.uxmskyblock.core.domain.island.Island island = optIslandRow.get();
                    host.send(player, "member.permissions_header");
                    island.roles().values().stream()
                            .sorted(java.util.Comparator.comparingInt(
                                    com.uxplima.uxmskyblock.core.domain.island.IslandRole::weight))
                            .forEach(role -> host.send(
                                    player,
                                    "member.permissions_entry",
                                    Placeholder.unparsed(
                                            "role",
                                            host.messages.named(player, "roles", role.id(), role.displayName())),
                                    Placeholder.unparsed("permissions", host.permissionsOf(player, role))));
                }));
    }
    /** Runs {@code action} with the caller's profile when the roles of an owner can be made at all. */
    int withShaper(
            CommandContext<CommandSourceStack> ctx,
            IslandMembershipCommands.TriConsumer<
                            Player, com.uxplima.uxmskyblock.core.application.membership.IslandRoleShaper, ProfileId>
                    action) {
        return host.withService(ctx, (player, service, actor) -> {
            com.uxplima.uxmskyblock.core.application.membership.IslandRoleShaper shaper = host.roleShaper.get();
            if (shaper == null) {
                host.send(player, "member.disabled");
                return;
            }
            action.accept(player, shaper, actor);
        });
    }
    /** {@code /is role <player>}: the roles the player could be given, rather than an unknown command. */
    int executeRoleChoice(CommandContext<CommandSourceStack> ctx) {
        String target = StringArgumentType.getString(ctx, "player");
        return host.withService(
                ctx,
                (player, service, actor) -> host.schedulerPort.async(() -> service.assignableRoles(actor)
                        .ifPresentOrElse(
                                roles -> host.send(
                                        player,
                                        "member.role_choose",
                                        Placeholder.unparsed("player", target),
                                        Placeholder.unparsed("roles", roles)),
                                () -> host.send(player, "error.no_island"))));
    }
    /** {@code /is permissions create <name>}: a role of the owner's own, edited like the others. */
    int executeCreateRole(CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "name");
        return withShaper(ctx, (player, shaper, actor) -> {
            // The owner's nodes are read here, on their own thread, and the number goes with the request.
            int allowance = host.roleConfiguration.get().allowanceFor(player::hasPermission);
            host.schedulerPort.async(() -> answer(player, shaper.create(actor, name, allowance)));
        });
    }
    /** Says what came of making or taking away a role. */
    void answer(Player player, com.uxplima.uxmskyblock.core.application.membership.IslandRoleShaper.Outcome outcome) {
        switch (outcome) {
            case com.uxplima.uxmskyblock.core.application.membership.IslandRoleShaper.Outcome.Created created ->
                host.send(player, "member.role_created", Placeholder.unparsed("role", created.roleId()));
            case com.uxplima.uxmskyblock.core.application.membership.IslandRoleShaper.Outcome.Deleted deleted ->
                host.send(
                        player,
                        "member.role_deleted",
                        Placeholder.unparsed("role", deleted.roleId()),
                        Placeholder.unparsed("count", Integer.toString(deleted.moved())));
            case com.uxplima.uxmskyblock.core.application.membership.IslandRoleShaper.Outcome.NotOwner ignored ->
                host.send(player, "member.role_owner_only");
            case com.uxplima.uxmskyblock.core.application.membership.IslandRoleShaper.Outcome.NoIsland ignored ->
                host.send(player, "error.no_island");
            case com.uxplima.uxmskyblock.core.application.membership.IslandRoleShaper.Outcome.BadName bad ->
                host.send(player, "member.role_bad_name", Placeholder.unparsed("name", bad.name()));
            case com.uxplima.uxmskyblock.core.application.membership.IslandRoleShaper.Outcome.Taken taken ->
                host.send(player, "member.role_taken", Placeholder.unparsed("role", taken.roleId()));
            case com.uxplima.uxmskyblock.core.application.membership.IslandRoleShaper.Outcome.LimitReached limit ->
                host.send(player, "member.role_limit", Placeholder.unparsed("limit", Integer.toString(limit.limit())));
            case com.uxplima.uxmskyblock.core.application.membership.IslandRoleShaper.Outcome.UnknownRole unknown ->
                host.send(
                        player,
                        "member.role_unknown_own",
                        Placeholder.unparsed("role", unknown.roleId()),
                        Placeholder.unparsed("roles", unknown.available()));
            case com.uxplima.uxmskyblock.core.application.membership.IslandRoleShaper.Outcome.Shipped shipped ->
                host.send(
                        player,
                        "member.role_shipped",
                        Placeholder.unparsed(
                                "role", host.messages.named(player, "roles", shipped.roleId(), shipped.roleId())));
        }
    }
}
