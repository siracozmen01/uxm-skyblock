package com.uxplima.uxmskyblock.bukkit.command;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.uxplima.uxmlib.command.Cmd;
import com.uxplima.uxmskyblock.bukkit.config.TemporaryAccessConfiguration;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.core.application.access.TemporaryAccessService;
import com.uxplima.uxmskyblock.core.application.activity.ActivityFeedService;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.notification.NotificationService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.access.CurrentNodeProcessIdentity;
import com.uxplima.uxmskyblock.core.domain.access.TemporaryAccessGrant;
import com.uxplima.uxmskyblock.core.domain.access.TerminationPolicy;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandMember;
import com.uxplima.uxmskyblock.core.domain.island.IslandRole;
import com.uxplima.uxmskyblock.core.domain.notification.NotificationCategory;
import com.uxplima.uxmskyblock.core.domain.profile.ProfileType;
import org.jspecify.annotations.Nullable;

/**
 * {@code /is trust}, {@code /is untrust} and {@code /is trusted}.
 *
 * <p>The grant subsystem has been here since the access work: a service, a normalized pair of
 * tables, four termination policies, a purge on a timer and a check on every block a non member
 * touches. Nothing could make a grant. The whole write side, issueGrant and revokeGrant, had no
 * caller anywhere, so the check on every click asked about grants that could not exist.
 *
 * <p>A trusted player is never a member. The grant carries whatever the operator's
 * {@code trust-permissions} names and the domain refuses management and bank withdrawal on top of
 * that, so trust cannot be a way in to the island's membership or its money.
 */
public final class IslandTrustCommands {

    /** {@code /is trust} and {@code /is untrust}: a player let onto the island for a while or until taken off, and the time they were given read. */
    private final TrustGrantVerbs grants = new TrustGrantVerbs(this);

    private static final java.util.logging.Logger LOGGER =
            java.util.logging.Logger.getLogger(IslandTrustCommands.class.getName());

    /** The root type the protection listener asks about, so a grant has to be filed under it. */
    public static final String ISLAND_ROOT_TYPE = "ISLAND";

    /** How a moment is written, in the server's own zone. */
    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT).withZone(ZoneId.systemDefault());

    final Supplier<@Nullable TemporaryAccessService> accessServiceProvider;
    final IslandLocationService islandLocationService;
    final SchedulerPort schedulerPort;
    final Supplier<TemporaryAccessConfiguration> configurationProvider;
    final Supplier<CurrentNodeProcessIdentity> nodeIdentitySupplier;
    final java.util.function.Function<ProfileId, ProfileType> profileTypes;
    private final Messages messages;
    final @Nullable PlayerSessionCoordinator sessionCoordinator;

    /** The inbox a player reads on their next join, for a revocation they were not here to see. */
    private volatile @Nullable NotificationService notificationService;

    /** Where trust given and taken back is written down for the island's members to read. */
    final IslandActivityLog activityLog = new IslandActivityLog();

    public IslandTrustCommands(
            Supplier<@Nullable TemporaryAccessService> accessServiceProvider,
            IslandLocationService islandLocationService,
            SchedulerPort schedulerPort,
            Supplier<TemporaryAccessConfiguration> configurationProvider,
            Supplier<CurrentNodeProcessIdentity> nodeIdentitySupplier,
            java.util.function.Function<ProfileId, ProfileType> profileTypes,
            Messages messages,
            @Nullable PlayerSessionCoordinator sessionCoordinator) {
        this.accessServiceProvider =
                Objects.requireNonNull(accessServiceProvider, "accessServiceProvider must not be null");
        this.islandLocationService =
                Objects.requireNonNull(islandLocationService, "islandLocationService must not be null");
        this.schedulerPort = Objects.requireNonNull(schedulerPort, "schedulerPort must not be null");
        this.configurationProvider =
                Objects.requireNonNull(configurationProvider, "configurationProvider must not be null");
        this.nodeIdentitySupplier =
                Objects.requireNonNull(nodeIdentitySupplier, "nodeIdentitySupplier must not be null");
        this.profileTypes = Objects.requireNonNull(profileTypes, "profileTypes must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
        this.sessionCoordinator = sessionCoordinator;
    }

    /** Tells this command group where to leave a notice for a player who is not here. */
    public void useNotifications(@Nullable NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    /** Tells this command group where to write the island's activity feed. */
    public void useActivityFeed(@Nullable ActivityFeedService service) {
        this.activityLog.useService(service);
    }

    /** {@code /is trust <player> [until]}. */
    public LiteralArgumentBuilder<CommandSourceStack> buildTrust() {
        return Cmd.literal("trust")
                .then(Cmd.argument("player", StringArgumentType.word())
                        .suggests((ctx, builder) -> {
                            for (Player online : Bukkit.getOnlinePlayers()) {
                                builder.suggest(online.getName());
                            }
                            return builder.buildFuture();
                        })
                        .executes(ctx -> grants.executeTrust(ctx, null))
                        .then(Cmd.argument("until", StringArgumentType.word())
                                .suggests((ctx, builder) -> {
                                    builder.suggest("session");
                                    builder.suggest("restart");
                                    builder.suggest("forever");
                                    builder.suggest("30m");
                                    builder.suggest("2h");
                                    return builder.buildFuture();
                                })
                                .executes(
                                        ctx -> grants.executeTrust(ctx, StringArgumentType.getString(ctx, "until")))));
    }

    /** {@code /is untrust <player or grant id>}. */
    public LiteralArgumentBuilder<CommandSourceStack> buildUntrust() {
        return Cmd.literal("untrust")
                .then(Cmd.argument("who", StringArgumentType.word())
                        .suggests((ctx, builder) -> {
                            for (Player online : Bukkit.getOnlinePlayers()) {
                                builder.suggest(online.getName());
                            }
                            return builder.buildFuture();
                        })
                        .executes(grants::executeUntrust));
    }

    /** {@code /is trusted}: who holds a grant on this island right now. */
    public LiteralArgumentBuilder<CommandSourceStack> buildTrusted() {
        return Cmd.literal("trusted").executes(this::executeTrusted);
    }

    private int executeTrusted(CommandContext<CommandSourceStack> ctx) {
        Player player = playerOrNull(ctx);
        if (player == null) {
            return Cmd.OK;
        }
        TemporaryAccessService service = accessServiceProvider.get();
        if (service == null) {
            send(player, "trust.disabled");
            return Cmd.OK;
        }
        Optional<ProfileId> optProfile = activeProfile(player);
        if (optProfile.isEmpty()) {
            send(player, "error.session_not_active");
            return Cmd.OK;
        }
        ProfileId profileId = optProfile.get();
        PlayerUuid playerUuid = new PlayerUuid(player.getUniqueId());

        schedulerPort.async(() -> {
            Optional<Island> optIsland =
                    islandLocationService.findIslandId(profileId).flatMap(islandLocationService::findIsland);
            if (optIsland.isEmpty()) {
                schedulerPort.onEntity(playerUuid, () -> send(player, "error.no_island"));
                return;
            }
            List<TemporaryAccessGrant> held = service.getActiveGrantsForRoot(
                    ISLAND_ROOT_TYPE, optIsland.get().id().value().toString());
            schedulerPort.onEntity(playerUuid, () -> {
                if (held.isEmpty()) {
                    send(player, "trust.none");
                    return;
                }
                send(player, "trust.header", Placeholder.unparsed("count", Integer.toString(held.size())));
                for (TemporaryAccessGrant grant : held) {
                    send(
                            player,
                            "trust.entry",
                            Placeholder.unparsed("who", nameOf(grant.granteeProfileId())),
                            Placeholder.unparsed("until", describe(grant)),
                            Placeholder.unparsed("id", grant.grantId().value().toString()));
                }
            });
        });
        return Cmd.OK;
    }

    /** Writes a revocation down for a player who is not here to be told. */
    void leaveNotice(ProfileId recipient, String revokerName) {
        NotificationService service = this.notificationService;
        if (service == null) {
            return;
        }
        try {
            service.notify(
                    recipient,
                    NotificationCategory.TRUST_REVOKED,
                    "notification.trust_revoked",
                    java.util.Map.of("player", revokerName),
                    null);
        } catch (RuntimeException e) {
            // The revocation itself happened and stands.
            LOGGER.log(
                    java.util.logging.Level.WARNING, e, () -> "Leaving a trust notice for " + recipient + " failed.");
        }
    }

    /** Grants held by the named player, or the one grant whose id was typed. */
    List<TemporaryAccessGrant> matching(
            List<TemporaryAccessGrant> held, String who, Map<ProfileId, Player> onlineByProfile) {
        List<TemporaryAccessGrant> matches = new ArrayList<>();
        for (TemporaryAccessGrant grant : held) {
            if (grant.grantId().value().toString().equalsIgnoreCase(who)) {
                return List.of(grant);
            }
            Player online = onlineByProfile.get(grant.granteeProfileId());
            if (online != null && who.equalsIgnoreCase(online.getName())) {
                matches.add(grant);
            }
        }
        return matches;
    }

    /**
     * Who is here now, by the profile they are playing.
     *
     * <p>Read once, on the thread the command arrives on, because everything that needs it happens
     * after a hop off that thread and a player belongs to whichever thread owns them.
     */
    Map<ProfileId, Player> onlineByProfile() {
        if (sessionCoordinator == null) {
            return Map.of();
        }
        Map<ProfileId, Player> byProfile = new java.util.LinkedHashMap<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            sessionCoordinator.activeProfile(online.getUniqueId()).ifPresent(profile -> byProfile.put(profile, online));
        }
        return byProfile;
    }

    /** A length of time, never longer than the operator allows. */
    Until timed(Duration wanted, TemporaryAccessConfiguration configuration) {
        if (wanted.isNegative() || wanted.isZero()) {
            throw new IllegalArgumentException("a grant must last longer than nothing: " + wanted);
        }
        Duration capped = wanted.compareTo(configuration.maxDuration()) > 0 ? configuration.maxDuration() : wanted;
        return new Until(
                TerminationPolicy.UNTIL_TIMESTAMP,
                null,
                null,
                null,
                null,
                Instant.now().plus(capped));
    }

    String describe(Until until) {
        Instant expiresAt = until.expiresAt();
        return expiresAt != null ? WHEN.format(expiresAt) : word(until.policy());
    }

    String describe(TemporaryAccessGrant grant) {
        Instant expiresAt = grant.expiresAt();
        return expiresAt != null ? WHEN.format(expiresAt) : word(grant.terminationPolicy());
    }

    static String word(TerminationPolicy policy) {
        return switch (policy) {
            case UNTIL_SESSION_END -> "session";
            case NODE_PROCESS_RESTART -> "restart";
            case UNTIL_REVOKED -> "forever";
            case UNTIL_TIMESTAMP -> "";
        };
    }

    static IslandRole roleOf(Island island, ProfileId profileId) {
        if (island.ownerProfileId().equals(profileId)) {
            return IslandRole.OWNER;
        }
        IslandMember member = island.members().get(profileId);
        return member != null ? member.role() : IslandRole.VISITOR;
    }

    private @Nullable Player onlinePlayerFor(ProfileId profileId) {
        if (sessionCoordinator == null) {
            return null;
        }
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (sessionCoordinator
                    .activeProfile(online.getUniqueId())
                    .filter(profileId::equals)
                    .isPresent()) {
                return online;
            }
        }
        return null;
    }

    /** The name behind a profile when its player is here, and the profile itself when they are not. */
    private String nameOf(ProfileId profileId) {
        Player online = onlinePlayerFor(profileId);
        return online != null ? online.getName() : profileId.value().toString();
    }

    @Nullable Player playerOrNull(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        if (sender instanceof Player player) {
            return player;
        }
        send(sender, "error.players_only");
        return null;
    }

    Optional<ProfileId> activeProfile(Player player) {
        if (sessionCoordinator == null) {
            return Optional.empty();
        }
        return sessionCoordinator.activeProfile(player.getUniqueId());
    }

    void send(CommandSender sender, String key, TagResolver... resolvers) {
        messages.send(sender, key, resolvers);
    }

    /** One answer to "how long", in the shape issueGrant wants it. */
    record Until(
            TerminationPolicy policy,
            @Nullable PlayerUuid anchorPlayerUuid,
            @Nullable Long anchorSessionEpoch,
            @Nullable String anchorNodeId,
            @Nullable String anchorProcessGenerationId,
            @Nullable Instant expiresAt) {}
}
