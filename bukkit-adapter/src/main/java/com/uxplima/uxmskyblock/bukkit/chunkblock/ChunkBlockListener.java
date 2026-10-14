package com.uxplima.uxmskyblock.bukkit.chunkblock;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import com.uxplima.uxmskyblock.bukkit.arrival.Arrival;
import com.uxplima.uxmskyblock.bukkit.arrival.ArrivalCause;
import com.uxplima.uxmskyblock.bukkit.arrival.ArrivalGate;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.core.application.chunkblock.ChunkBlockService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkPos;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;

/**
 * The edge of a ChunkBlock island's territory: nobody walks, builds, breaks or uses a block in a closed
 * chunk, and whoever stands in a chunk as it closes is moved to the island's spawn.
 *
 * <p>Every answer comes from memory, the island at the spot and its territory, so no step waits on the
 * database. Nothing built in a closed chunk is touched: it waits there for the chunk to open again.
 */
public final class ChunkBlockListener implements Listener, ArrivalGate {

    /** How often one player is told a chunk is closed, so walking along the edge is not a flood. */
    private static final long TOLD_EVERY_MILLIS = 2_000;

    private final ChunkBlockService service;
    private final IslandProtectionListener islands;

    /** The worlds whose ground this guards; another dimension's web guards the rest. */
    private volatile java.util.function.Predicate<String> worlds = world -> true;

    private final SchedulerPort scheduler;
    private final Messages messages;
    private final Function<IslandId, Optional<IslandLocation>> locations;
    private final String bypassPermission;
    private final Map<UUID, Long> lastTold = new ConcurrentHashMap<>();
    private final java.util.Set<UUID> puttingOut = ConcurrentHashMap.newKeySet();

    public ChunkBlockListener(
            ChunkBlockService service,
            IslandProtectionListener islands,
            SchedulerPort scheduler,
            Messages messages,
            Function<IslandId, Optional<IslandLocation>> locations,
            String bypassPermission) {
        this.service = Objects.requireNonNull(service, "service must not be null");
        this.islands = Objects.requireNonNull(islands, "islands must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
        this.locations = Objects.requireNonNull(locations, "locations must not be null");
        this.bypassPermission = Objects.requireNonNull(bypassPermission, "bypassPermission must not be null");
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        refuseAt(event.getPlayer(), event.getBlock().getLocation(), event);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        refuseAt(event.getPlayer(), event.getBlockPlaced().getLocation(), event);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent event) {
        Block clicked = event.getClickedBlock();
        if (clicked != null) {
            refuseAt(event.getPlayer(), clicked.getLocation(), event);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent event) {
        refuseAt(event.getPlayer(), event.getBlock().getLocation(), event);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        // Folia fires no teleport event for an asynchronous teleport, so a player can arrive in a closed
        // chunk by a teleport, or log in where a chunk has closed since. Their first step takes them out.
        if (standsInClosed(event.getPlayer(), from)) {
            event.setCancelled(true);
            putOut(event.getPlayer(), from);
            return;
        }
        if ((from.getBlockX() >> 4) == (to.getBlockX() >> 4) && (from.getBlockZ() >> 4) == (to.getBlockZ() >> 4)) {
            return;
        }
        refuseAt(event.getPlayer(), to, event);
    }

    /**
     * Nobody arrives in a closed chunk, however they came. Folia announces no teleport, so this answers
     * the arrival watch rather than a teleport event. A login or a respawn in a closed chunk has no
     * earlier place to go back to, so it is put out to the island's spawn instead.
     */
    @Override
    public boolean refuses(Arrival arrival) {
        Player player = arrival.player();
        Location to = arrival.to();
        boolean nowhereToGoBack =
                !arrival.before() && (arrival.cause() == ArrivalCause.JOIN || arrival.cause() == ArrivalCause.RESPAWN);
        if (nowhereToGoBack) {
            if (standsInClosed(player, to)) {
                putOut(player, to);
            }
            return false;
        }
        return refuses(player, to);
    }

    /**
     * Moves whoever stands in one of {@code closed} of this island to its spawn. Called off the main
     * thread when a fallen level has closed them; each player is looked at on their own thread.
     */
    public void moveOut(IslandId islandId, List<ChunkPos> closed) {
        Optional<IslandLocation> home = locations.apply(islandId);
        if (home.isEmpty() || closed.isEmpty()) {
            return;
        }
        IslandLocation spawn = home.get();
        for (Player player : Bukkit.getOnlinePlayers()) {
            scheduler.onEntity(new PlayerUuid(player.getUniqueId()), () -> {
                Location at = player.getLocation();
                if (!player.isOnline() || at == null || !standsIn(at, islandId, closed)) {
                    return;
                }
                World world = Bukkit.getWorld(spawn.worldName());
                if (world == null) {
                    return;
                }
                var unused = player.teleportAsync(new Location(
                        world, spawn.spawnX(), spawn.spawnY(), spawn.spawnZ(), spawn.spawnYaw(), spawn.spawnPitch()));
                messages.send(player, "chunkblock.moved_out");
            });
        }
    }

    private boolean standsInClosed(Player player, Location location) {
        Optional<Island> island = islandAt(location);
        return island.isPresent()
                && !service.isOpen(island.get().id(), ChunkPos.ofBlock(location.getBlockX(), location.getBlockZ()))
                        .orElse(true)
                && (bypassPermission.isEmpty() || !player.hasPermission(bypassPermission));
    }

    /** Sends a player found in a closed chunk to the island's spawn, once until they are there. */
    private void putOut(Player player, Location at) {
        Optional<Island> island = islandAt(at);
        if (island.isEmpty() || !puttingOut.add(player.getUniqueId())) {
            return;
        }
        IslandId islandId = island.get().id();
        scheduler.async(() -> {
            Optional<IslandLocation> home = locations.apply(islandId);
            scheduler.onEntity(new PlayerUuid(player.getUniqueId()), () -> {
                World world =
                        home.map(spawn -> Bukkit.getWorld(spawn.worldName())).orElse(null);
                if (home.isEmpty() || world == null || !player.isOnline()) {
                    puttingOut.remove(player.getUniqueId());
                    return;
                }
                IslandLocation spawn = home.get();
                var unused = player.teleportAsync(new Location(
                                world,
                                spawn.spawnX(),
                                spawn.spawnY(),
                                spawn.spawnZ(),
                                spawn.spawnYaw(),
                                spawn.spawnPitch()))
                        .whenComplete((moved, failure) -> puttingOut.remove(player.getUniqueId()));
                messages.send(player, "chunkblock.put_out");
            });
        });
    }

    private boolean standsIn(Location location, IslandId islandId, List<ChunkPos> closed) {
        Optional<Island> island = islandAt(location);
        return island.isPresent()
                && island.get().id().equals(islandId)
                && closed.contains(ChunkPos.ofBlock(location.getBlockX(), location.getBlockZ()));
    }

    private void refuseAt(Player player, Location location, Cancellable event) {
        if (refuses(player, location)) {
            event.setCancelled(true);
        }
    }

    /** Whether {@code location} is in a closed chunk this player may not enter, telling them so if it is. */
    private boolean refuses(Player player, Location location) {
        Optional<Island> island = islandAt(location);
        if (island.isEmpty()) {
            return false;
        }
        ChunkPos chunk = ChunkPos.ofBlock(location.getBlockX(), location.getBlockZ());
        boolean open = service.isOpen(island.get().id(), chunk).orElse(true);
        if (open || (!bypassPermission.isEmpty() && player.hasPermission(bypassPermission))) {
            return false;
        }
        long now = System.currentTimeMillis();
        Long told = lastTold.get(player.getUniqueId());
        if (told == null || now - told >= TOLD_EVERY_MILLIS) {
            lastTold.put(player.getUniqueId(), now);
            long next = service.inMemory(island.get().id())
                    .map(territory -> territory.nextRequirement(service.rules()))
                    .orElse(0L);
            messages.send(player, "chunkblock.closed", Placeholder.unparsed("island_level", Long.toString(next)));
        }
        return true;
    }

    /** Guards only the worlds {@code guarded} names, so each dimension's web answers in its own worlds alone. */
    public void guarding(java.util.function.Predicate<String> guarded) {
        this.worlds = Objects.requireNonNull(guarded, "guarded must not be null");
    }

    /** The island at {@code at}, in a world this guards. */
    private Optional<Island> islandAt(Location at) {
        org.bukkit.World world = at.getWorld();
        return world == null || !worlds.test(world.getName()) ? Optional.empty() : islands.findIslandAt(at);
    }
}
