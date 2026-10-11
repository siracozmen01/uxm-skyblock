package com.uxplima.uxmskyblock.bukkit.boundary;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkPos;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;
import com.uxplima.uxmskyblock.core.domain.wall.WallOutline;
import com.uxplima.uxmskyblock.core.domain.wall.WallSegment;

/**
 * The wall along an island's edge, for an island whose members chose a wall rather than the client's border.
 *
 * <p>Each chunk the edge runs through stands the stretch of wall that closes its own ground, when it loads. When an
 * island's choice changes, or the island grows, every chunk along its edge is drawn again. Members and visitors see
 * the same wall.
 */
public final class IslandEdgeWalls implements Listener {

    /** What a wall of the island's edge is marked with, apart from a ChunkBlock island's walls of the same island. */
    static final String PREFIX = "edge:";

    /** Where an island stands and how far it reaches, as its walls are drawn. */
    record Place(String world, IslandBounds bounds) {}

    private final IslandEdges edges;
    private final IslandProtectionListener islands;
    private final SchedulerPort scheduler;
    private final WallPainter painter;
    private final Function<IslandId, Optional<IslandLocation>> locationOf;
    private final Map<IslandId, Place> places = new ConcurrentHashMap<>();

    /** @param locationOf an island's location, read off the main thread */
    public IslandEdgeWalls(
            IslandEdges edges,
            IslandProtectionListener islands,
            SchedulerPort scheduler,
            WallPainter painter,
            Function<IslandId, Optional<IslandLocation>> locationOf) {
        this.edges = Objects.requireNonNull(edges, "edges must not be null");
        this.islands = Objects.requireNonNull(islands, "islands must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.painter = Objects.requireNonNull(painter, "painter must not be null");
        this.locationOf = Objects.requireNonNull(locationOf, "locationOf must not be null");
    }

    /** A chunk the edge of a walled island runs through stands its stretch, on its own thread. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        World world = event.getWorld();
        ChunkPos chunk = new ChunkPos(event.getChunk().getX(), event.getChunk().getZ());
        Optional<Island> island = islandIn(world, chunk);
        if (island.isEmpty() || edges.wallOf(island.get().id()).isEmpty()) {
            return;
        }
        IslandBounds bounds = island.get().bounds();
        if (segmentsIn(bounds, chunk).isEmpty()) {
            return;
        }
        Place place = new Place(world.getName(), bounds);
        places.put(island.get().id(), place);
        scheduler.onRegion(
                place.world(), chunk.x(), chunk.z(), () -> redraw(island.get().id(), place, chunk));
    }

    /** The island chose another way to draw its edge: every chunk along it is drawn again. */
    public void changed(IslandId islandId) {
        placeOf(islandId).ifPresent(place -> redrawAll(islandId, place, edgeChunks(place.bounds())));
    }

    /** The island grew from {@code before} to {@code after}: the old edge comes down, and the new one stands. */
    public void moved(IslandId islandId, String world, IslandBounds before, IslandBounds after) {
        Place place = new Place(world, after);
        places.put(islandId, place);
        Set<ChunkPos> chunks = new LinkedHashSet<>(edgeChunks(before));
        chunks.addAll(edgeChunks(after));
        redrawAll(islandId, place, chunks);
    }

    /** Forgets what was kept of an island that left memory. */
    public void forget(IslandId islandId) {
        places.remove(islandId);
    }

    /**
     * Takes down the island's edge wall in {@code chunk} and stands what it holds now, on the chunk's own thread. A
     * chunk that is not loaded is left: it stands its wall when it loads.
     */
    void redraw(IslandId islandId, Place place, ChunkPos chunk) {
        World world = Bukkit.getWorld(place.world());
        if (world == null || !world.isChunkLoaded(chunk.x(), chunk.z())) {
            return;
        }
        String owner = PREFIX + islandId.value();
        painter.clear(world.getChunkAt(chunk.x(), chunk.z()), owner);
        Optional<WallStyle> style = edges.wallOf(islandId);
        if (style.isEmpty()) {
            return;
        }
        for (WallSegment wall : segmentsIn(place.bounds(), chunk)) {
            painter.draw(world, owner, wall, style.get());
        }
    }

    /**
     * The stretches of the island's edge whose ground lies in {@code chunk}, cut at the chunk's sides. A wall belongs
     * to the chunk of the ground it closes, so it stands in that chunk and leaves with it.
     */
    static List<WallSegment> segmentsIn(IslandBounds bounds, ChunkPos chunk) {
        int minX = chunk.x() << 4;
        int minZ = chunk.z() << 4;
        List<WallSegment> inside = new ArrayList<>();
        for (WallSegment wall : WallOutline.of(bounds, Integer.MAX_VALUE)) {
            int ground =
                    switch (wall.side()) {
                        case NORTH, WEST -> wall.line();
                        case SOUTH, EAST -> wall.line() - 1;
                    };
            boolean alongX = wall.side().alongX();
            if ((ground >> 4) != (alongX ? chunk.z() : chunk.x())) {
                continue;
            }
            int low = alongX ? minX : minZ;
            int from = Math.max(wall.from(), low);
            int to = Math.min(wall.to(), low + 16);
            if (from < to) {
                inside.add(new WallSegment(wall.side(), wall.line(), from, to));
            }
        }
        return List.copyOf(inside);
    }

    /** Every chunk the edge of {@code bounds} runs through. */
    static Set<ChunkPos> edgeChunks(IslandBounds bounds) {
        Set<ChunkPos> chunks = new LinkedHashSet<>();
        for (int x = bounds.minX() >> 4; x <= bounds.maxX() >> 4; x++) {
            for (int z = bounds.minZ() >> 4; z <= bounds.maxZ() >> 4; z++) {
                boolean rim = x == bounds.minX() >> 4
                        || x == bounds.maxX() >> 4
                        || z == bounds.minZ() >> 4
                        || z == bounds.maxZ() >> 4;
                if (rim) {
                    chunks.add(new ChunkPos(x, z));
                }
            }
        }
        return chunks;
    }

    /** The island whose ground lies in {@code chunk}, looked for at its corners and its middle. */
    private Optional<Island> islandIn(World world, ChunkPos chunk) {
        int minX = chunk.x() << 4;
        int minZ = chunk.z() << 4;
        int[][] spots = {
            {minX + 8, minZ + 8}, {minX, minZ}, {minX + 15, minZ}, {minX, minZ + 15}, {minX + 15, minZ + 15}
        };
        for (int[] spot : spots) {
            Optional<Island> found = islands.findIslandAt(new Location(world, spot[0], 0, spot[1]));
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    private void redrawAll(IslandId islandId, Place place, Set<ChunkPos> chunks) {
        for (ChunkPos chunk : chunks) {
            scheduler.onRegion(place.world(), chunk.x(), chunk.z(), () -> redraw(islandId, place, chunk));
        }
    }

    private Optional<Place> placeOf(IslandId islandId) {
        Place known = places.get(islandId);
        if (known != null) {
            return Optional.of(known);
        }
        Optional<Place> read =
                locationOf.apply(islandId).map(location -> new Place(location.worldName(), location.bounds()));
        read.ifPresent(place -> places.put(islandId, place));
        return read;
    }
}
