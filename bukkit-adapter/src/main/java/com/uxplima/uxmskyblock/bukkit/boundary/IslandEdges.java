package com.uxplima.uxmskyblock.bukkit.boundary;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Predicate;

import com.uxplima.uxmskyblock.bukkit.wall.WallConfiguration;
import com.uxplima.uxmskyblock.bukkit.wall.WallStyle;
import com.uxplima.uxmskyblock.core.application.boundary.IslandEdgePort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/**
 * How each island's edge is drawn: the square border a client draws, a wall of a style {@code walls.conf} names, or
 * nothing. Its members choose, and an island that never chose keeps the server's default. An island that edges its
 * own ground some other way, as a ChunkBlock island does, draws neither border nor wall at its edge.
 *
 * <p>Every choice is held in memory, read once at start, so drawing an edge never asks the database.
 */
public final class IslandEdges {

    private final IslandEdgePort port;
    private final WallConfiguration walls;
    private final Predicate<IslandId> ownEdge;
    private final Map<IslandId, String> chosen = new ConcurrentHashMap<>();
    private final List<Consumer<IslandId>> changed = new CopyOnWriteArrayList<>();

    /** @param ownEdge whether an island edges its ground some other way, and so draws nothing at its edge */
    public IslandEdges(IslandEdgePort port, WallConfiguration walls, Predicate<IslandId> ownEdge) {
        this.port = Objects.requireNonNull(port, "port must not be null");
        this.walls = Objects.requireNonNull(walls, "walls must not be null");
        this.ownEdge = Objects.requireNonNull(ownEdge, "ownEdge must not be null");
    }

    /** Reads every island's choice into memory, off the main thread, and answers how many there were. */
    public int prime() {
        Map<IslandId, String> all = port.findAll();
        chosen.putAll(all);
        return all.size();
    }

    /** Hears every island whose choice changed. */
    public void whenChanged(Consumer<IslandId> listener) {
        changed.add(Objects.requireNonNull(listener, "listener must not be null"));
    }

    /**
     * How {@code island}'s edge is drawn now. A choice of a style the operator took out of walls.conf since is the
     * server's default again.
     */
    public String styleOf(IslandId island) {
        Objects.requireNonNull(island, "island must not be null");
        if (ownEdge.test(island)) {
            return WallConfiguration.NONE;
        }
        String choice = chosen.get(island);
        return choice != null && walls.edge(choice) ? choice : walls.island();
    }

    /** Whether {@code island} is edged by the square border a client draws. */
    public boolean drawsBorder(IslandId island) {
        return WallConfiguration.BORDER.equals(styleOf(island));
    }

    /** The wall {@code island} is edged by, or nothing when it is edged some other way. */
    public Optional<WallStyle> wallOf(IslandId island) {
        return Optional.ofNullable(walls.styles().get(styleOf(island)));
    }

    /** The wall style {@code way} names, when it names one. */
    public Optional<WallStyle> wallStyle(String way) {
        return Optional.ofNullable(walls.styles().get(way));
    }

    /** Whether the members of {@code island} may choose how its edge is drawn. One that edges its own ground may not. */
    public boolean choosable(IslandId island) {
        return !ownEdge.test(island);
    }

    /** Every way an island's edge can be drawn, in the order a window shows them. */
    public List<String> choices() {
        return walls.edges();
    }

    /**
     * Keeps {@code style} as {@code island}'s choice, off the main thread, and tells whoever draws edges. Answers false
     * for a style that is no way an edge is drawn here, or an island that may not choose.
     */
    public boolean choose(IslandId island, String style) {
        Objects.requireNonNull(island, "island must not be null");
        Objects.requireNonNull(style, "style must not be null");
        if (!walls.edge(style) || !choosable(island)) {
            return false;
        }
        port.keep(island, style);
        chosen.put(island, style);
        changed.forEach(listener -> listener.accept(island));
        return true;
    }
}
