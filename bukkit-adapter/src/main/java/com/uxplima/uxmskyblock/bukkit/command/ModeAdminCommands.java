package com.uxplima.uxmskyblock.bukkit.command;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiPredicate;
import java.util.function.Consumer;

import org.bukkit.plugin.java.JavaPlugin;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.uxplima.uxmlib.command.Cmd;
import com.uxplima.uxmlib.command.CommandRegistrar;
import com.uxplima.uxmlib.command.annotation.ConfiguredCommands;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import org.jspecify.annotations.Nullable;

/**
 * {@code /isadmin}: what an operator does to the islands of one mode, a branch per mode the server runs and a verb
 * per change. The command, each mode's word and each verb are named in {@code commands.conf}, under
 * {@code isadmin}, and each verb asks a permission of its own, named {@code uxmskyblock.admin.<mode>.<verb>}.
 */
public final class ModeAdminCommands {

    /** The key the command is read under, and the word it answers to when the file says nothing. */
    static final String ROOT = "isadmin";

    /** One mode's branch: its key, and its verbs under their own keys. */
    public interface Mode {

        /** The key the branch is read under and the word it answers to when the file says nothing. */
        String key();

        /** The branch, its literal the key and each verb's literal its own key. */
        LiteralArgumentBuilder<CommandSourceStack> build(ModeAdminCommands admin);
    }

    private final List<Mode> modes;
    private final IslandLocationService locations;
    private final @Nullable PlayerSessionCoordinator sessions;
    private final SchedulerPort scheduler;
    private final Messages messages;

    public ModeAdminCommands(
            List<Mode> modes,
            IslandLocationService locations,
            @Nullable PlayerSessionCoordinator sessions,
            SchedulerPort scheduler,
            Messages messages) {
        this.modes = List.copyOf(modes);
        this.locations = Objects.requireNonNull(locations, "locations must not be null");
        this.sessions = sessions;
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
    }

    /** The verb {@code verb}, there only for a sender who holds {@code permission}, its own node. */
    public static LiteralArgumentBuilder<CommandSourceStack> verb(
            String verb, com.uxplima.uxmskyblock.bukkit.permission.CatalogPermissions permission) {
        return Cmd.literal(verb).requires(Cmd.permission(permission.node()));
    }

    /** The whole command under the names the file gives, or nothing when no mode runs or the file turns it off. */
    Optional<LiteralArgumentBuilder<CommandSourceStack>> build(
            @Nullable ConfiguredCommands names, BiPredicate<CommandSourceStack, String> reads) {
        ConfiguredCommandTree<CommandSourceStack> tree =
                new ConfiguredCommandTree<>(names, reads, ROOT, ROOT, List.of());
        if (modes.isEmpty() || !tree.root().enabled()) {
            return Optional.empty();
        }
        LiteralArgumentBuilder<CommandSourceStack> root = Cmd.literal(ROOT);
        List<java.util.function.Predicate<CommandSourceStack>> branches = new java.util.ArrayList<>();
        for (Mode mode : modes) {
            LiteralArgumentBuilder<CommandSourceStack> branch = mode.build(this);
            // A branch is there for a sender who may use one of its verbs, and the command for one who may use any.
            List<java.util.function.Predicate<CommandSourceStack>> verbs = branch.getArguments().stream()
                    .map(com.mojang.brigadier.tree.CommandNode::getRequirement)
                    .toList();
            java.util.function.Predicate<CommandSourceStack> any =
                    source -> verbs.stream().anyMatch(verb -> verb.test(source));
            branches.add(any);
            ConfiguredCommandTree<CommandSourceStack> named = new ConfiguredCommandTree<>(
                    names, reads, ConfiguredCommands.branchKey(ROOT, mode.key()), mode.key(), List.of());
            root.then(named.applyToBranches(branch.requires(any)));
        }
        root.requires(source -> branches.stream().anyMatch(branch -> branch.test(source)));
        return Optional.of(tree.apply(root));
    }

    /** Registers the command, as {@code names} names it. */
    public void register(
            JavaPlugin plugin, @Nullable ConfiguredCommands names, BiPredicate<CommandSourceStack, String> reads) {
        build(names, reads).ifPresent(root -> {
            ConfiguredCommandTree<CommandSourceStack> tree =
                    new ConfiguredCommandTree<>(names, reads, ROOT, ROOT, List.of());
            CommandRegistrar.register(
                    plugin, root.build(), "What an operator does to a mode's islands", tree.rootAliases());
        });
    }

    /**
     * Finds the island {@code target} names, a player's name or an island's id, off the main thread, and hands it
     * to {@code then} there; a target with no island is told to the sender.
     */
    public void island(CommandSourceStack source, String target, Consumer<IslandId> then) {
        scheduler.async(() -> {
            Optional<IslandId> found = IslandAdminCommands.resolveIslandId(sessions, locations, target);
            if (found.isEmpty()) {
                send(source.getSender(), "admin.island_unresolved", Placeholder.unparsed("target", target));
                return;
            }
            then.accept(found.get());
        });
    }

    /** Where islands lie, for a verb that changes the world. */
    public IslandLocationService locations() {
        return locations;
    }

    public SchedulerPort scheduler() {
        return scheduler;
    }

    public Messages messages() {
        return messages;
    }

    public void send(Audience audience, String key, TagResolver... resolvers) {
        messages.send(audience, key, resolvers);
    }
}
