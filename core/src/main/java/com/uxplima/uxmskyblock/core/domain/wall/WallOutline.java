package com.uxplima.uxmskyblock.core.domain.wall;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkPos;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;

/**
 * The walls around ground: around the chunks a ChunkBlock island holds, or around an island's square.
 *
 * <p>The world border a client draws is one square, and the chunks a ChunkBlock island holds are seldom one: they
 * grow beside each other into any shape. A wall stands on every side of an open chunk whose neighbour is closed.
 */
public final class WallOutline {

    private WallOutline() {}

    /** The walls of {@code chunk} that face a chunk not in {@code open}, nothing for a chunk not open itself. */
    public static List<WallSegment> of(ChunkPos chunk, Set<ChunkPos> open) {
        Objects.requireNonNull(chunk, "chunk must not be null");
        Objects.requireNonNull(open, "open must not be null");
        if (!open.contains(chunk)) {
            return List.of();
        }
        int minX = chunk.x() << 4;
        int minZ = chunk.z() << 4;
        List<WallSegment> walls = new ArrayList<>(4);
        if (!open.contains(new ChunkPos(chunk.x(), chunk.z() - 1))) {
            walls.add(new WallSegment(WallSegment.Side.NORTH, minZ, minX, minX + 16));
        }
        if (!open.contains(new ChunkPos(chunk.x(), chunk.z() + 1))) {
            walls.add(new WallSegment(WallSegment.Side.SOUTH, minZ + 16, minX, minX + 16));
        }
        if (!open.contains(new ChunkPos(chunk.x() - 1, chunk.z()))) {
            walls.add(new WallSegment(WallSegment.Side.WEST, minX, minZ, minZ + 16));
        }
        if (!open.contains(new ChunkPos(chunk.x() + 1, chunk.z()))) {
            walls.add(new WallSegment(WallSegment.Side.EAST, minX + 16, minZ, minZ + 16));
        }
        return List.copyOf(walls);
    }

    /** Every wall around {@code open}, chunk by chunk. */
    public static List<WallSegment> of(Set<ChunkPos> open) {
        List<WallSegment> walls = new ArrayList<>();
        for (ChunkPos chunk : open) {
            walls.addAll(of(chunk, open));
        }
        return List.copyOf(walls);
    }

    /**
     * The walls around an island's square, each side cut into stretches no longer than {@code longest}, so a
     * stretch is drawn by what stands near it.
     */
    public static List<WallSegment> of(IslandBounds bounds, int longest) {
        Objects.requireNonNull(bounds, "bounds must not be null");
        if (longest < 1) {
            throw new IllegalArgumentException("a stretch is at least one block long, not " + longest);
        }
        List<WallSegment> walls = new ArrayList<>();
        cut(walls, WallSegment.Side.NORTH, bounds.minZ(), bounds.minX(), bounds.maxX() + 1, longest);
        cut(walls, WallSegment.Side.SOUTH, bounds.maxZ() + 1, bounds.minX(), bounds.maxX() + 1, longest);
        cut(walls, WallSegment.Side.WEST, bounds.minX(), bounds.minZ(), bounds.maxZ() + 1, longest);
        cut(walls, WallSegment.Side.EAST, bounds.maxX() + 1, bounds.minZ(), bounds.maxZ() + 1, longest);
        return List.copyOf(walls);
    }

    private static void cut(List<WallSegment> walls, WallSegment.Side side, int line, int from, int to, int longest) {
        for (int start = from; start < to; start += longest) {
            walls.add(new WallSegment(side, line, start, Math.min(to, start + longest)));
        }
    }
}
