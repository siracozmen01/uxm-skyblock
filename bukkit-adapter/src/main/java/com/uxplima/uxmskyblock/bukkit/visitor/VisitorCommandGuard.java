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

    public VisitorCommandGuard(
            Supplier<VisitorsConfiguration> configuration, IslandProtectionListener islands, Messages messages) {
        this.configuration = Objects.requireNonNull(configuration, "configuration must not be null");
        this.islands = Objects.requireNonNull(islands, "islands must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        Optional<String> blocked = configuration.get().blocking(event.getMessage());
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
