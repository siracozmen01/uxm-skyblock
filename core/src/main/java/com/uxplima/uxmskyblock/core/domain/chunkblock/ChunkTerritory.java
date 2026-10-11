package com.uxplima.uxmskyblock.core.domain.chunkblock;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;

/**
 * The chunks a ChunkBlock island has open, in the order they were opened.
 *
 * <p>The first chunk is the one the magic block stands in, and it is never closed. Every chunk after it
 * was opened beside one already open, so closing them last first leaves the rest joined.
 */
public final class ChunkTerritory {

    /** What an unlock came to. */
    public enum Unlock {
        UNLOCKED,
        ALREADY_OPEN,
        NOT_BESIDE_TERRITORY,
        OUTSIDE_ISLAND,
        LEVEL_TOO_LOW
    }

    private final ChunkPos origin;
    private final List<ChunkPos> opened;

    /**
     * @param origin the chunk the island started with
     * @param opened the chunks opened since, oldest first
     */
    public ChunkTerritory(ChunkPos origin, List<ChunkPos> opened) {
        this.origin = Objects.requireNonNull(origin, "origin must not be null");
        Objects.requireNonNull(opened, "opened must not be null");
        this.opened = new ArrayList<>(opened.size());
        Set<ChunkPos> seen = new LinkedHashSet<>();
        seen.add(origin);
        for (ChunkPos chunk : opened) {
            if (!seen.add(chunk)) {
                throw new IllegalArgumentException("the chunk " + chunk + " is opened twice");
            }
            this.opened.add(chunk);
        }
    }

    public static ChunkTerritory startingAt(ChunkPos origin) {
        return new ChunkTerritory(origin, List.of());
    }

    public ChunkPos origin() {
        return origin;
    }

    /** The chunks opened after the first, oldest first. */
    public List<ChunkPos> opened() {
        return List.copyOf(opened);
    }

    /** Every open chunk, the first among them. */
    public Set<ChunkPos> open() {
        Set<ChunkPos> open = new LinkedHashSet<>();
        open.add(origin);
        open.addAll(opened);
        return java.util.Collections.unmodifiableSet(open);
    }

    public int size() {
        return opened.size() + 1;
    }

    public boolean isOpen(ChunkPos chunk) {
        return origin.equals(chunk) || opened.contains(chunk);
    }

    /** The level the next chunk needs. */
    public long nextRequirement(ChunkUnlockRules rules) {
        return rules.requiredFor(opened.size() + 1);
    }

    /** Whether {@code chunk} could be opened now, leaving the level aside. */
    public Unlock placeFor(ChunkPos chunk, IslandBounds bounds) {
        if (isOpen(chunk)) {
            return Unlock.ALREADY_OPEN;
        }
        if (!chunk.overlaps(bounds)) {
            return Unlock.OUTSIDE_ISLAND;
        }
        if (!origin.touches(chunk) && opened.stream().noneMatch(chunk::touches)) {
            return Unlock.NOT_BESIDE_TERRITORY;
        }
        return Unlock.UNLOCKED;
    }

    /** Opens {@code chunk} for an island at {@code level}, or says why it stays closed. */
    public Unlock unlock(ChunkPos chunk, IslandBounds bounds, ChunkUnlockRules rules, long level) {
        Objects.requireNonNull(chunk, "chunk must not be null");
        Unlock place = placeFor(chunk, bounds);
        if (place != Unlock.UNLOCKED) {
            return place;
        }
        if (level < nextRequirement(rules)) {
            return Unlock.LEVEL_TOO_LOW;
        }
        opened.add(chunk);
        return Unlock.UNLOCKED;
    }

    /**
     * Closes the chunks an island at {@code level} can no longer hold, newest first.
     *
     * @return the chunks closed, in the order they were closed
     */
    public List<ChunkPos> relockFor(long level, ChunkUnlockRules rules) {
        List<ChunkPos> closed = new ArrayList<>();
        while (!opened.isEmpty() && rules.requiredFor(opened.size()) > level) {
            closed.add(opened.removeLast());
        }
        return List.copyOf(closed);
    }

    /** The open chunk nearest {@code chunk}, where a player shut out of it is put. */
    public ChunkPos nearestOpenTo(ChunkPos chunk) {
        ChunkPos best = origin;
        long bestDistance = distance(origin, chunk);
        for (ChunkPos candidate : opened) {
            long d = distance(candidate, chunk);
            if (d < bestDistance) {
                best = candidate;
                bestDistance = d;
            }
        }
        return best;
    }

    private static long distance(ChunkPos a, ChunkPos b) {
        long dx = (long) a.x() - b.x();
        long dz = (long) a.z() - b.z();
        return dx * dx + dz * dz;
    }
}
