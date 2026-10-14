package com.uxplima.uxmskyblock.bukkit.command;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

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
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.name.IslandName;
import com.uxplima.uxmskyblock.core.domain.name.IslandNameRefusedException;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import org.jspecify.annotations.Nullable;

/**
 * Handles island lifecycle commands:
 * /is create, /is reset, /is delete, /is rename
 */
public final class IslandLifecycleCommands {

    /** {@code /is reset}: the warning, the window or the typed confirmation, and the erasure. */
    private final ResetFlow reset = new ResetFlow(this);

    /** {@code /is create}: the kind offered or named, the island made, and the owner set down on it. */
    private final CreateFlow create = new CreateFlow(this);

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
    Supplier<com.uxplima.uxmskyblock.bukkit.lifecycle.@Nullable PlayerLifecycle> lifecycle = () -> null;

    /** What carries out the operator's lifecycle rules, looked up when a reset happens. */
    public void useLifecycle(Supplier<com.uxplima.uxmskyblock.bukkit.lifecycle.@Nullable PlayerLifecycle> lifecycle) {
        this.lifecycle = java.util.Objects.requireNonNull(lifecycle, "lifecycle must not be null");
    }

    /** How a player picks a kind of island, and who may start each, as {@code modules/presets.conf} says. */
    Supplier<com.uxplima.uxmskyblock.bukkit.config.PresetChoices> presetChoices =
            () -> com.uxplima.uxmskyblock.bukkit.config.PresetChoices.DEFAULT;

    /** The window of island kinds, when the operator kept its file. */
    private Supplier<com.uxplima.uxmskyblock.bukkit.menu.@Nullable PresetList> presetList = () -> null;

    /** Whether a kind of island can be made on this server, so the kinds a player is told of are ones it can. */
    java.util.function.Predicate<com.uxplima.uxmskyblock.core.domain.preset.StarterPreset> playableKinds =
            preset -> true;

    /** Hands the commands which kinds of island this server can make. */
    public void usePlayableKinds(
            java.util.function.Predicate<com.uxplima.uxmskyblock.core.domain.preset.StarterPreset> playable) {
        this.playableKinds = Objects.requireNonNull(playable, "playable must not be null");
    }

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
    void fireMilestone(String interaction, org.bukkit.entity.Player player) {
        com.uxplima.uxmskyblock.bukkit.effect.InteractionEffects written = this.effects;
        com.uxplima.uxmskyblock.bukkit.effect.InteractionEffectPlayer plays = this.effectPlayer;
        if (written != null && plays != null) {
            plays.fire(written, interaction, player);
        }
    }

    final CreateIslandUseCase createIslandUseCase;
    final IslandLocationService islandLocationService;
    final StarterPresetCatalog presetCatalog;
    final StarterSchematicEngine schematicEngine;
    final IslandProtectionListener protectionListener;
    volatile @Nullable IslandMarkerSynchroniser markerSynchroniser;
    final PlayerSessionCoordinator sessionCoordinator;
    final SchedulerPort schedulerPort;
    final ServerNodeId serverNodeId;
    final String worldName;
    final Supplier<@Nullable IslandAntiAbuseService> antiAbuseServiceProvider;

    /**
     * The bypass permissions the operator named.
     *
     * <p>The node was written into the code as {@code skyblock.antiabuse.bypass} while the file
     * named {@code uxmskyblock.bypass.resetlimits}, so whatever the operator granted their staff
     * did nothing and whatever they wrote in the file was never asked for.
     */
    private volatile com.uxplima.uxmskyblock.bukkit.config.@Nullable AntiAbuseConfiguration antiAbuseRules;

    final Supplier<@Nullable IslandRecycleService> recycleServiceProvider;
    final Supplier<@Nullable IslandResetConfirmationMenu> resetMenuProvider;
    private final Supplier<@Nullable IslandNameService> nameServiceProvider;
    final Messages messages;

    /** Where the preset a new island started from is written down as its feed's first line. */
    final IslandActivityLog activityLog = new IslandActivityLog();

    /** Tells this command group which permissions the operator lets staff bypass the rules with. */
    public void useAntiAbuseRules(com.uxplima.uxmskyblock.bukkit.config.@Nullable AntiAbuseConfiguration rules) {
        this.antiAbuseRules = rules;
    }

    /** Tells this command group where to write the island's activity feed. */
    public void useActivityFeed(@Nullable ActivityFeedService service) {
        this.activityLog.useService(service);
    }

    volatile com.uxplima.uxmskyblock.core.application.notification.@Nullable NotificationService notificationService;

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
                .executes(create::executeCreateWithoutKind)
                .then(Cmd.argument("preset", StringArgumentType.word())
                        .executes(ctx -> create.executeCreate(ctx, StringArgumentType.getString(ctx, "preset"))));
    }

    public LiteralArgumentBuilder<CommandSourceStack> buildReset() {
        return Cmd.literal("reset")
                .executes(reset::executeReset)
                .then(Cmd.literal("confirm").executes(reset::executeResetConfirm));
    }

    public LiteralArgumentBuilder<CommandSourceStack> buildDelete() {
        return Cmd.literal("delete")
                .executes(reset::executeReset)
                .then(Cmd.literal("confirm").executes(reset::executeResetConfirm));
    }

    /**
     * {@code /is disband}: the word the documents use for erasing the island.
     *
     * <p>It is {@code /is delete} with another name, its window and its confirmation and all. A document that
     * tells an operator to type a word is a document the command tree has to answer.
     */
    public LiteralArgumentBuilder<CommandSourceStack> buildDisband() {
        return Cmd.literal("disband")
                .executes(reset::executeReset)
                .then(Cmd.literal("confirm").executes(reset::executeResetConfirm));
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
    String presetName(Player player, com.uxplima.uxmskyblock.core.domain.preset.StarterPreset preset) {
        return messages.words(player, preset.displayName());
    }

    Optional<ProfileId> activeProfile(Player player) {
        if (sessionCoordinator == null) {
            return Optional.empty();
        }
        return sessionCoordinator.activeProfile(player.getUniqueId());
    }

    private Optional<IslandId> findIslandId(Player player) {
        return activeProfile(player).flatMap(islandLocationService::findIslandId);
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

    /**
     * Shows a player the window of island kinds, when the operator asks for it and kept its file, and answers
     * whether it opened.
     */
    public boolean offerKinds(Player player) {
        com.uxplima.uxmskyblock.bukkit.menu.PresetList list = presetList.get();
        return presetChoices.get().createAsks() && list != null && list.show(player);
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

    /**
     * Whether this player is let past the reset cooldown and the daily limit.
     *
     * <p>The node comes off the operator's file. A node with no file behind it falls back to the
     * one this plugin ships with, which is the same name the file is written with.
     */
    boolean mayBypassTheResetRules(Player player) {
        com.uxplima.uxmskyblock.bukkit.config.AntiAbuseConfiguration rules = this.antiAbuseRules;
        String node = rules == null
                ? com.uxplima.uxmskyblock.bukkit.config.AntiAbuseConfiguration.DEFAULT_RESET_BYPASS_PERMISSION
                : rules.resetBypassPermission();
        return player.hasPermission(node) || player.isOp();
    }

    /** Draws a new island on the web map, while one is running. */
    void islandCreated(IslandId island) {
        IslandMarkerSynchroniser markers = this.markerSynchroniser;
        if (markers != null) {
            markers.onIslandCreated(island);
        }
    }

    /** Takes an erased island off the web map, while one is running: a map that draws it is worse than no map. */
    void islandRemoved(IslandId island) {
        IslandMarkerSynchroniser markers = this.markerSynchroniser;
        if (markers != null) {
            markers.onIslandRemoved(island);
        }
    }
}
