package com.uxplima.uxmskyblock.bukkit.state;

import java.util.Objects;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Swaps what a player carries when they change worlds or join in a world of another group, and keeps quiet the
 * advancements a swap hands back, which the player had already.
 */
public final class PlayerStateListener implements Listener {

    private final PlayerStates states;

    public PlayerStateListener(PlayerStates states) {
        this.states = Objects.requireNonNull(states, "states must not be null");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        states.arrived(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        states.arrived(event.getPlayer());
    }

    /** An advancement given back in a swap is not announced: nobody earned it just now. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        if (PlayerStates.isSwapping(event.getPlayer())) {
            event.message(null);
        }
    }
}
