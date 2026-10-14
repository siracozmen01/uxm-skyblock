package com.uxplima.uxmskyblock.bukkit.command;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.uxplima.uxmlib.command.Cmd;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.core.application.activity.ActivityFeedService;
import com.uxplima.uxmskyblock.core.application.antiabuse.IslandAntiAbuseService;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.membership.IslandMembershipService;
import com.uxplima.uxmskyblock.core.application.notification.NotificationService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.IslandMember;
import com.uxplima.uxmskyblock.core.domain.notification.NotificationCategory;
import org.jspecify.annotations.Nullable;

/**
 * {@code /is invite}, {@code /is accept}, {@code /is deny}, {@code /is kick}, {@code /is leave},
 * {@code /is members} and {@code /is role}: who belongs to the island.
 *
 * <p>This is a skyblock and a team is the point of one, and there was no way to make one. The
 * domain has carried addMember and removeMember since it was written, the roles carry
 * MEMBER_INVITE, MEMBER_KICK, MEMBER_PROMOTE and MEMBER_DEMOTE, the MEMBERS upgrade raises a cap,
 * the members menu has an invite button, and none of it reached a command: every island on every
 * server was a solo island.
 */
public final class IslandMembershipCommands {

    /** {@code /is kick} and {@code /is leave}: a member taken off an island, or leaving it, and what each of them is told. */
    private final RemovalVerbs removals = new RemovalVerbs(this);

    /** {@code /is invite}, {@code /is accept} and {@code /is deny}: asking a player onto an island, and their answer under the co-op hopping lock. */
    final InviteVerbs invites = new InviteVerbs(this);

    /** {@code /is role} and {@code /is permissions}: the roles of an island, what each may do, and the window that shapes them. */
    private final RoleVerbs roles = new RoleVerbs(this);

    /** Where a command named after a window opens it. */
    private CommandWindows windows = CommandWindows.none();

    void useWindows(CommandWindows windows) {
        this.windows = java.util.Objects.requireNonNull(windows, "windows must not be null");
    }

    /**
     * What the operator wrote for the milestones this command group reaches.
     *
     * <p>The reply a command gives is the catalogue's. What a server wants beside it, a sound, a
     * title, a bar, is the operator's list and nothing here decides it. A node with no list fires
     * nothing, which is a server that wrote none.
     */
    private volatile com.uxplima.uxmskyblock.bukkit.effect.@Nullable InteractionEffects effects;

    private volatile com.uxplima.uxmskyblock.bukkit.effect.@Nullable InteractionEffectPlayer effectPlayer;

    /** Tells this command group what the operator wrote for its milestones. */
    public void useEffects(
            com.uxplima.uxmskyblock.bukkit.effect.@Nullable InteractionEffects effects,
            com.uxplima.uxmskyblock.bukkit.effect.@Nullable InteractionEffectPlayer player) {
        this.effects = effects;
        this.effectPlayer = player;
    }

    /** Fires one milestone for one player, when the operator wrote anything for it. */
    void fireMilestone(String interaction, org.bukkit.entity.Player player) {
        com.uxplima.uxmskyblock.bukkit.effect.InteractionEffects written = this.effects;
        com.uxplima.uxmskyblock.bukkit.effect.InteractionEffectPlayer plays = this.effectPlayer;
        if (written != null && plays != null) {
            plays.fire(written, interaction, player);
        }
    }

    private static final java.util.logging.Logger LOGGER =
            java.util.logging.Logger.getLogger(IslandMembershipCommands.class.getName());

    private final Supplier<@Nullable IslandMembershipService> membershipServiceProvider;
    final IslandLocationService islandLocationService;
    final SchedulerPort schedulerPort;
    final Messages messages;
    private final @Nullable PlayerSessionCoordinator sessionCoordinator;

    /**
     * The lock that stops a player leaving one island and being recruited into the next.
     *
     * <p>The service has had the check and the record since the anti abuse work and nothing called
     * either, so {@code coop-join-cooldown} and the permission that bypasses it were two settings
     * an operator could write and watch do nothing.
     */
    volatile @Nullable IslandAntiAbuseService coopHoppingLock;

    volatile com.uxplima.uxmskyblock.bukkit.config.@Nullable AntiAbuseConfiguration antiAbuseRules;

    /**
     * The inbox a player reads on their next join.
     *
     * <p>Losing your place on an island is the kind of thing you find out about by walking into a
     * wall a week later. It is told to you now, in your own language, whether or not you were here
     * when it happened.
     */
    private volatile @Nullable NotificationService notificationService;

    /** Where what happens to this island's membership is written down for its members to read. */
    final IslandActivityLog activityLog = new IslandActivityLog();

    public IslandMembershipCommands(
            Supplier<@Nullable IslandMembershipService> membershipServiceProvider,
            IslandLocationService islandLocationService,
            SchedulerPort schedulerPort,
            Messages messages,
            @Nullable PlayerSessionCoordinator sessionCoordinator) {
        this.membershipServiceProvider =
                Objects.requireNonNull(membershipServiceProvider, "membershipServiceProvider must not be null");
        this.islandLocationService =
                Objects.requireNonNull(islandLocationService, "islandLocationService must not be null");
        this.schedulerPort = Objects.requireNonNull(schedulerPort, "schedulerPort must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
        this.sessionCoordinator = sessionCoordinator;
    }

    /** Tells this command group where to leave a notice for a player who is not here. */
    Supplier<com.uxplima.uxmskyblock.bukkit.lifecycle.@Nullable PlayerLifecycle> lifecycle = () -> null;

    /** What carries out the operator's lifecycle rules, looked up when a leave or a kick happens. */
    public void useLifecycle(Supplier<com.uxplima.uxmskyblock.bukkit.lifecycle.@Nullable PlayerLifecycle> lifecycle) {
        this.lifecycle = java.util.Objects.requireNonNull(lifecycle, "lifecycle must not be null");
    }

    public void useNotifications(@Nullable NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    /** Tells this command group which lock holds a player who has just left an island. */
    public void useCoopHoppingLock(
            @Nullable IslandAntiAbuseService lock,
            com.uxplima.uxmskyblock.bukkit.config.@Nullable AntiAbuseConfiguration rules) {
        this.coopHoppingLock = lock;
        this.antiAbuseRules = rules;
    }

    /** What makes and takes away an owner's own roles, and how many one may make. */
    Supplier<com.uxplima.uxmskyblock.core.application.membership.@Nullable IslandRoleShaper> roleShaper = () -> null;

    Supplier<com.uxplima.uxmskyblock.bukkit.config.RoleConfiguration> roleConfiguration =
            com.uxplima.uxmskyblock.bukkit.config.RoleConfiguration::defaults;

    /** Hands this group what makes an owner's own roles, and the allowance the operator wrote. */
    public void useRoleShaper(
            Supplier<com.uxplima.uxmskyblock.core.application.membership.@Nullable IslandRoleShaper> shaper,
            Supplier<com.uxplima.uxmskyblock.bukkit.config.RoleConfiguration> configuration) {
        this.roleShaper = java.util.Objects.requireNonNull(shaper, "shaper must not be null");
        this.roleConfiguration = java.util.Objects.requireNonNull(configuration, "configuration must not be null");
    }

    /** Tells this command group where to write the island's activity feed. */
    public void useActivityFeed(@Nullable ActivityFeedService service) {
        this.activityLog.useService(service);
    }

    /**
     * Tells {@code recipient} now when they are on this server, and leaves a notice for their next
     * login when they are not.
     *
     * <p>Every change to a member was left as a notice only, and a notice is read out when a session
     * is made, so a player standing on the island when they were invited, promoted or kicked heard
     * nothing until they logged in again. An invite has no notice at all, because it runs out long
     * before a later login.
     */
    void tellOrLeave(
            ProfileId recipient,
            String liveKey,
            NotificationCategory category,
            @Nullable String noticeKey,
            Map<String, String> values) {
        Optional<Player> online = onlinePlayerOf(recipient);
        if (online.isPresent()) {
            Player reader = online.get();
            schedulerPort.onEntity(new PlayerUuid(reader.getUniqueId()), () -> {
                if (!reader.isOnline()) {
                    return;
                }
                java.util.List<TagResolver> resolvers = new java.util.ArrayList<>(values.size());
                for (Map.Entry<String, String> value : values.entrySet()) {
                    resolvers.add(Placeholder.unparsed(value.getKey(), messages.words(reader, value.getValue())));
                }
                messages.send(reader, liveKey, resolvers.toArray(new TagResolver[0]));
            });
            return;
        }
        if (noticeKey != null) {
            leaveNotice(recipient, category, noticeKey, values);
        }
    }

    /** The player on this server whose active profile is {@code profile}, if there is one. */
    private Optional<Player> onlinePlayerOf(ProfileId profile) {
        PlayerSessionCoordinator sessions = this.sessionCoordinator;
        if (sessions == null) {
            return Optional.empty();
        }
        for (Player online : org.bukkit.Bukkit.getOnlinePlayers()) {
            if (sessions.activeProfile(online.getUniqueId())
                    .filter(profile::equals)
                    .isPresent()) {
                return Optional.of(online);
            }
        }
        return Optional.empty();
    }

    /**
     * Leaves a notice for a member who may not be here to read it.
     *
     * <p>What is stored is the name of a message and the values it has holes for, never a sentence.
     */
    private void leaveNotice(
            ProfileId recipient, NotificationCategory category, String messageKey, Map<String, String> values) {
        NotificationService service = this.notificationService;
        if (service == null) {
            return;
        }
        try {
            service.notify(recipient, category, messageKey, values, null);
        } catch (RuntimeException e) {
            // The island change itself already happened and stands. A notice that could not be
            // written is worth a line in the log and nothing more.
            LOGGER.log(
                    java.util.logging.Level.WARNING,
                    e,
                    () -> "Leaving a " + category + " notice for " + recipient + " failed.");
        }
    }

    public LiteralArgumentBuilder<CommandSourceStack> buildInvite() {
        return Cmd.literal("invite")
                .then(Cmd.argument("player", StringArgumentType.word()).executes(invites::executeInvite));
    }

    public LiteralArgumentBuilder<CommandSourceStack> buildAccept() {
        return Cmd.literal("accept").executes(invites::executeAccept);
    }

    public LiteralArgumentBuilder<CommandSourceStack> buildDeny() {
        return Cmd.literal("deny").executes(invites::executeDeny);
    }

    public LiteralArgumentBuilder<CommandSourceStack> buildKick() {
        return Cmd.literal("kick")
                .then(Cmd.argument("player", StringArgumentType.word()).executes(removals::executeKick));
    }

    public LiteralArgumentBuilder<CommandSourceStack> buildLeave() {
        return Cmd.literal("leave").executes(removals::executeLeave);
    }

    public LiteralArgumentBuilder<CommandSourceStack> buildMembers() {
        return Cmd.literal("members")
                .executes(ctx -> windows.openOr(ctx, "island-members", this::executeMembers))
                .then(Cmd.literal("list").executes(this::executeMembers));
    }

    /** {@code /is role <player> <role>}: what somebody on the island may do. */
    public LiteralArgumentBuilder<CommandSourceStack> buildRole() {
        return Cmd.literal("role")
                .then(Cmd.argument("player", StringArgumentType.word())
                        .executes(roles::executeRoleChoice)
                        .then(Cmd.argument("role", StringArgumentType.word()).executes(roles::executeRole)));
    }

    /**
     * {@code /is permissions} and {@code /is permissions <role> <permission> <on|off>}.
     *
     * <p>The design document publishes this for overriding a permission per role, the table that
     * holds it has been written on every save since the island writer was written, and nothing could
     * move one: the roles an island was created with were the roles it died with.
     */
    public LiteralArgumentBuilder<CommandSourceStack> buildPermissions() {
        return Cmd.literal("permissions")
                .executes(ctx -> ctx.getSource().getSender() instanceof Player player && windows.openRoles(player)
                        ? Cmd.OK
                        : roles.executePermissionList(ctx))
                .then(Cmd.literal("list").executes(roles::executePermissionList))
                .then(Cmd.literal("create")
                        .then(Cmd.argument("name", StringArgumentType.word()).executes(roles::executeCreateRole)))
                .then(Cmd.literal("delete")
                        .then(Cmd.argument("name", StringArgumentType.word()).executes(this::executeDeleteRole)))
                .then(Cmd.argument("role", StringArgumentType.word())
                        .then(Cmd.argument("permission", StringArgumentType.word())
                                .then(Cmd.argument("state", StringArgumentType.word())
                                        .executes(roles::executeSetPermission))));
    }

    /** {@code /is permissions delete <role>}: takes a role of the owner's own away. */
    private int executeDeleteRole(CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "name");
        return roles.withShaper(
                ctx,
                (player, shaper, actor) -> schedulerPort.async(() -> roles.answer(player, shaper.delete(actor, name))));
    }

    /** Three things at once, as a request to shape the roles needs them. */
    @FunctionalInterface
    interface TriConsumer<A, B, C> {
        void accept(A first, B second, C third);
    }

    /** The permissions a role carries, by the names the reader's language gives them, or a word saying none. */
    String permissionsOf(Player reader, com.uxplima.uxmskyblock.core.domain.island.IslandRole role) {
        return role.permissions().stream()
                .map(permission -> messages.named(reader, "permissions", permission.name(), permission.name()))
                .reduce((left, right) -> left + ", " + right)
                .orElse("-");
    }

    private int executeMembers(CommandContext<CommandSourceStack> ctx) {
        return withService(
                ctx,
                (player, service, actor) -> schedulerPort.async(() -> {
                    Optional<IslandId> optIsland = islandLocationService.findIslandId(actor);
                    if (optIsland.isEmpty()) {
                        send(player, "error.no_island");
                        return;
                    }
                    List<IslandMember> members = service.members(optIsland.get());
                    send(player, "member.header", Placeholder.unparsed("count", Integer.toString(members.size())));
                    for (IslandMember member : members) {
                        send(
                                player,
                                "member.entry",
                                Placeholder.unparsed("player", nameOf(member.playerUuid())),
                                Placeholder.unparsed(
                                        "role",
                                        messages.named(
                                                player,
                                                "roles",
                                                member.role().id(),
                                                member.role().displayName())));
                    }
                }));
    }

    /** What a membership command needs: a player, the service, and their own profile. */
    @FunctionalInterface
    interface MembershipAction {
        void run(Player player, IslandMembershipService service, ProfileId actor);
    }

    int withService(CommandContext<CommandSourceStack> ctx, MembershipAction action) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            send(ctx.getSource().getSender(), "error.players_only");
            return Cmd.OK;
        }
        IslandMembershipService service = membershipServiceProvider.get();
        if (service == null) {
            send(player, "member.disabled");
            return Cmd.OK;
        }
        Optional<ProfileId> optProfile = activeProfile(player);
        if (optProfile.isEmpty()) {
            send(player, "error.session_not_active");
            return Cmd.OK;
        }
        action.run(player, service, optProfile.get());
        return Cmd.OK;
    }

    /** The player behind a name, for the rules that are about a player rather than a profile. */
    Optional<PlayerUuid> resolveUuid(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return Optional.of(new PlayerUuid(online.getUniqueId()));
        }
        @SuppressWarnings("deprecation")
        OfflinePlayer offline = Bukkit.getOfflinePlayer(name);
        if (offline.hasPlayedBefore() || offline.isOnline()) {
            return Optional.of(new PlayerUuid(offline.getUniqueId()));
        }
        return Optional.empty();
    }

    /**
     * The profile of the player the caller named, online or not.
     *
     * <p>An invite that only works while the target is standing there is barely an invite, and a
     * kick that only works while they are online is a member nobody can remove.
     */
    Optional<ProfileId> resolveProfile(String name) {
        if (sessionCoordinator == null) {
            return Optional.empty();
        }
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return sessionCoordinator.activeProfile(online.getUniqueId());
        }
        @SuppressWarnings("deprecation")
        OfflinePlayer offline = Bukkit.getOfflinePlayer(name);
        if (offline.hasPlayedBefore() || offline.isOnline()) {
            return sessionCoordinator.findDurableActiveProfile(offline.getUniqueId());
        }
        return Optional.empty();
    }

    /** The name a player reads, which is the offline profile's when the player is away. */
    private static String nameOf(PlayerUuid playerUuid) {
        String name = Bukkit.getOfflinePlayer(playerUuid.value()).getName();
        return name == null ? playerUuid.value().toString() : name;
    }

    private Optional<ProfileId> activeProfile(Player player) {
        if (sessionCoordinator == null) {
            return Optional.empty();
        }
        return sessionCoordinator.activeProfile(player.getUniqueId());
    }

    void send(Audience audience, String key, TagResolver... resolvers) {
        Component line = messages.render(audience, key, resolvers);
        if (audience instanceof Player player) {
            schedulerPort.onEntity(new PlayerUuid(player.getUniqueId()), () -> {
                if (player.isOnline()) {
                    player.sendMessage(line);
                }
            });
        } else {
            audience.sendMessage(line);
        }
    }
}
