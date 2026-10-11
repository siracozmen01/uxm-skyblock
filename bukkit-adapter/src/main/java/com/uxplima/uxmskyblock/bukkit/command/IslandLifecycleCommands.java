package com.uxplima.uxmskyblock.bukkit.command;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
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
import com.uxplima.uxmskyblock.bukkit.i18n.DurationText;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.menu.IslandResetConfirmationMenu;
import com.uxplima.uxmskyblock.bukkit.schematic.StarterSchematicEngine;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.bukkit.webmap.IslandMarkerSynchroniser;
import com.uxplima.uxmskyblock.core.application.activity.ActivityFeedService;
import com.uxplima.uxmskyblock.core.application.antiabuse.IslandAntiAbuseService;
import com.uxplima.uxmskyblock.core.application.island.CreateIslandUseCase;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.name.IslandNameService;
import com.uxplima.uxmskyblock.core.application.preset.StarterPresetCatalog;
import com.uxplima.uxmskyblock.core.application.recycle.IslandRecycleService;
import com.uxplima.uxmskyblock.core.application.recycle.IslandRecycleService.RecycleResult;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.activity.ActivityEventType;
import com.uxplima.uxmskyblock.core.domain.antiabuse.ResetCheckResult;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;
import com.uxplima.uxmskyblock.core.domain.name.IslandName;
import com.uxplima.uxmskyblock.core.domain.name.IslandNameRefusedException;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import org.jspecify.annotations.Nullable;

/**
 * Handles island lifecycle commands:
 * /is create, /is reset, /is delete, /is rename
 */
public final class IslandLifecycleCommands {

    private static final java.util.logging.Logger LOGGER =
            java.util.logging.Logger.getLogger(IslandLifecycleCommands.class.getName());

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
    private Supplier<com.uxplima.uxmskyblock.bukkit.lifecycle.@Nullable PlayerLifecycle> lifecycle = () -> null;

    /** What carries out the operator's lifecycle rules, looked up when a reset happens. */
    public void useLifecycle(Supplier<com.uxplima.uxmskyblock.bukkit.lifecycle.@Nullable PlayerLifecycle> lifecycle) {
        this.lifecycle = java.util.Objects.requireNonNull(lifecycle, "lifecycle must not be null");
    }

    /** How a player picks a kind of island, and who may start each, as {@code modules/presets.conf} says. */
    private Supplier<com.uxplima.uxmskyblock.bukkit.config.PresetChoices> presetChoices =
            () -> com.uxplima.uxmskyblock.bukkit.config.PresetChoices.DEFAULT;

    /** The window of island kinds, when the operator kept its file. */
    private Supplier<com.uxplima.uxmskyblock.bukkit.menu.@Nullable PresetList> presetList = () -> null;

    /** Tells this command group how a player picks a kind of island, and where the window of kinds is. */
    public void usePresetChoices(
            Supplier<com.uxplima.uxmskyblock.bukkit.config.PresetChoices> choices,
            Supplier<com.uxplima.uxmskyblock.bukkit.menu.@Nullable PresetList> list) {
        this.presetChoices = java.util.Objects.requireNonNull(choices, "choices must not be null");
        this.presetList = java.util.Objects.requireNonNull(list, "list must not be null");
    }

    public void useEffects(
            com.uxplima.uxmskyblock.bukkit.effect.@Nullable InteractionEffects effects,
            com.uxplima.uxmskyblock.bukkit.effect.@Nullable InteractionEffectPlayer player) {
        this.effects = effects;
        this.effectPlayer = player;
    }

    /** Fires one milestone for one player, when the operator wrote anything for it. */
    private void fireMilestone(String interaction, org.bukkit.entity.Player player) {
        com.uxplima.uxmskyblock.bukkit.effect.InteractionEffects written = this.effects;
        com.uxplima.uxmskyblock.bukkit.effect.InteractionEffectPlayer plays = this.effectPlayer;
        if (written != null && plays != null) {
            plays.fire(written, interaction, player);
        }
    }

    private final CreateIslandUseCase createIslandUseCase;
    private final IslandLocationService islandLocationService;
    private final StarterPresetCatalog presetCatalog;
    private final StarterSchematicEngine schematicEngine;
    private final IslandProtectionListener protectionListener;
    private volatile @Nullable IslandMarkerSynchroniser markerSynchroniser;
    private final PlayerSessionCoordinator sessionCoordinator;
    private final SchedulerPort schedulerPort;
    private final ServerNodeId serverNodeId;
    private final String worldName;
    private final Supplier<@Nullable IslandAntiAbuseService> antiAbuseServiceProvider;

    /**
     * The bypass permissions the operator named.
     *
     * <p>The node was written into the code as {@code skyblock.antiabuse.bypass} while the file
     * named {@code uxmskyblock.bypass.resetlimits}, so whatever the operator granted their staff
     * did nothing and whatever they wrote in the file was never asked for.
     */
    private volatile com.uxplima.uxmskyblock.bukkit.config.@Nullable AntiAbuseConfiguration antiAbuseRules;

    private final Supplier<@Nullable IslandRecycleService> recycleServiceProvider;
    private final Supplier<@Nullable IslandResetConfirmationMenu> resetMenuProvider;
    private final Supplier<@Nullable IslandNameService> nameServiceProvider;
    private final Messages messages;

    /** Where the preset a new island started from is written down as its feed's first line. */
    private final IslandActivityLog activityLog = new IslandActivityLog();

    /** Tells this command group which permissions the operator lets staff bypass the rules with. */
    public void useAntiAbuseRules(com.uxplima.uxmskyblock.bukkit.config.@Nullable AntiAbuseConfiguration rules) {
        this.antiAbuseRules = rules;
    }

    /** Tells this command group where to write the island's activity feed. */
    public void useActivityFeed(@Nullable ActivityFeedService service) {
        this.activityLog.useService(service);
    }

    private volatile com.uxplima.uxmskyblock.core.application.notification.@Nullable NotificationService
            notificationService;

    /** Where a member who is away is told that the island they belonged to was reset. */
    public void useNotifications(
            com.uxplima.uxmskyblock.core.application.notification.@Nullable NotificationService service) {
        this.notificationService = service;
    }

    public IslandLifecycleCommands(
            CreateIslandUseCase createIslandUseCase,
            IslandLocationService islandLocationService,
            StarterPresetCatalog presetCatalog,
            StarterSchematicEngine schematicEngine,
            IslandProtectionListener protectionListener,
            PlayerSessionCoordinator sessionCoordinator,
            SchedulerPort schedulerPort,
            ServerNodeId serverNodeId,
            String worldName,
            Supplier<@Nullable IslandAntiAbuseService> antiAbuseServiceProvider,
            Supplier<@Nullable IslandRecycleService> recycleServiceProvider,
            Supplier<@Nullable IslandResetConfirmationMenu> resetMenuProvider,
            Supplier<@Nullable IslandNameService> nameServiceProvider,
            Messages messages) {
        this.createIslandUseCase = Objects.requireNonNull(createIslandUseCase, "createIslandUseCase must not be null");
        this.islandLocationService =
                Objects.requireNonNull(islandLocationService, "islandLocationService must not be null");
        this.presetCatalog = Objects.requireNonNull(presetCatalog, "presetCatalog must not be null");
        this.schematicEngine = Objects.requireNonNull(schematicEngine, "schematicEngine must not be null");
        this.protectionListener = Objects.requireNonNull(protectionListener, "protectionListener must not be null");
        this.sessionCoordinator = Objects.requireNonNull(sessionCoordinator, "sessionCoordinator must not be null");
        this.schedulerPort = Objects.requireNonNull(schedulerPort, "schedulerPort must not be null");
        this.serverNodeId = Objects.requireNonNull(serverNodeId, "serverNodeId must not be null");
        this.worldName = Objects.requireNonNull(worldName, "worldName must not be null");
        this.antiAbuseServiceProvider =
                Objects.requireNonNull(antiAbuseServiceProvider, "antiAbuseServiceProvider must not be null");
        this.recycleServiceProvider =
                Objects.requireNonNull(recycleServiceProvider, "recycleServiceProvider must not be null");
        this.resetMenuProvider = Objects.requireNonNull(resetMenuProvider, "resetMenuProvider must not be null");
        this.nameServiceProvider = Objects.requireNonNull(nameServiceProvider, "nameServiceProvider must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
    }

    /**
     * Hands this command the thing that draws islands on the web map.
     *
     * <p>Set after construction because the map adapters are built in the integration layer, which
     * is assembled after the commands are.
     */
    public void useMarkerSynchroniser(@Nullable IslandMarkerSynchroniser markerSynchroniser) {
        this.markerSynchroniser = markerSynchroniser;
    }

    public LiteralArgumentBuilder<CommandSourceStack> buildCreate() {
        return Cmd.literal("create")
                .executes(this::executeCreateWithoutKind)
                .then(Cmd.argument("preset", StringArgumentType.word())
                        .executes(ctx -> executeCreate(ctx, StringArgumentType.getString(ctx, "preset"))));
    }

    public LiteralArgumentBuilder<CommandSourceStack> buildReset() {
        return Cmd.literal("reset")
                .executes(this::executeReset)
                .then(Cmd.literal("confirm").executes(this::executeResetConfirm));
    }

    public LiteralArgumentBuilder<CommandSourceStack> buildDelete() {
        return Cmd.literal("delete")
                .executes(this::executeReset)
                .then(Cmd.literal("confirm").executes(this::executeResetConfirm));
    }

    /**
     * {@code /is disband}: the word the documents use for erasing the island.
     *
     * <p>It is {@code /is delete} with another name, its window and its confirmation and all. A document that
     * tells an operator to type a word is a document the command tree has to answer.
     */
    public LiteralArgumentBuilder<CommandSourceStack> buildDisband() {
        return Cmd.literal("disband")
                .executes(this::executeReset)
                .then(Cmd.literal("confirm").executes(this::executeResetConfirm));
    }

    public LiteralArgumentBuilder<CommandSourceStack> buildRename() {
        return Cmd.literal("rename")
                .executes(this::executeGetRename)
                .then(Cmd.argument("name", StringArgumentType.greedyString()).executes(this::executeRename));
    }

    /**
     * The preset's name in the player's own language.
     *
     * <p>A preset's name is a catalogue key, because it is a sentence a player reads and no sentence
     * a player reads is written in Java. A file that holds a plain name rather than a key still
     * works: the name is shown as it stands.
     */
    private String presetName(Player player, com.uxplima.uxmskyblock.core.domain.preset.StarterPreset preset) {
        return messages.words(player, preset.displayName());
    }

    private Optional<ProfileId> activeProfile(Player player) {
        if (sessionCoordinator == null) {
            return Optional.empty();
        }
        return sessionCoordinator.activeProfile(player.getUniqueId());
    }

    private Optional<IslandId> findIslandId(Player player) {
        return activeProfile(player).flatMap(islandLocationService::findIslandId);
    }

    private void send(Audience audience, String key, TagResolver... resolvers) {
        send(audience, messages.render(audience, key, resolvers));
    }

    private void send(Audience audience, Component component) {
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

    /**
     * The height a player arrives standing at on a new island, found in the column it was built in, on
     * the thread that owns it. A spawn that had to move is written back, so a home is not inside a hill
     * either.
     */
    private double arrivalOn(World world, IslandId islandId, IslandLocation location, boolean underwater) {
        int planned = (int) Math.floor(location.spawnY());
        java.util.OptionalInt safe = com.uxplima.uxmskyblock.bukkit.world.SafeArrival.standingY(
                world, (int) Math.floor(location.spawnX()), planned, (int) Math.floor(location.spawnZ()), underwater);
        if (safe.isEmpty()) {
            // Rock as far as the search reaches: the room above the platform is cleared instead.
            com.uxplima.uxmskyblock.bukkit.world.SafeArrival.makeRoom(
                    world,
                    (int) Math.floor(location.spawnX()),
                    planned,
                    (int) Math.floor(location.spawnZ()),
                    underwater);
            return location.spawnY();
        }
        if (safe.getAsInt() == planned) {
            return location.spawnY();
        }
        double moved = safe.getAsInt();
        schedulerPort.async(() -> islandLocationService.moveSpawnHeight(islandId, moved));
        return moved;
    }

    /** Sends the player who made the island onto it, standing at {@code standingY}, and tells them. */
    private void arrive(
            Player player,
            PlayerUuid playerUuid,
            World world,
            CreateIslandUseCase.CreateIslandResult.Success success,
            double standingY) {
        schedulerPort.onEntity(playerUuid, () -> {
            if (!player.isOnline()) {
                return;
            }
            Location destination = new Location(
                    world,
                    success.location().spawnX(),
                    standingY,
                    success.location().spawnZ(),
                    0.0f,
                    0.0f);
            var unused = player.teleportAsync(destination).thenAccept(teleported -> {
                if (Boolean.TRUE.equals(teleported)) {
                    player.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
                    player.setFallDistance(0.0f);
                }
            });
            send(player, "create.success", Placeholder.unparsed("preset", presetName(player, success.preset())));
            fireMilestone("island-created", player);
        });
    }

    /** {@code /is create} with no kind written: the window of kinds, or the default island, as the operator chose. */
    private int executeCreateWithoutKind(CommandContext<CommandSourceStack> ctx) {
        if (ctx.getSource().getSender() instanceof Player player && offerKinds(player)) {
            return Cmd.OK;
        }
        return executeCreate(ctx, presetCatalog.defaultPreset().id());
    }

    /**
     * Shows a player the window of island kinds, when the operator asks for it and kept its file, and answers
     * whether it opened.
     */
    public boolean offerKinds(Player player) {
        com.uxplima.uxmskyblock.bukkit.menu.PresetList list = presetList.get();
        return presetChoices.get().createAsks() && list != null && list.show(player);
    }

    private int executeCreate(CommandContext<CommandSourceStack> ctx, String presetId) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            send(ctx.getSource().getSender(), "error.players_only");
            return Cmd.OK;
        }
        // A kind the operator keeps behind a permission node is refused before anything is made.
        java.util.Optional<com.uxplima.uxmskyblock.core.domain.preset.StarterPreset> asked =
                presetCatalog.findById(presetId);
        if (asked.isPresent() && !presetChoices.get().allows(player, asked.get().id())) {
            send(
                    player,
                    "create.preset_locked",
                    Placeholder.unparsed(
                            "preset", messages.words(player, asked.get().displayName())));
            return Cmd.OK;
        }

        PlayerUuid playerUuid = new PlayerUuid(player.getUniqueId());
        Optional<ProfileId> optProfile = activeProfile(player);
        if (optProfile.isEmpty()) {
            send(player, "error.session_not_active");
            return Cmd.OK;
        }
        ProfileId profileId = optProfile.get();
        // A preset may name a world of its own. One the server has not loaded would get an island
        // written to it and nowhere to send the player, so nothing is made.
        java.util.Optional<String> missingWorld = presetCatalog
                .findById(presetId)
                .map(preset -> preset.worldOr(worldName))
                .filter(world -> Bukkit.getWorld(world) == null);
        if (missingWorld.isPresent()) {
            send(player, "create.world_missing", Placeholder.unparsed("world", missingWorld.get()));
            return Cmd.OK;
        }

        schedulerPort.async(() -> {
            CreateIslandUseCase.CreateIslandResult result;
            try {
                result = createIslandUseCase.execute(playerUuid, profileId, presetId, serverNodeId, worldName);
            } catch (RuntimeException thrown) {
                // A database that refuses a statement throws out of the use case. The player was told
                // nothing and the command just stopped; the trace goes to the log, they get the failure.
                LOGGER.log(
                        java.util.logging.Level.WARNING,
                        thrown,
                        () -> "Creating an island for " + player.getName() + " threw.");
                result = new CreateIslandUseCase.CreateIslandResult.Failure(String.valueOf(thrown.getMessage()));
            }
            CreateIslandUseCase.CreateIslandResult outcome = result;

            schedulerPort.onEntity(playerUuid, () -> {
                if (outcome instanceof CreateIslandUseCase.CreateIslandResult.Success success) {
                    activityLog.recordForMembers(
                            success.island().id(),
                            profileId,
                            ActivityEventType.TEMPLATE_APPLIED,
                            "activity.template_applied",
                            java.util.Map.of(
                                    "player",
                                    player.getName(),
                                    "preset",
                                    // The preset's own name, which is a key a reader resolves.
                                    success.preset().displayName()));
                    protectionListener.cacheIsland(success.island());
                    IslandMarkerSynchroniser markers = this.markerSynchroniser;
                    if (markers != null) {
                        markers.onIslandCreated(success.island().id());
                    }
                    IslandAntiAbuseService antiAbuse = antiAbuseServiceProvider.get();
                    if (antiAbuse != null) {
                        antiAbuse.quarantineNewIsland(success.island().id(), Instant.now());
                    }

                    // The world the island was made in, which its preset may name.
                    String madeIn = success.location().worldName();
                    World resolvedWorld = Bukkit.getWorld(madeIn);
                    if (resolvedWorld != null) {
                        int centerX = success.location().bounds().centerX();
                        int centerZ = success.location().bounds().centerZ();
                        int platformY = StarterSchematicEngine.platformBelow(
                                success.location().spawnY());
                        int chunkX = centerX >> 4;
                        int chunkZ = centerZ >> 4;
                        String targetWorld = resolvedWorld.getName();

                        schedulerPort.onRegion(targetWorld, chunkX, chunkZ, () -> {
                            World w = Bukkit.getWorld(targetWorld);
                            if (w == null) {
                                arrive(
                                        player,
                                        playerUuid,
                                        resolvedWorld,
                                        success,
                                        success.location().spawnY());
                                return;
                            }
                            // The preset's own creation actions: a platform, a pasted schematic, a OneBlock
                            // island's block. A schematic stands some ticks later, and the player arrives
                            // once it does, on whatever it built.
                            com.uxplima.uxmskyblock.bukkit.schematic.StarterSchematicEngine.afterBuilt(
                                    schematicEngine.start(new com.uxplima.uxmskyblock.bukkit.schematic.IslandStart(
                                            w, success.island().id(), centerX, platformY, centerZ, success.preset())),
                                    failure -> LOGGER.log(
                                            java.util.logging.Level.WARNING,
                                            failure,
                                            () -> "Building the island of " + player.getName() + " failed."),
                                    () -> schedulerPort.onRegion(targetWorld, chunkX, chunkZ, () -> {
                                        double arrivalY = arrivalOn(
                                                w,
                                                success.island().id(),
                                                success.location(),
                                                success.preset().mode().playedUnderwater());
                                        arrive(player, playerUuid, w, success, arrivalY);
                                    }));
                        });
                    } else {
                        send(player, "create.world_unloaded", Placeholder.unparsed("world", madeIn));
                    }
                } else if (outcome instanceof CreateIslandUseCase.CreateIslandResult.AlreadyHasIsland) {
                    send(player, "create.already_has_island");
                } else if (outcome instanceof CreateIslandUseCase.CreateIslandResult.UnknownPreset unknown) {
                    send(
                            player,
                            "create.unknown_preset",
                            Placeholder.unparsed("preset", unknown.presetId()),
                            Placeholder.unparsed("presets", availablePresetIds()));
                } else if (outcome instanceof CreateIslandUseCase.CreateIslandResult.Failure failure) {
                    // The reason is an exception's message, which can be a database's own words. It
                    // goes to the log; the player reads a line in their own language.
                    LOGGER.warning(() -> "Creating an island for " + player.getName() + " failed: " + failure.reason());
                    send(player, "create.failed");
                }
            });
        });

        return Cmd.OK;
    }

    private int executeReset(CommandContext<CommandSourceStack> ctx) {
        Audience sender = ctx.getSource().getSender();
        if (!(sender instanceof Player player)) {
            send(sender, "error.players_only");
            return Cmd.OK;
        }
        IslandRecycleService recycleService = recycleServiceProvider.get();
        if (recycleService == null) {
            send(player, "reset.service_disabled");
            return Cmd.OK;
        }

        Optional<ProfileId> optProfile = activeProfile(player);
        if (optProfile.isEmpty()) {
            send(player, "error.session_not_active");
            return Cmd.OK;
        }

        ProfileId profileId = optProfile.get();
        PlayerUuid playerUuid = new PlayerUuid(player.getUniqueId());
        IslandAntiAbuseService antiAbuse = antiAbuseServiceProvider.get();
        // The permission is read here, on the thread that owns the player, and carried into the
        // scheduler. Everything after it is storage.
        boolean bypass = mayBypassTheResetRules(player);

        schedulerPort.async(() -> {
            if (islandLocationService.findIslandId(profileId).isEmpty()) {
                send(player, "error.no_island");
                return;
            }
            if (checkResetBlocked(player, antiAbuse, bypass)) {
                return;
            }

            // The window asks with two buttons, erase and keep, and nothing to copy out of chat. A code to
            // type back proved nothing the button did not: the player who clicks it is the owner who asked.
            IslandResetConfirmationMenu resetMenu = resetMenuProvider.get();
            if (resetMenu == null) {
                send(player, "reset.warning");
                send(player, "reset.warning_detail");
                send(player, "reset.confirm_hint");
                return;
            }
            // Opening an inventory belongs to the thread that owns the player.
            schedulerPort.onEntity(playerUuid, () -> {
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
            send(
                    player,
                    "reset.cooldown",
                    Placeholder.unparsed("remaining", DurationText.of(messages, player, cd.remaining())));
            return true;
        } else if (check instanceof ResetCheckResult.DailyLimitExceeded dl) {
            send(
                    player,
                    "reset.daily_limit",
                    Placeholder.unparsed("max", Integer.toString(dl.maxDailyResets())),
                    Placeholder.unparsed("remaining", DurationText.of(messages, player, dl.remaining())));
            return true;
        }
        return false;
    }

    private int executeResetConfirm(CommandContext<CommandSourceStack> ctx) {
        Audience sender = ctx.getSource().getSender();
        if (!(sender instanceof Player player)) {
            send(sender, "error.players_only");
            return Cmd.OK;
        }
        IslandRecycleService recycleService = recycleServiceProvider.get();
        if (recycleService == null) {
            send(player, "reset.service_disabled");
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
        Optional<ProfileId> optProfile = activeProfile(player);
        if (optProfile.isEmpty()) {
            send(player, "error.session_not_active");
            return;
        }

        ProfileId profileId = optProfile.get();
        IslandAntiAbuseService antiAbuse = antiAbuseServiceProvider.get();
        boolean bypass = mayBypassTheResetRules(player);

        schedulerPort.async(() -> {
            Optional<IslandId> optIsland = islandLocationService.findIslandId(profileId);
            if (optIsland.isEmpty()) {
                send(player, "error.no_island");
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
                send(player, "reset.already_running");
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
        java.util.List<com.uxplima.uxmskyblock.core.domain.island.IslandMember> others = islandLocationService
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
            send(player, "reset.failed");
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
                    com.uxplima.uxmskyblock.bukkit.lifecycle.PlayerLifecycle rules = lifecycle.get();
                    if (result instanceof RecycleResult.Success && rules != null) {
                        rules.happened(
                                com.uxplima.uxmskyblock.core.domain.lifecycle.LifecycleEvent.RESET,
                                playerUuid,
                                profileId);
                    }
                    schedulerPort.onEntity(new PlayerUuid(player.getUniqueId()), () -> {
                        switch (result) {
                            case RecycleResult.Success s -> {
                                // The island is gone, so its marker must go with it. A web map that
                                // keeps drawing an island nobody can visit is worse than no map.
                                IslandMarkerSynchroniser markers = this.markerSynchroniser;
                                if (markers != null) {
                                    markers.onIslandRemoved(islandId);
                                }
                                if (antiAbuse != null && antiAbuse.purgeInventoryOnReset()) {
                                    com.uxplima.uxmskyblock.bukkit.antiabuse.ResetInventoryPurge.purgeAndSettle(
                                            antiAbuse, schedulerPort, player);
                                }
                                // Asynchronous, because Folia throws on a synchronous teleport, and
                                // that left the player standing where their island had been with no
                                // word that it was gone.
                                var unusedTeleport =
                                        player.teleportAsync(player.getWorld().getSpawnLocation());
                                send(player, "reset.success");
                                send(player, "reset.success_hint");
                            }
                            case RecycleResult.NotOwner no -> send(player, "reset.not_owner");
                            case RecycleResult.InvalidChallenge ic -> send(player, "reset.invalid_challenge");
                            case RecycleResult.IslandNotFound nf -> send(player, "reset.island_not_found");
                            case RecycleResult.AlreadyRunning ar -> send(player, "reset.already_running");
                            case RecycleResult.Failure f -> {
                                LOGGER.warning(() -> "Resetting island " + islandId + " for " + player.getName()
                                        + " failed: " + f.reason());
                                send(player, "reset.failed");
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
                send(online, "reset.member_notice", Placeholder.unparsed("player", owner));
                continue;
            }
            var notices = this.notificationService;
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

    private int executeGetRename(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            send(ctx.getSource().getSender(), "error.players_only");
            return Cmd.OK;
        }
        IslandNameService nameService = nameServiceProvider.get();
        if (nameService == null) {
            send(player, "name.service_disabled");
            return Cmd.OK;
        }
        schedulerPort.async(() -> {
            Optional<IslandId> optIslandId = findIslandId(player);
            if (optIslandId.isEmpty()) {
                send(player, "name.requires_island");
                return;
            }
            Optional<IslandName> current = nameService.getIslandName(optIslandId.get());
            if (current.isPresent()) {
                send(
                        player,
                        "name.current",
                        Placeholder.unparsed("name", current.get().value()));
            } else {
                send(player, "name.unset");
            }
        });
        return Cmd.OK;
    }

    private int executeRename(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            send(ctx.getSource().getSender(), "error.players_only");
            return Cmd.OK;
        }
        IslandNameService nameService = nameServiceProvider.get();
        if (nameService == null) {
            send(player, "name.service_disabled");
            return Cmd.OK;
        }
        Optional<ProfileId> optProfile = activeProfile(player);
        if (optProfile.isEmpty()) {
            send(player, "error.session_not_active");
            return Cmd.OK;
        }
        ProfileId profileId = optProfile.get();
        String rawName = StringArgumentType.getString(ctx, "name");

        schedulerPort.async(() -> {
            Optional<IslandId> optIslandId = findIslandId(player);
            if (optIslandId.isEmpty()) {
                send(player, "name.requires_island");
                return;
            }
            try {
                IslandName newName = nameService.renameIsland(optIslandId.get(), profileId, rawName);
                IslandMarkerSynchroniser markers = this.markerSynchroniser;
                if (markers != null) {
                    markers.onIslandChanged(optIslandId.get());
                }
                send(player, "name.renamed", Placeholder.unparsed("name", newName.value()));
            } catch (IslandNameRefusedException refused) {
                switch (refused.reason()) {
                    case LENGTH ->
                        send(
                                player,
                                "name.refused_length",
                                Placeholder.unparsed("min", Integer.toString(IslandName.MIN_LENGTH)),
                                Placeholder.unparsed("max", Integer.toString(IslandName.MAX_LENGTH)));
                    case CHARACTERS -> send(player, "name.refused_characters");
                    case RESERVED -> send(player, "name.refused_reserved");
                    case UNSAFE -> send(player, "name.refused_unsafe");
                }
            } catch (SecurityException e) {
                send(player, "name.refused_permission");
            } catch (IllegalStateException e) {
                send(player, "name.refused_taken");
            } catch (IllegalArgumentException e) {
                // The island went away between finding it and renaming it.
                send(player, "name.requires_island");
            }
        });
        return Cmd.OK;
    }

    /** The preset ids an operator can actually pass, read off the catalogue rather than typed. */
    private String availablePresetIds() {
        return presetCatalog.allPresets().stream()
                .map(preset -> preset.id())
                .collect(java.util.stream.Collectors.joining(", "));
    }

    /**
     * Whether this player is let past the reset cooldown and the daily limit.
     *
     * <p>The node comes off the operator's file. A node with no file behind it falls back to the
     * one this plugin ships with, which is the same name the file is written with.
     */
    private boolean mayBypassTheResetRules(Player player) {
        com.uxplima.uxmskyblock.bukkit.config.AntiAbuseConfiguration rules = this.antiAbuseRules;
        String node = rules == null
                ? com.uxplima.uxmskyblock.bukkit.config.AntiAbuseConfiguration.DEFAULT_RESET_BYPASS_PERMISSION
                : rules.resetBypassPermission();
        return player.hasPermission(node) || player.isOp();
    }
}
