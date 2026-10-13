package com.uxplima.uxmskyblock.bukkit.boundary;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import com.uxplima.uxmskyblock.bukkit.arrival.Arrival;
import com.uxplima.uxmskyblock.bukkit.arrival.ArrivalCause;
import com.uxplima.uxmskyblock.bukkit.arrival.ArrivalGate;
import com.uxplima.uxmskyblock.bukkit.arrival.ArrivalObserver;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.core.application.boundary.IslandBoundaryPoint;
import com.uxplima.uxmskyblock.core.application.boundary.IslandBoundaryService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;

/**
 * Enforces boundary physics shielding against fluid spillover, piston pushing across borders,
 * ender pearl boundary breaches, and manages virtual WorldBorder synchronization.
 */
public final class IslandBoundaryListener implements Listener, ArrivalGate, ArrivalObserver {

    private static final Particle.DustOptions PERIMETER_DUST =
            new Particle.DustOptions(Color.fromRGB(0, 220, 255), 1.0f);

    private final IslandBoundaryService boundaryService;
    private final IslandProtectionListener protectionListener;
    private final Messages messages;
    private volatile boolean stopBorderCrossing = false;
    /**
     * The island and world whose border each player was last shown, so a ride or a step inside it sends
     * nothing. The world counts because a player's own border is gone once they change worlds.
     */
    private final java.util.Map<java.util.UUID, Shown> bordered = new java.util.concurrent.ConcurrentHashMap<>();

    private record Shown(IslandId island, java.util.UUID world) {}

    /** Whether an island is edged by the border a client draws. One that is not shows its edge some other way. */
    private volatile java.util.function.Predicate<IslandId> drawsBorder = island -> true;

    /** The islands whose pearls a rule of their own game mode judges, as a Boxed island's box does. */
    private volatile java.util.function.Predicate<IslandId> pearlsJudgedElsewhere = island -> false;

    /** Leaves a pearl thrown from one of these islands to the rule of its game mode. */
    public void leavePearlsOf(java.util.function.Predicate<IslandId> islands) {
        this.pearlsJudgedElsewhere = Objects.requireNonNull(islands, "islands must not be null");
    }

    /** Tells the listener which islands the client's border edges, and which show their edge some other way. */
    public void useBorderFor(java.util.function.Predicate<IslandId> drawsBorder) {
        this.drawsBorder = Objects.requireNonNull(drawsBorder, "drawsBorder must not be null");
    }

    public IslandBoundaryListener(
            IslandBoundaryService boundaryService, IslandProtectionListener protectionListener, Messages messages) {
        this.boundaryService = Objects.requireNonNull(boundaryService, "boundaryService must not be null");
        this.protectionListener = Objects.requireNonNull(protectionListener, "protectionListener must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
    }

    public IslandBoundaryListener(
            IslandBoundaryService boundaryService,
            IslandProtectionListener protectionListener,
            @SuppressWarnings("unused") SchedulerPort schedulerPort,
            Messages messages) {
        this(boundaryService, protectionListener, messages);
    }

    public void setStopBorderCrossing(boolean stopBorderCrossing) {
        this.stopBorderCrossing = stopBorderCrossing;
    }

    public boolean isStopBorderCrossing() {
        return stopBorderCrossing;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockFromTo(BlockFromToEvent event) {
        Location from = event.getBlock().getLocation();
        Location to = event.getToBlock().getLocation();
        if (from.getBlockX() == to.getBlockX() && from.getBlockZ() == to.getBlockZ()) {
            return;
        }

        Optional<Island> fromIsland = protectionListener.findIslandAt(from);
        if (fromIsland.isPresent()) {
            IslandBounds bounds = fromIsland.get().bounds();
            if (boundaryService.isSpillover(
                    bounds, from.getBlockX(), from.getBlockZ(), to.getBlockX(), to.getBlockZ())) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        Block piston = event.getBlock();
        BlockFace direction = event.getDirection();
        Optional<Island> optIsland = protectionListener.findIslandAt(piston.getLocation());
        if (optIsland.isEmpty()) {
            return;
        }

        IslandBounds bounds = optIsland.get().bounds();
        for (Block block : event.getBlocks()) {
            int toX = block.getX() + direction.getModX();
            int toZ = block.getZ() + direction.getModZ();
            if (boundaryService.isSpillover(bounds, block.getX(), block.getZ(), toX, toZ)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (!event.isSticky()) {
            return;
        }
        Block piston = event.getBlock();
        BlockFace direction = event.getDirection();
        Optional<Island> optIsland = protectionListener.findIslandAt(piston.getLocation());
        if (optIsland.isEmpty()) {
            return;
        }

        IslandBounds bounds = optIsland.get().bounds();
        for (Block block : event.getBlocks()) {
            int toX = block.getX() + direction.getModX();
            int toZ = block.getZ() + direction.getModZ();
            if (boundaryService.isSpillover(bounds, block.getX(), block.getZ(), toX, toZ)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    /**
     * A pearl thrown from an island does not carry its thrower off it. Folia announces no pearl
     * teleport, so this answers the arrival watch, which sends the thrower back where it cannot stop
     * the landing.
     */
    @Override
    public boolean refuses(Arrival arrival) {
        Location from = arrival.from();
        if (arrival.cause() != ArrivalCause.PEARL || from == null) {
            return false;
        }
        Optional<Island> thrownFrom = protectionListener.findIslandAt(from);
        if (thrownFrom.isPresent()
                && !pearlsJudgedElsewhere.test(thrownFrom.get().id())
                && protectionListener.findIslandAt(arrival.to()).isEmpty()) {
            messages.send(arrival.player(), "navigation.pearl_blocked");
            return true;
        }
        return false;
    }

    /**
     * The border of the island a player arrives on is drawn for them, and taken away when they arrive
     * where no island is. Nearly every arrival on an island is a teleport, home, a visit or a warp, and
     * the border used to follow only a walk across the edge, so it was almost never shown.
     */
    @Override
    public void arrived(Arrival arrival) {
        showBorder(arrival.player(), arrival.to(), protectionListener.findIslandAt(arrival.to()));
    }

    private void showBorder(Player player, Location at, Optional<Island> island) {
        java.util.UUID id = player.getUniqueId();
        PlayerUuid playerUuid = new PlayerUuid(id);
        if (island.isPresent()
                && at.getWorld() != null
                && drawsBorder.test(island.get().id())) {
            Shown shown = new Shown(island.get().id(), at.getWorld().getUID());
            if (!shown.equals(bordered.put(id, shown))) {
                boundaryService.handlePlayerEnterIsland(playerUuid, island.get().bounds());
            }
        } else if (bordered.remove(id) != null) {
            boundaryService.handlePlayerExitIsland(playerUuid);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || (from.getBlockX() == to.getBlockX() && from.getBlockZ() == to.getBlockZ())) {
            return;
        }

        Optional<Island> fromIsland = protectionListener.findIslandAt(from);
        Optional<Island> toIsland = protectionListener.findIslandAt(to);

        if (fromIsland.isPresent()
                && toIsland.isEmpty()
                && stopBorderCrossing
                && !event.getPlayer().hasPermission("uxmskyblock.admin.bypass")) {
            event.setCancelled(true);
            messages.send(event.getPlayer(), "navigation.void_blocked");
            return;
        }
        showBorder(event.getPlayer(), to, toIsland);
    }

    /** A player who hid the border before finds it hidden, before any island draws it for them. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (BorderChoice.hidden(event.getPlayer())) {
            boundaryService.hideBorder(new PlayerUuid(event.getPlayer().getUniqueId()));
        }
    }

    /**
     * Draws the edge where {@code player} stands again, from nothing: an island that changed how its edge is drawn is
     * shown the new way at once. Runs on the player's own thread.
     */
    public void refresh(Player player) {
        Location at = player.getLocation();
        if (at == null) {
            return;
        }
        if (bordered.remove(player.getUniqueId()) != null) {
            boundaryService.handlePlayerExitIsland(new PlayerUuid(player.getUniqueId()));
        }
        showBorder(player, at, protectionListener.findIslandAt(at));
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        PlayerUuid uuid = new PlayerUuid(event.getPlayer().getUniqueId());
        bordered.remove(event.getPlayer().getUniqueId());
        boundaryService.disablePerimeter(uuid);
        boundaryService.forget(uuid);
    }

    /**
     * Renders particle perimeter outline for a specific player if they have border projection enabled.
     */
    public void renderPerimeterForPlayer(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        PlayerUuid uuid = new PlayerUuid(player.getUniqueId());
        if (!boundaryService.isPerimeterActive(uuid)) {
            return;
        }

        Location loc = player.getLocation();
        if (loc == null) {
            return;
        }
        Optional<Island> optIsland = protectionListener.findIslandAt(loc);
        if (optIsland.isEmpty()) {
            return;
        }

        IslandBounds bounds = optIsland.get().bounds();
        double playerY = loc.getY();
        double playerX = loc.getX();
        double playerZ = loc.getZ();

        List<IslandBoundaryPoint> points = boundaryService.calculatePerimeterPoints(bounds, playerY, 2);
        for (IslandBoundaryPoint pt : points) {
            double dx = pt.x() - playerX;
            double dz = pt.z() - playerZ;
            // Only spawn particles within 48 blocks of player for performance
            if (dx * dx + dz * dz <= 2304.0) {
                player.spawnParticle(Particle.DUST, pt.x(), pt.y(), pt.z(), 1, 0.0, 0.0, 0.0, 0.0, PERIMETER_DUST);
                // Also spawn at eye height + 1 block for visibility
                player.spawnParticle(
                        Particle.DUST, pt.x(), pt.y() + 1.2, pt.z(), 1, 0.0, 0.0, 0.0, 0.0, PERIMETER_DUST);
            }
        }
    }
}
