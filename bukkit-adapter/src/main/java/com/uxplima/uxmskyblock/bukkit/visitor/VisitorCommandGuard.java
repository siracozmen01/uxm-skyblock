package com.uxplima.uxmskyblock.bukkit.visitor;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import com.uxplima.uxmskyblock.bukkit.config.VisitorsConfiguration;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;

/**
 * Refuses a visitor the commands the operator listed, on an island they are no member of. A player whose session is
 * not known yet is a visitor, and staff who bypass the island protection are never one.
 */
public final class VisitorCommandGuard implements Listener {

    private final Supplier<VisitorsConfiguration> configuration;
    private final IslandProtectionListener islands;
    private final Messages messages;
    private final java.util.function.Function<String, java.util.Set<String>> namesOf;

    /**
     * @param namesOf every name the command a label runs answers to: its own, its aliases and its prefixed names
     */
    public VisitorCommandGuard(
            Supplier<VisitorsConfiguration> configuration,
            IslandProtectionListener islands,
            Messages messages,
            java.util.function.Function<String, java.util.Set<String>> namesOf) {
        this.configuration = Objects.requireNonNull(configuration, "configuration must not be null");
        this.islands = Objects.requireNonNull(islands, "islands must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
        this.namesOf = Objects.requireNonNull(namesOf, "namesOf must not be null");
    }

    /** Every name the command {@code label} runs on this server answers to, or the label alone for none. */
    public static java.util.Set<String> serverNamesOf(String label) {
        org.bukkit.command.Command command = org.bukkit.Bukkit.getCommandMap().getCommand(label);
        java.util.Set<String> names = new java.util.HashSet<>();
        names.add(label);
        if (command != null) {
            names.add(command.getName());
            names.add(command.getLabel());
            names.addAll(command.getAliases());
        }
        return names;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        Optional<String> blocked = configuration.get().blocking(event.getMessage(), namesOf);
        if (blocked.isEmpty() || player.hasPermission("uxmskyblock.admin.bypass")) {
            return;
        }
        org.bukkit.Location standing = player.getLocation();
        if (standing == null) {
            return;
        }
        Optional<Island> here = islands.findIslandAt(standing);
        if (here.isEmpty()) {
            return;
        }
        Optional<ProfileId> profile = islands.activeProfile(new PlayerUuid(player.getUniqueId()));
        if (profile.isPresent() && here.get().isMember(profile.get())) {
            return;
        }
        event.setCancelled(true);
        messages.send(player, "visitors.command_blocked", Placeholder.unparsed("command", blocked.get()));
    }
}
