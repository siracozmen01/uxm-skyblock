package com.uxplima.uxmskyblock.bukkit.state;

import java.util.Objects;
import java.util.function.Consumer;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

/**
 * Swaps what a player carries when they reach a world of another group, and keeps quiet the advancements a swap
 * hands back, which the player had already.
 *
 * <p>Folia moves a player between worlds without the world change event, so a teleport and a respawn into another
 * world each ask for the swap once the player has landed, and a sweep asks for every player every second. A join
 * asks for nothing: the session puts the player's state on them after it, and asks once it has. Between landing
 * and the swap what the player carries still belongs to the group they came from,
 * so nothing of it may leave their hands in the new world and nothing of the new world may reach them: no drop,
 * no container, no block or entity placed, nothing used on a block or an entity, no bucket emptied, no item picked
 * up and nothing thrown. A player who dies then keeps what they carry, which goes back to its own group.
 */
public final class PlayerStateListener implements Listener {

    private final PlayerStates states;
    private final Consumer<Player> soon;

    /** @param soon runs the swap for the player on their own thread a tick from now, once they have landed */
    public PlayerStateListener(PlayerStates states, Consumer<Player> soon) {
        this.states = Objects.requireNonNull(states, "states must not be null");
        this.soon = Objects.requireNonNull(soon, "soon must not be null");
    }

    /** Asks for the swap for {@code player}, on their own thread. What the sweep and {@link #soon} run. */
    public void arrive(Player player) {
        if (player.isOnline()) {
            states.arrived(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        states.arrived(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        states.left(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (event.getTo().getWorld() != null
                && !event.getTo().getWorld().equals(event.getFrom().getWorld())) {
            soon.accept(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        soon.accept(event.getPlayer());
    }

    /** Landed and not swapped yet: the item stays in hand and is put down with the rest. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        hold(event.getPlayer(), event);
    }

    /** Landed and not swapped yet: no container opens until what the player carries is swapped. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player) {
            hold(player, event);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        hold(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlaceEntity(EntityPlaceEvent event) {
        if (event.getPlayer() != null) {
            hold(event.getPlayer(), event);
        }
    }

    /** A cancelled click on air still reaches here, so this one is not skipped when cancelled. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        hold(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        hold(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteractAtEntity(PlayerInteractAtEntityEvent event) {
        hold(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent event) {
        hold(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent event) {
        hold(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player) {
            hold(player, event);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onThrow(ProjectileLaunchEvent event) {
        if (event.getEntity().getShooter() instanceof Player player) {
            hold(player, event);
        }
    }

    /** Landed and not swapped yet: nothing drops in the new world, and what they carry goes back to its group. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getPlayer();
        if (states.pending(player)) {
            event.setKeepInventory(true);
            event.getDrops().clear();
            event.setKeepLevel(true);
            event.setDroppedExp(0);
        }
    }

    /** Stops {@code event} while {@code player} waits for the swap, and asks for the swap again. */
    private void hold(Player player, Cancellable event) {
        if (states.pending(player)) {
            event.setCancelled(true);
            soon.accept(player);
        }
    }

    /** An advancement given back in a swap is not announced: nobody earned it just now. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        if (PlayerStates.isSwapping(event.getPlayer())) {
            event.message(null);
        }
    }
}
