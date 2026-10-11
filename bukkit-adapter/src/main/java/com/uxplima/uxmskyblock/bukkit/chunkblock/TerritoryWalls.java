package com.uxplima.uxmskyblock.bukkit.chunkblock;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;

import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.wall.WallPainter;
import com.uxplima.uxmskyblock.bukkit.wall.WallStyle;
import com.uxplima.uxmskyblock.core.application.chunkblock.ChunkBlockService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkPos;
import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkTerritory;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.wall.WallOutline;
import com.uxplima.uxmskyblock.core.domain.wall.WallSegment;

/**
 * The wall around a ChunkBlock island's open chunks, standing on every side an open chunk shares with a closed one.
 *
 * <p>A ChunkBlock island grows into any shape, and the border a client draws is one square, so a player saw
 * nothing where the open ground ended and was stopped at an edge they could not see. Each open chunk now stands the
 * walls of its own closed sides. They are drawn when the chunk loads, and drawn again in the chunk and its
 * neighbours when a chunk opens or closes. Members and visitors see the same wall.
 */
public final class TerritoryWalls implements Listener {

    private final ChunkBlockService service;
    private final IslandProtectionListener islands;
    private final SchedulerPort scheduler;
    private final WallPainter panels;
    private final WallStyle style;
    private final Function<IslandId, Optional<String>> worldOf;

    /**
     * @param worldOf the world an island stands in, read off the island's location
     */
    public TerritoryWalls(
            ChunkBlockService service,
            IslandProtectionListener islands,
            SchedulerPort scheduler,
            WallPainter panels,
            WallStyle style,
            Function<IslandId, Optional<String>> worldOf) {
        this.service = Objects.requireNonNull(service, "service must not be null");
        this.islands = Objects.requireNonNull(islands, "islands must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.panels = Objects.requireNonNull(panels, "panels must not be null");
        this.style = Objects.requireNonNull(style, "style must not be null");
        this.worldOf = Objects.requireNonNull(worldOf, "worldOf must not be null");
    }

    /** A chunk of a ChunkBlock island that loads stands its walls, a tick later on its own thread. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        World world = event.getWorld();
        int chunkX = event.getChunk().getX();
        int chunkZ = event.getChunk().getZ();
        Optional<Island> island = islands.findIslandAt(new Location(world, (chunkX << 4) + 8, 0, (chunkZ << 4) + 8));
        if (island.isEmpty() || service.inMemory(island.get().id()).isEmpty()) {
            return;
        }
        IslandId islandId = island.get().id();
        String worldName = world.getName();
        scheduler.onRegion(worldName, chunkX, chunkZ, () -> redraw(worldName, islandId, new ChunkPos(chunkX, chunkZ)));
    }

    /** A chunk opened: it stands its walls, and its open neighbours take down the one they shared with it. */
    public void opened(IslandId islandId, ChunkPos chunk) {
        worldOf.apply(islandId).ifPresent(world -> {
            for (ChunkPos each : withNeighbours(List.of(chunk))) {
                scheduler.onRegion(world, each.x(), each.z(), () -> redraw(world, islandId, each));
            }
        });
    }

    /** Chunks closed: their walls come down, and their open neighbours stand one where they meet. */
    public void closed(IslandId islandId, List<ChunkPos> chunks) {
        worldOf.apply(islandId).ifPresent(world -> {
            for (ChunkPos each : withNeighbours(chunks)) {
                scheduler.onRegion(world, each.x(), each.z(), () -> redraw(world, islandId, each));
            }
        });
    }

    /**
     * Takes down the island's walls in {@code chunk} and stands the ones it holds now, on the chunk's own thread. A
     * chunk that is not loaded is left: it stands its walls when it loads.
     */
    void redraw(String worldName, IslandId islandId, ChunkPos chunk) {
        World world = Bukkit.getWorld(worldName);
        if (world == null || !world.isChunkLoaded(chunk.x(), chunk.z())) {
            return;
        }
        String owner = islandId.value().toString();
        panels.clear(world.getChunkAt(chunk.x(), chunk.z()), owner);
        Optional<ChunkTerritory> territory = service.inMemory(islandId);
        if (territory.isEmpty()) {
            return;
        }
        for (WallSegment wall : WallOutline.of(chunk, territory.get().open())) {
            panels.draw(world, owner, wall, style);
        }
    }

    private static Set<ChunkPos> withNeighbours(List<ChunkPos> chunks) {
        Set<ChunkPos> all = new LinkedHashSet<>();
        for (ChunkPos chunk : chunks) {
            all.add(chunk);
            all.addAll(chunk.neighbours());
        }
        return all;
    }
}
