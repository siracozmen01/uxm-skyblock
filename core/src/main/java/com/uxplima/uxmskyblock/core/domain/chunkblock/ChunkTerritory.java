package com.uxplima.uxmskyblock.core.domain.chunkblock;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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

    /** What an operator's closing came to. */
    public enum Close {
        CLOSED,
        /** The chunk is not open, or is the one the island started with, which never closes. */
        NOT_OPEN,
        /** Closing it would cut another open chunk off from the first; close that one first. */
        CUTS_OFF
    }

    private final ChunkPos origin;
    private final List<ChunkPos> opened;
    private final Set<ChunkPos> bought;

    /**
     * @param origin the chunk the island started with
     * @param opened the chunks opened since, oldest first
     */
    public ChunkTerritory(ChunkPos origin, List<ChunkPos> opened) {
        this(origin, opened, Set.of());
    }

    /**
     * @param origin the chunk the island started with
     * @param opened the chunks opened since, oldest first
     * @param bought the opened chunks that were paid for, which stay open whatever the level does
     */
    public ChunkTerritory(ChunkPos origin, List<ChunkPos> opened, Set<ChunkPos> bought) {
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
        this.bought = new LinkedHashSet<>(Objects.requireNonNull(bought, "bought must not be null"));
        if (!this.opened.containsAll(this.bought)) {
            throw new IllegalArgumentException("a bought chunk is an opened one");
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

    /** The opened chunks that were paid for, which no fall of the level closes. */
    public Set<ChunkPos> bought() {
        return java.util.Collections.unmodifiableSet(bought);
    }

    /** Whether {@code chunk} was paid for. */
    public boolean isBought(ChunkPos chunk) {
        return bought.contains(chunk);
    }

    /** How many opened chunks the level holds: every opened one but those paid for. */
    public int heldCount() {
        return opened.size() - bought.size();
    }

    /** The level the next chunk the level holds needs, as the ladder prices it. */
    public long nextRequirement(ChunkUnlockRules rules) {
        return rules.requiredFor(heldCount() + 1);
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
     * Opens {@code chunk}, its cost settled by the caller, or says why it cannot stand there.
     *
     * @param held whether the level holds it, so it closes again when the level falls; false for a chunk paid for
     */
    public Unlock open(ChunkPos chunk, IslandBounds bounds, boolean held) {
        Objects.requireNonNull(chunk, "chunk must not be null");
        Unlock place = placeFor(chunk, bounds);
        if (place != Unlock.UNLOCKED) {
            return place;
        }
        opened.add(chunk);
        if (!held) {
            bought.add(chunk);
        }
        return Unlock.UNLOCKED;
    }

    /**
     * Closes the chunks the level holds that an island at {@code level} can no longer hold, newest first. A chunk
     * paid for never closes, and a held chunk whose closing would cut a paid one off from the rest stays open; the
     * next newest closes in its place.
     *
     * @return the chunks closed, in the order they were closed
     */
    public List<ChunkPos> relockFor(long level, ChunkUnlockRules rules) {
        List<ChunkPos> closed = new ArrayList<>();
        while (heldCount() > 0 && rules.requiredFor(heldCount()) > level) {
            Optional<ChunkPos> next = newestClosable();
            if (next.isEmpty()) {
                break;
            }
            opened.remove(next.get());
            closed.add(next.get());
        }
        return List.copyOf(closed);
    }

    /**
     * Closes {@code chunk}, as an operator does, paid for or not. The chunk the island started with never closes,
     * and neither does one whose closing would leave another open chunk cut off from it.
     */
    public Close close(ChunkPos chunk) {
        Objects.requireNonNull(chunk, "chunk must not be null");
        if (!opened.contains(chunk)) {
            return Close.NOT_OPEN;
        }
        if (!joinedWithout(chunk)) {
            return Close.CUTS_OFF;
        }
        opened.remove(chunk);
        bought.remove(chunk);
        return Close.CLOSED;
    }

    /** Closes every chunk but the first, newest first, as an operator's reset does, and says which. */
    public List<ChunkPos> closeAll() {
        List<ChunkPos> closed = new ArrayList<>(opened.reversed());
        opened.clear();
        bought.clear();
        return List.copyOf(closed);
    }

    /** The newest held chunk whose closing leaves every other open chunk joined to the first. */
    private Optional<ChunkPos> newestClosable() {
        for (int i = opened.size() - 1; i >= 0; i--) {
            ChunkPos candidate = opened.get(i);
            if (!bought.contains(candidate) && joinedWithout(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /** Whether every open chunk but {@code left} is reached from the first, stepping beside, never across. */
    private boolean joinedWithout(ChunkPos left) {
        Set<ChunkPos> open = new LinkedHashSet<>(open());
        open.remove(left);
        Set<ChunkPos> reached = new LinkedHashSet<>();
        java.util.ArrayDeque<ChunkPos> queue = new java.util.ArrayDeque<>();
        queue.add(origin);
        reached.add(origin);
        while (!queue.isEmpty()) {
            for (ChunkPos next : queue.poll().neighbours()) {
                if (open.contains(next) && reached.add(next)) {
                    queue.add(next);
                }
            }
        }
        return reached.size() == open.size();
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
