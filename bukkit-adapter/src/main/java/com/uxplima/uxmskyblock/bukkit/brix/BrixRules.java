package com.uxplima.uxmskyblock.bukkit.brix;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntSupplier;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;

import com.uxplima.uxmskyblock.bukkit.config.BrixConfiguration;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.core.application.brix.BrixService;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import org.jspecify.annotations.Nullable;

/**
 * What cannot happen on a plot: nothing hurts, nobody goes hungry, no creature comes of itself, no item lies on the
 * ground and no
 * ender chest opens. The last two keep what was made in creative on the plot, where a thrown item or an
 * ender chest would carry it off. A player whose items are kept aside is held to the same off the plot
 * by {@link com.uxplima.uxmskyblock.bukkit.creative.SealedInventoryGuard}.
 *
 * <p>Each handler runs on the thread that owns the place the event happens in, and every answer about
 * the plot comes from memory.
 */
public final class BrixRules implements Listener {

    private final BrixService service;
    private final IslandProtectionListener islands;
    private final BrixConfiguration.Rules rules;

    /** The ways a creature comes of itself. */
    private static final Set<CreatureSpawnEvent.SpawnReason> UNBIDDEN = EnumSet.of(
            CreatureSpawnEvent.SpawnReason.NATURAL,
            CreatureSpawnEvent.SpawnReason.JOCKEY,
            CreatureSpawnEvent.SpawnReason.MOUNT,
            CreatureSpawnEvent.SpawnReason.PATROL,
            CreatureSpawnEvent.SpawnReason.RAID,
            CreatureSpawnEvent.SpawnReason.REINFORCEMENTS,
            CreatureSpawnEvent.SpawnReason.VILLAGE_INVASION,
            CreatureSpawnEvent.SpawnReason.TRAP);

    private final IntSupplier spacing;
    private final int groundRadius;

    /** Rules that know of no ground laid past a plot's bounds. */
    public BrixRules(BrixService service, IslandProtectionListener islands, BrixConfiguration.Rules rules) {
        this(service, islands, rules, () -> 0, 0);
    }

    /**
     * @param spacing how far apart the islands' centres are on the grid, or 0 for no grid
     * @param groundRadius how far a plot's ground reaches from its centre, past its bounds too
     */
    public BrixRules(
            BrixService service,
            IslandProtectionListener islands,
            BrixConfiguration.Rules rules,
            IntSupplier spacing,
            int groundRadius) {
        this.service = Objects.requireNonNull(service, "service must not be null");
        this.islands = Objects.requireNonNull(islands, "islands must not be null");
        this.rules = Objects.requireNonNull(rules, "rules must not be null");
        this.spacing = Objects.requireNonNull(spacing, "spacing must not be null");
        this.groundRadius = groundRadius;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        // The void is left to hurt: a player falling for ever is worse than one who fell.
        if (rules.noDamage()
                && event.getEntity() instanceof Player player
                && event.getCause() != EntityDamageEvent.DamageCause.VOID
                && onPlot(player.getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHunger(FoodLevelChangeEvent event) {
        if (rules.noHunger()
                && event.getFoodLevel() < event.getEntity().getFoodLevel()
                && onPlot(event.getEntity().getLocation())) {
            event.setCancelled(true);
        }
    }

    /**
     * No creature comes of itself onto a plot: not out of the dark, not with a patrol or a raid. One a player brings,
     * from an egg, a spawner, a command or by breeding, comes as it would.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        if (rules.noSpawning()
                && UNBIDDEN.contains(event.getSpawnReason())
                && (onPlot(event.getLocation()) || onGround(event.getLocation()))) {
            event.setCancelled(true);
        }
    }

    /**
     * Whether a place is on a plot's ground past its bounds: the ground is laid wider than a new plot's border, and a
     * creature come of itself there walks in. The plot is the island at the grid point nearest the place.
     */
    private boolean onGround(Location at) {
        int every = spacing.getAsInt();
        if (every <= 0 || at.getWorld() == null) {
            return false;
        }
        int x = at.getBlockX();
        int z = at.getBlockZ();
        int pointX = Math.toIntExact(Math.round((double) x / every) * every);
        int pointZ = Math.toIntExact(Math.round((double) z / every) * every);
        Optional<Island> island = islands.findIslandAt(at.getWorld().getName(), pointX, pointZ);
        return island.isPresent()
                && service.isPlot(island.get().id())
                && Math.abs(x - island.get().bounds().centerX()) <= groundRadius
                && Math.abs(z - island.get().bounds().centerZ()) <= groundRadius;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onThrow(PlayerDropItemEvent event) {
        if (onPlot(event.getPlayer().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onItem(ItemSpawnEvent event) {
        if (onPlot(event.getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEnderChest(InventoryOpenEvent event) {
        if (event.getInventory().getType() == InventoryType.ENDER_CHEST
                && event.getPlayer() instanceof Player player
                && onPlot(player.getLocation())) {
            event.setCancelled(true);
        }
    }

    private boolean onPlot(@Nullable Location at) {
        if (at == null) {
            return false;
        }
        Optional<Island> island = islands.findIslandAt(at);
        return island.isPresent() && service.isPlot(island.get().id());
    }
}
