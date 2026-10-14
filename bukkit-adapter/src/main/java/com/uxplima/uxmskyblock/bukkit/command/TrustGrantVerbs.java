package com.uxplima.uxmskyblock.bukkit.command;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.uxplima.uxmlib.command.Cmd;
import com.uxplima.uxmlib.common.Durations;
import com.uxplima.uxmskyblock.bukkit.config.TemporaryAccessConfiguration;
import com.uxplima.uxmskyblock.bukkit.session.ActiveSession;
import com.uxplima.uxmskyblock.core.application.access.TemporaryAccessService;
import com.uxplima.uxmskyblock.core.domain.access.CurrentNodeProcessIdentity;
import com.uxplima.uxmskyblock.core.domain.access.TemporaryAccessGrant;
import com.uxplima.uxmskyblock.core.domain.access.TerminationPolicy;
import com.uxplima.uxmskyblock.core.domain.activity.ActivityEventType;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandPermission;
import org.jspecify.annotations.Nullable;

/**
 * {@code /is trust} and {@code /is untrust}: a player let onto the island for a while or until taken off, and the time they were given read. Split out of {@link IslandTrustCommands}, whose services and words it uses, so neither file is over the size the
 * standards draw a line at.
 */
final class TrustGrantVerbs {

    private final IslandTrustCommands host;

    TrustGrantVerbs(IslandTrustCommands host) {
        this.host = host;
    }

    int executeTrust(CommandContext<CommandSourceStack> ctx, @Nullable String untilRaw) {
        Player player = host.playerOrNull(ctx);
        if (player == null) {
            return Cmd.OK;
        }
        TemporaryAccessService service = host.accessServiceProvider.get();
        if (service == null) {
            host.send(player, "trust.disabled");
            return Cmd.OK;
        }
        Optional<ProfileId> optProfile = host.activeProfile(player);
        if (optProfile.isEmpty()) {
            host.send(player, "error.session_not_active");
            return Cmd.OK;
        }

        String targetName = StringArgumentType.getString(ctx, "player");
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null || !target.isOnline()) {
            host.send(player, "trust.player_not_found", Placeholder.unparsed("player", targetName));
            return Cmd.OK;
        }
        if (target.getUniqueId().equals(player.getUniqueId())) {
            host.send(player, "trust.not_yourself");
            return Cmd.OK;
        }
        Optional<ProfileId> optTargetProfile = host.activeProfile(target);
        if (optTargetProfile.isEmpty()) {
            host.send(player, "trust.target_not_ready", Placeholder.unparsed("player", target.getName()));
            return Cmd.OK;
        }

        TemporaryAccessConfiguration configuration = host.configurationProvider.get();
        IslandTrustCommands.Until until;
        try {
            until = readUntil(untilRaw, configuration, target);
        } catch (IllegalArgumentException e) {
            host.send(player, "trust.bad_duration", Placeholder.unparsed("given", untilRaw == null ? "" : untilRaw));
            return Cmd.OK;
        }

        ProfileId granterProfile = optProfile.get();
        ProfileId granteeProfile = optTargetProfile.get();
        // Both names are read here, on the thread that owns these players. Folia owns a player per
        // region thread, so reading one from the async pool throws there and races on Paper.
        String granterName = player.getName();
        String granteeName = target.getName();
        PlayerUuid granterUuid = new PlayerUuid(player.getUniqueId());
        PlayerUuid granteeUuid = new PlayerUuid(target.getUniqueId());

        host.schedulerPort.async(() -> {
            Optional<Island> optIsland = host.islandLocationService
                    .findIslandId(granterProfile)
                    .flatMap(host.islandLocationService::findIsland);
            if (optIsland.isEmpty()) {
                host.schedulerPort.onEntity(granterUuid, () -> host.send(player, "error.no_island"));
                return;
            }
            Island island = optIsland.get();
            if (!IslandTrustCommands.roleOf(island, granterProfile).hasPermission(IslandPermission.MEMBER_INVITE)) {
                host.schedulerPort.onEntity(granterUuid, () -> host.send(player, "trust.no_permission"));
                return;
            }

            try {
                service.issueGrant(
                        island.id().value().toString(),
                        IslandTrustCommands.ISLAND_ROOT_TYPE,
                        island.id().value().toString(),
                        granteeProfile,
                        granteeUuid,
                        host.profileTypes.apply(granteeProfile),
                        granterProfile,
                        until.policy(),
                        until.anchorPlayerUuid(),
                        until.anchorSessionEpoch(),
                        until.anchorNodeId(),
                        until.anchorProcessGenerationId(),
                        configuration.trustPermissions(),
                        until.expiresAt());
            } catch (RuntimeException e) {
                host.schedulerPort.onEntity(granterUuid, () -> host.send(player, "trust.refused"));
                return;
            }

            host.activityLog.recordForMembers(
                    island.id(),
                    granterProfile,
                    ActivityEventType.TRUST_GRANTED,
                    "activity.trust_granted",
                    java.util.Map.of("player", granteeName, "actor", granterName));

            String describedUntil = host.describe(until);
            host.schedulerPort.onEntity(
                    granterUuid,
                    () -> host.send(
                            player,
                            "trust.granted",
                            Placeholder.unparsed("player", target.getName()),
                            Placeholder.unparsed("until", describedUntil)));
            host.schedulerPort.onEntity(granteeUuid, () -> {
                if (target.isOnline()) {
                    host.send(
                            target,
                            "trust.received",
                            Placeholder.unparsed("player", granterName),
                            Placeholder.unparsed("until", describedUntil));
                }
            });
        });
        return Cmd.OK;
    }

    int executeUntrust(CommandContext<CommandSourceStack> ctx) {
        Player player = host.playerOrNull(ctx);
        if (player == null) {
            return Cmd.OK;
        }
        TemporaryAccessService service = host.accessServiceProvider.get();
        if (service == null) {
            host.send(player, "trust.disabled");
            return Cmd.OK;
        }
        Optional<ProfileId> optProfile = host.activeProfile(player);
        if (optProfile.isEmpty()) {
            host.send(player, "error.session_not_active");
            return Cmd.OK;
        }
        ProfileId revokerProfile = optProfile.get();
        PlayerUuid revokerUuid = new PlayerUuid(player.getUniqueId());
        String who = StringArgumentType.getString(ctx, "who");
        // Who is here, and what they are called, is read on the thread that owns them. Folia owns a
        // player per region thread, so asking one from the async pool throws there.
        String revokerName = player.getName();
        Map<ProfileId, Player> onlineByProfile = host.onlineByProfile();

        host.schedulerPort.async(() -> {
            Optional<Island> optIsland = host.islandLocationService
                    .findIslandId(revokerProfile)
                    .flatMap(host.islandLocationService::findIsland);
            if (optIsland.isEmpty()) {
                host.schedulerPort.onEntity(revokerUuid, () -> host.send(player, "error.no_island"));
                return;
            }
            Island island = optIsland.get();
            if (!IslandTrustCommands.roleOf(island, revokerProfile).hasPermission(IslandPermission.MEMBER_INVITE)) {
                host.schedulerPort.onEntity(revokerUuid, () -> host.send(player, "trust.no_permission"));
                return;
            }

            List<TemporaryAccessGrant> held = service.getActiveGrantsForRoot(
                    IslandTrustCommands.ISLAND_ROOT_TYPE, island.id().value().toString());
            List<TemporaryAccessGrant> matching = host.matching(held, who, onlineByProfile);
            if (matching.isEmpty()) {
                host.schedulerPort.onEntity(
                        revokerUuid,
                        () -> host.send(player, "trust.nothing_to_revoke", Placeholder.unparsed("who", who)));
                return;
            }
            for (TemporaryAccessGrant grant : matching) {
                service.revokeGrant(grant.grantId(), revokerProfile);
            }
            host.activityLog.recordForMembers(
                    island.id(),
                    revokerProfile,
                    ActivityEventType.TRUST_REVOKED,
                    "activity.trust_revoked",
                    java.util.Map.of("player", who, "actor", revokerName));

            int revoked = matching.size();
            host.schedulerPort.onEntity(
                    revokerUuid,
                    () -> host.send(
                            player,
                            "trust.revoked",
                            Placeholder.unparsed("who", who),
                            Placeholder.unparsed("count", Integer.toString(revoked))));
            java.util.Set<ProfileId> told = new java.util.LinkedHashSet<>();
            for (TemporaryAccessGrant grant : matching) {
                if (!told.add(grant.granteeProfileId())) {
                    continue;
                }
                Player grantee = onlineByProfile.get(grant.granteeProfileId());
                if (grantee != null) {
                    host.schedulerPort.onEntity(
                            new PlayerUuid(grantee.getUniqueId()),
                            () -> host.send(
                                    grantee, "trust.revoked_notice", Placeholder.unparsed("player", revokerName)));
                    continue;
                }
                // Losing your standing on an island while you are away is exactly what the inbox is
                // for. One notice per player, not one per grant they happened to hold.
                host.leaveNotice(grant.granteeProfileId(), revokerName);
            }
        });
        return Cmd.OK;
    }
    /**
     * What the operator's words mean in the domain's terms.
     *
     * <p>All four termination policies the documents name are reachable, because a feature is not
     * half a feature: a word for the session, a word for the restart, a word for until revoked, and
     * anything else read as a length of time.
     */
    IslandTrustCommands.Until readUntil(
            @Nullable String raw, TemporaryAccessConfiguration configuration, Player target) {
        if (raw == null || raw.isBlank()) {
            return host.timed(configuration.defaultDuration(), configuration);
        }
        String word = raw.strip().toLowerCase(Locale.ROOT);
        return switch (word) {
            case "session", "logout" -> {
                ActiveSession session = host.sessionCoordinator == null
                        ? null
                        : host.sessionCoordinator.getActiveSession(target.getUniqueId());
                if (session == null) {
                    throw new IllegalArgumentException("no live session to anchor to");
                }
                yield new IslandTrustCommands.Until(
                        TerminationPolicy.UNTIL_SESSION_END,
                        new PlayerUuid(target.getUniqueId()),
                        session.sessionEpoch(),
                        null,
                        null,
                        null);
            }
            case "restart" -> {
                CurrentNodeProcessIdentity identity = host.nodeIdentitySupplier.get();
                yield new IslandTrustCommands.Until(
                        TerminationPolicy.NODE_PROCESS_RESTART,
                        null,
                        null,
                        identity.nodeId(),
                        identity.processGenerationId(),
                        null);
            }
            case "forever", "revoked" ->
                new IslandTrustCommands.Until(TerminationPolicy.UNTIL_REVOKED, null, null, null, null, null);
            default -> host.timed(Durations.parse(word), configuration);
        };
    }
}
