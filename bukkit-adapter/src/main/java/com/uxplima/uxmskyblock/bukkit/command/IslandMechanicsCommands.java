package com.uxplima.uxmskyblock.bukkit.command;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import org.bukkit.entity.Player;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.uxplima.uxmlib.command.Cmd;
import com.uxplima.uxmskyblock.bukkit.i18n.DurationText;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.menu.IslandBoosterMenu;
import com.uxplima.uxmskyblock.bukkit.menu.IslandMissionsMenu;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.core.application.activity.ActivityFeedService;
import com.uxplima.uxmskyblock.core.application.antiabuse.IslandAntiAbuseService;
import com.uxplima.uxmskyblock.core.application.booster.IslandBoosterService;
import com.uxplima.uxmskyblock.core.application.boundary.IslandBoundaryService;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.limit.IslandLimitService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.booster.BoosterApplyResult;
import com.uxplima.uxmskyblock.core.domain.booster.BoosterCategory;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.limit.LimitCategory;
import com.uxplima.uxmskyblock.core.domain.limit.LimitType;
import com.uxplima.uxmskyblock.core.domain.mission.MissionRepeat;
import org.jspecify.annotations.Nullable;

/**
 * Handles island mechanics commands:
 * /is limits, /is quarantine, /is booster, /is missions, /is challenges, /is border, /is bounds
 */
public final class IslandMechanicsCommands {

    /** {@code /isadmin booster}: a booster applied to an island, and what the operator is told it did. */
    private final BoosterVerbs boosters = new BoosterVerbs(this);

    final IslandLocationService islandLocationService;
    private final PlayerSessionCoordinator sessionCoordinator;
    final SchedulerPort schedulerPort;
    private final Supplier<@Nullable IslandLimitService> limitServiceProvider;
    private final Supplier<@Nullable IslandAntiAbuseService> antiAbuseServiceProvider;
    final Supplier<@Nullable IslandBoosterService> boosterServiceProvider;
    private final Supplier<@Nullable IslandBoosterMenu> boosterMenuProvider;
    private final Supplier<@Nullable IslandMissionsMenu> missionsMenuProvider;
    private final Supplier<@Nullable IslandBoundaryService> boundaryServiceProvider;
    final Messages messages;

    /** Where a booster somebody started is written down for the island's members to read. */
    final IslandActivityLog activityLog = new IslandActivityLog();

    /** Tells this command group where to write the island's activity feed. */
    public void useActivityFeed(@Nullable ActivityFeedService service) {
        this.activityLog.useService(service);
    }

    public IslandMechanicsCommands(
            IslandLocationService islandLocationService,
            PlayerSessionCoordinator sessionCoordinator,
            SchedulerPort schedulerPort,
            Supplier<@Nullable IslandLimitService> limitServiceProvider,
            Supplier<@Nullable IslandAntiAbuseService> antiAbuseServiceProvider,
            Supplier<@Nullable IslandBoosterService> boosterServiceProvider,
            Supplier<@Nullable IslandBoosterMenu> boosterMenuProvider,
            Supplier<@Nullable IslandMissionsMenu> missionsMenuProvider,
            Supplier<@Nullable IslandBoundaryService> boundaryServiceProvider,
            Messages messages) {
        this.islandLocationService =
                Objects.requireNonNull(islandLocationService, "islandLocationService must not be null");
        this.sessionCoordinator = Objects.requireNonNull(sessionCoordinator, "sessionCoordinator must not be null");
        this.schedulerPort = Objects.requireNonNull(schedulerPort, "schedulerPort must not be null");
        this.limitServiceProvider =
                Objects.requireNonNull(limitServiceProvider, "limitServiceProvider must not be null");
        this.antiAbuseServiceProvider =
                Objects.requireNonNull(antiAbuseServiceProvider, "antiAbuseServiceProvider must not be null");
        this.boosterServiceProvider =
                Objects.requireNonNull(boosterServiceProvider, "boosterServiceProvider must not be null");
        this.boosterMenuProvider = Objects.requireNonNull(boosterMenuProvider, "boosterMenuProvider must not be null");
        this.missionsMenuProvider =
                Objects.requireNonNull(missionsMenuProvider, "missionsMenuProvider must not be null");
        this.boundaryServiceProvider =
                Objects.requireNonNull(boundaryServiceProvider, "boundaryServiceProvider must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
    }

    public LiteralArgumentBuilder<CommandSourceStack> buildLimits() {
        return Cmd.literal("limits").executes(this::executeLimits);
    }

    public LiteralArgumentBuilder<CommandSourceStack> buildQuarantine() {
        return Cmd.literal("quarantine").executes(this::executeQuarantine);
    }

    public LiteralArgumentBuilder<CommandSourceStack> buildBooster() {
        return Cmd.literal("booster")
                .executes(this::executeBooster)
                .then(Cmd.literal("apply")
                        .then(Cmd.argument("category", StringArgumentType.word())
                                .then(Cmd.argument("multiplier", DoubleArgumentType.doubleArg(1.0))
                                        .then(Cmd.argument("duration", StringArgumentType.word())
                                                .executes(boosters::executeAdminApplyBooster)))));
    }

    public LiteralArgumentBuilder<CommandSourceStack> buildMissions() {
        return Cmd.literal("missions")
                .executes(ctx -> executeMissions(ctx, null))
                .then(Cmd.literal("daily").executes(ctx -> executeMissions(ctx, MissionRepeat.DAILY)))
                .then(Cmd.literal("weekly").executes(ctx -> executeMissions(ctx, MissionRepeat.WEEKLY)))
                .then(Cmd.literal("challenges").executes(ctx -> executeMissions(ctx, MissionRepeat.ONCE)));
    }

    public LiteralArgumentBuilder<CommandSourceStack> buildChallenges() {
        return Cmd.literal("challenges").executes(ctx -> executeMissions(ctx, MissionRepeat.ONCE));
    }

    public LiteralArgumentBuilder<CommandSourceStack> buildBorder() {
        return Cmd.literal("border").executes(this::executeBorder);
    }

    public LiteralArgumentBuilder<CommandSourceStack> buildBounds() {
        return Cmd.literal("bounds").executes(this::executeBorder);
    }

    Optional<ProfileId> activeProfile(Player player) {
        if (sessionCoordinator == null) {
            return Optional.empty();
        }
        return sessionCoordinator.activeProfile(player.getUniqueId());
    }

    void send(Audience audience, String key, TagResolver... resolvers) {
        send(audience, messages.render(audience, key, resolvers));
    }

    void send(Audience audience, Component component) {
        if (audience instanceof Player player) {
            schedulerPort.onEntity(new PlayerUuid(player.getUniqueId()), () -> {
                if (player.isOnline()) {
                    player.sendMessage(component);
                }
            });
        } else {
            audience.sendMessage(component);
        }
    }

    private int executeLimits(CommandContext<CommandSourceStack> ctx) {
        Audience sender = ctx.getSource().getSender();
        if (!(sender instanceof Player player)) {
            send(sender, "error.players_only");
            return Cmd.OK;
        }

        IslandLimitService limitService = limitServiceProvider.get();
        if (limitService == null) {
            send(player, "limits.disabled");
            return Cmd.OK;
        }

        Optional<ProfileId> optProfile = activeProfile(player);
        if (optProfile.isEmpty()) {
            send(player, "error.session_not_active");
            return Cmd.OK;
        }

        ProfileId profileId = optProfile.get();
        schedulerPort.async(() -> {
            Optional<IslandId> optIslandId = islandLocationService.findIslandId(profileId);
            if (optIslandId.isEmpty()) {
                send(player, "error.no_island");
                return;
            }

            IslandId islandId = optIslandId.get();
            java.util.Map<LimitType, Integer> counts = limitService.getCounts(islandId);
            java.util.Map<LimitType, Integer> limits = limitService.getLimits(islandId);

            send(player, "limits.header");
            send(player, "limits.tile_header");
            sendLimitRows(player, LimitCategory.TILE_ENTITY, counts, limits);
            send(player, "limits.entity_header");
            sendLimitRows(player, LimitCategory.ENTITY, counts, limits);
        });

        return Cmd.OK;
    }

    private int executeQuarantine(CommandContext<CommandSourceStack> ctx) {
        Audience sender = ctx.getSource().getSender();
        if (!(sender instanceof Player player)) {
            send(sender, "error.players_only");
            return Cmd.OK;
        }
        IslandAntiAbuseService antiAbuseService = antiAbuseServiceProvider.get();
        if (antiAbuseService == null) {
            send(player, "quarantine.disabled");
            return Cmd.OK;
        }

        Optional<ProfileId> optProfile = activeProfile(player);
        if (optProfile.isEmpty()) {
            send(player, "error.session_not_active");
            return Cmd.OK;
        }

        ProfileId profileId = optProfile.get();
        schedulerPort.async(() -> {
            Optional<IslandId> optIsland = islandLocationService.findIslandId(profileId);
            if (optIsland.isEmpty()) {
                send(player, "error.no_island");
                return;
            }
            IslandId islandId = optIsland.get();
            Optional<Duration> optRemaining = antiAbuseService.getQuarantineRemaining(islandId, Instant.now());
            schedulerPort.onEntity(new PlayerUuid(player.getUniqueId()), () -> {
                if (optRemaining.isPresent()) {
                    send(
                            player,
                            "quarantine.active",
                            Placeholder.unparsed("remaining", DurationText.of(messages, player, optRemaining.get())));
                } else {
                    send(player, "quarantine.inactive");
                }
            });
        });
        return Cmd.OK;
    }

    private int executeBooster(CommandContext<CommandSourceStack> ctx) {
        Audience sender = ctx.getSource().getSender();
        if (!(sender instanceof Player player)) {
            send(sender, "error.players_only");
            return Cmd.OK;
        }
        IslandBoosterMenu boosterMenu = boosterMenuProvider.get();
        if (boosterMenu == null) {
            send(player, "booster.disabled");
            return Cmd.OK;
        }
        boosterMenu.open(player);
        return Cmd.OK;
    }

    /** Whether the booster is now running. A refused one is not worth a line in the feed. */
    static boolean isApplied(BoosterApplyResult result) {
        return result instanceof BoosterApplyResult.Success
                || result instanceof BoosterApplyResult.DurationExtended
                || result instanceof BoosterApplyResult.MultiplierStacked
                || result instanceof BoosterApplyResult.Replaced;
    }

    /** Opens the missions, only those that come back as {@code which} says when it is not null. */
    private int executeMissions(CommandContext<CommandSourceStack> ctx, @Nullable MissionRepeat which) {
        Audience sender = ctx.getSource().getSender();
        if (!(sender instanceof Player player)) {
            send(sender, "error.players_only");
            return Cmd.OK;
        }
        IslandMissionsMenu missionsMenu = missionsMenuProvider.get();
        if (missionsMenu == null) {
            send(player, "missions.disabled");
            return Cmd.OK;
        }
        schedulerPort.onEntity(new PlayerUuid(player.getUniqueId()), () -> missionsMenu.open(player, which));
        return Cmd.OK;
    }

    /** The window of the border, when the operator kept its file. */
    private Supplier<com.uxplima.uxmskyblock.bukkit.menu.@Nullable BorderWindow> borderWindow = () -> null;

    /** Hands {@code /is border} the window it opens. */
    public void useBorderWindow(Supplier<com.uxplima.uxmskyblock.bukkit.menu.@Nullable BorderWindow> window) {
        this.borderWindow = java.util.Objects.requireNonNull(window, "window must not be null");
    }

    private int executeBorder(CommandContext<CommandSourceStack> ctx) {
        Audience sender = ctx.getSource().getSender();
        if (!(sender instanceof Player player)) {
            send(sender, "error.players_only");
            return Cmd.OK;
        }
        // The window shows the border, hides it and picks its colour. Without its file the command switches it.
        com.uxplima.uxmskyblock.bukkit.menu.BorderWindow window = borderWindow.get();
        if (window != null && window.show(player)) {
            return Cmd.OK;
        }
        IslandBoundaryService boundaryService = boundaryServiceProvider.get();
        if (boundaryService == null) {
            send(player, "border.disabled");
            return Cmd.OK;
        }
        PlayerUuid uuid = new PlayerUuid(player.getUniqueId());
        boolean active = boundaryService.toggleBorder(uuid);
        com.uxplima.uxmskyblock.bukkit.boundary.BorderChoice.keep(player, active);
        if (active) {
            send(player, "border.enabled");
        } else {
            send(player, "border.turned_off");
        }
        return Cmd.OK;
    }

    /**
     * A row per limit, coloured by how close the island is to it. The colour is three catalogue
     * keys rather than a tag glued onto the number, because a tag glued onto a number cannot be
     * closed correctly and the old code closed it with the wrong tag.
     */
    private void sendLimitRows(
            Player player,
            LimitCategory category,
            java.util.Map<LimitType, Integer> counts,
            java.util.Map<LimitType, Integer> limits) {
        for (LimitType type : LimitType.values()) {
            if (type.category() != category || !limits.containsKey(type)) {
                continue;
            }
            int used = counts.getOrDefault(type, 0);
            int max = limits.get(type);
            String countKey =
                    used >= max ? "limits.count_full" : (used >= max * 0.8 ? "limits.count_near" : "limits.count_free");
            Component count =
                    messages.renderPlain(player, countKey, Placeholder.unparsed("count", Integer.toString(used)));
            send(
                    player,
                    "limits.entry",
                    Placeholder.component(
                            "type", com.uxplima.uxmskyblock.bukkit.limit.LimitNames.of(messages, player, type)),
                    Placeholder.component("count", count),
                    Placeholder.unparsed("max", Integer.toString(max)));
        }
    }

    static TagResolver multiplierOf(double multiplier) {
        return Placeholder.unparsed("multiplier", formatMultiplier(multiplier));
    }

    static String formatMultiplier(double multiplier) {
        return String.format(Locale.ROOT, "%.2f", multiplier);
    }

    /** The booster categories an operator can actually pass, read off the enum rather than typed. */
    static String availableBoosterCategories() {
        return java.util.Arrays.stream(BoosterCategory.values())
                .map(Enum::name)
                .collect(java.util.stream.Collectors.joining(", "));
    }

    static Duration parseDurationString(String raw) {
        if (raw == null || raw.isBlank()) {
            return Duration.ZERO;
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        try {
            if (s.endsWith("d")) {
                return Duration.ofDays(Long.parseLong(s.substring(0, s.length() - 1)));
            }
            if (s.endsWith("h")) {
                return Duration.ofHours(Long.parseLong(s.substring(0, s.length() - 1)));
            }
            if (s.endsWith("m")) {
                return Duration.ofMinutes(Long.parseLong(s.substring(0, s.length() - 1)));
            }
            if (s.endsWith("s")) {
                return Duration.ofSeconds(Long.parseLong(s.substring(0, s.length() - 1)));
            }
            return Duration.ofSeconds(Long.parseLong(s));
        } catch (NumberFormatException e) {
            return Duration.ZERO;
        }
    }
}
