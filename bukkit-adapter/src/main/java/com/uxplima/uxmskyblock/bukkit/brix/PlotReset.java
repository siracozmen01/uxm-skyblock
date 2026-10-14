package com.uxplima.uxmskyblock.bukkit.brix;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import com.uxplima.uxmskyblock.bukkit.config.BrixConfiguration;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.core.application.brix.BrixService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;

/**
 * {@code /is plot reset}: a plot's owner clears everything built on it and has its ground laid again under its spawn,
 * after saying so twice. The plot keeps its team, its sky and its standing; only what stands in its bounds goes.
 *
 * <p>Each chunk of the plot is cleared on the thread of the region that owns it: every block from the world's floor to
 * its roof, and every thing that is not a player. The ground is laid after, in the same task, and anyone standing on
 * the plot is sent to its spawn.
 */
public final class PlotReset {

    /** How long a player has to say so again. */
    public static final Duration CONFIRM_WITHIN = Duration.ofSeconds(30);

    private static final Logger LOGGER = Logger.getLogger(PlotReset.class.getName());

    private final BrixService plots;
    private final IslandProtectionListener islands;
    private final Function<IslandId, Optional<IslandLocation>> locations;
    private final SchedulerPort scheduler;
    private final BrixConfiguration.Ground ground;
    private final Messages messages;
    private final Clock clock;
    private final Map<UUID, Asked> asked = new ConcurrentHashMap<>();

    private record Asked(IslandId plot, Instant until) {}

    /** @param locations an island's location by its id; off the main thread, as it may read a row */
    public PlotReset(
            BrixService plots,
            IslandProtectionListener islands,
            Function<IslandId, Optional<IslandLocation>> locations,
            SchedulerPort scheduler,
            BrixConfiguration.Ground ground,
            Messages messages,
            Clock clock) {
        this.plots = Objects.requireNonNull(plots, "plots must not be null");
        this.islands = Objects.requireNonNull(islands, "islands must not be null");
        this.locations = Objects.requireNonNull(locations, "locations must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.ground = Objects.requireNonNull(ground, "ground must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /** The first word: the owner is asked to say so again. */
    public void ask(Player player) {
        Optional<Island> plot = ownPlot(player);
        if (plot.isEmpty()) {
            return;
        }
        asked.put(
                player.getUniqueId(), new Asked(plot.get().id(), clock.instant().plus(CONFIRM_WITHIN)));
        messages.send(player, "brix.reset.confirm");
    }

    /** The second word: the plot asked of is reset, when it is the one the owner stands on and asked in time. */
    public void confirm(Player player) {
        Optional<Island> plot = ownPlot(player);
        if (plot.isEmpty()) {
            return;
        }
        Asked was = asked.remove(player.getUniqueId());
        if (was == null
                || !was.plot().equals(plot.get().id())
                || clock.instant().isAfter(was.until())) {
            messages.send(player, "brix.reset.not_asked");
            return;
        }
        Island reset = plot.get();
        scheduler.async(() -> {
            Optional<IslandLocation> location;
            try {
                location = locations.apply(reset.id());
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "The spawn of Brix plot " + reset.id() + " could not be read.", e);
                location = Optional.empty();
            }
            if (location.isEmpty()) {
                scheduler.onEntity(
                        PlayerUuid.of(player.getUniqueId()), () -> messages.send(player, "brix.reset.failed"));
                return;
            }
            IslandLocation at = location.get();
            // On the owner's thread, where Bukkit may be asked: the plot's own world, from its record, never the
            // world the owner happens to stand in, where the same bounds may be another island's dimension.
            scheduler.onEntity(PlayerUuid.of(player.getUniqueId()), () -> {
                World world = Bukkit.getWorld(at.worldName());
                if (world == null) {
                    messages.send(player, "brix.reset.failed");
                    return;
                }
                int chunks = clear(world, reset.bounds(), at);
                messages.send(player, "brix.reset.done");
                LOGGER.fine(() -> "Brix plot " + reset.id() + " was reset over " + chunks + " chunks.");
            });
        });
    }

    /**
     * Clears the plot's bounds chunk by chunk, each on its region's thread, laying the ground under the spawn and
     * sending anyone there to it. Answers how many chunks were handed out.
     */
    int clear(World world, IslandBounds bounds, IslandLocation spawn) {
        int top = (int) Math.floor(spawn.spawnY()) - 1;
        List<Material> layers = ground.layers();
        int groundMinX = bounds.centerX() - ground.radius();
        int groundMaxX = bounds.centerX() + ground.radius();
        int groundMinZ = bounds.centerZ() - ground.radius();
        int groundMaxZ = bounds.centerZ() + ground.radius();
        Location arrival = new Location(
                world, spawn.spawnX(), spawn.spawnY(), spawn.spawnZ(), spawn.spawnYaw(), spawn.spawnPitch());
        int chunks = 0;
        for (int chunkX = bounds.minX() >> 4; chunkX <= bounds.maxX() >> 4; chunkX++) {
            for (int chunkZ = bounds.minZ() >> 4; chunkZ <= bounds.maxZ() >> 4; chunkZ++) {
                int fromX = Math.max(bounds.minX(), chunkX << 4);
                int toX = Math.min(bounds.maxX(), (chunkX << 4) + 15);
                int fromZ = Math.max(bounds.minZ(), chunkZ << 4);
                int toZ = Math.min(bounds.maxZ(), (chunkZ << 4) + 15);
                int cx = chunkX;
                int cz = chunkZ;
                scheduler.onRegion(world.getName(), chunkX, chunkZ, () -> {
                    wipe(world, cx, cz, fromX, toX, fromZ, toZ, arrival);
                    if (!layers.isEmpty()) {
                        PlotStart.lay(
                                world,
                                Math.max(fromX, groundMinX),
                                Math.min(toX, groundMaxX),
                                Math.max(fromZ, groundMinZ),
                                Math.min(toZ, groundMaxZ),
                                top,
                                layers);
                    }
                });
                chunks++;
            }
        }
        return chunks;
    }

    private static void wipe(
            World world, int chunkX, int chunkZ, int fromX, int toX, int fromZ, int toZ, Location arrival) {
        for (int x = fromX; x <= toX; x++) {
            for (int z = fromZ; z <= toZ; z++) {
                for (int y = world.getMinHeight(); y < world.getMaxHeight(); y++) {
                    Block block = world.getBlockAt(x, y, z);
                    if (!block.getType().isAir()) {
                        // No physics: nothing falls or flows into a column not yet cleared.
                        block.setType(Material.AIR, false);
                    }
                }
            }
        }
        for (Entity entity : world.getChunkAt(chunkX, chunkZ).getEntities()) {
            Location at = entity.getLocation();
            if (at.getBlockX() < fromX || at.getBlockX() > toX || at.getBlockZ() < fromZ || at.getBlockZ() > toZ) {
                continue;
            }
            if (entity instanceof Player standing) {
                standing.teleportAsync(arrival).exceptionally(failure -> {
                    LOGGER.log(
                            Level.WARNING,
                            failure,
                            () -> standing.getName() + " could not be sent to the plot's spawn.");
                    return false;
                });
            } else {
                entity.remove();
            }
        }
    }

    /** The plot the player stands on, when they own it; having told them why not otherwise. */
    private Optional<Island> ownPlot(Player player) {
        Location at = player.getLocation();
        Optional<Island> island = at == null ? Optional.empty() : islands.findIslandAt(at);
        if (island.isEmpty() || !plots.isPlot(island.get().id())) {
            messages.send(player, "brix.not_plot");
            return Optional.empty();
        }
        if (!island.get().ownerPlayerUuid().equals(PlayerUuid.of(player.getUniqueId()))) {
            messages.send(player, "brix.reset.not_owner");
            return Optional.empty();
        }
        return island;
    }
}
