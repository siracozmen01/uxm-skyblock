package com.uxplima.uxmskyblock.core.application.chunkblock;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkPos;
import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkTerritory;
import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkUnlockRules;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;

/**
 * The chunks of every ChunkBlock island: opened with the island's level, closed when it falls.
 *
 * <p>Each territory is held in memory, so a step or a block placed near a closed chunk is answered
 * without a query. A change is written first and swapped in as a new copy after, so a reader never
 * sees a chunk the table does not hold.
 */
public final class ChunkBlockService {

    /** What an unlock came to. */
    public enum Outcome {
        UNLOCKED,
        ALREADY_OPEN,
        NOT_BESIDE_TERRITORY,
        OUTSIDE_ISLAND,
        LEVEL_TOO_LOW,
        NOT_CHUNKBLOCK,
        /** Another unlock on the same island took the place first; the territory is read again. */
        TAKEN
    }

    /** An unlock's outcome and the level the next chunk needs after it. */
    public record UnlockResult(Outcome outcome, long nextRequirement) {}

    private final ChunkTerritoryPort port;
    private final ChunkUnlockRules rules;
    private final Map<IslandId, ChunkTerritory> memory = new ConcurrentHashMap<>();
    private final Map<IslandId, Object> locks = new ConcurrentHashMap<>();
    private final List<BiConsumer<IslandId, List<ChunkPos>>> closedListeners = new CopyOnWriteArrayList<>();
    private final List<BiConsumer<IslandId, ChunkPos>> openedListeners = new CopyOnWriteArrayList<>();

    public ChunkBlockService(ChunkTerritoryPort port, ChunkUnlockRules rules) {
        this.port = Objects.requireNonNull(port, "port must not be null");
        this.rules = Objects.requireNonNull(rules, "rules must not be null");
    }

    public ChunkUnlockRules rules() {
        return rules;
    }

    /** Reads every territory into memory. Off the main thread, when the server starts. */
    public int prime() {
        Map<IslandId, ChunkTerritory> all = port.findAll();
        all.forEach(memory::putIfAbsent);
        return all.size();
    }

    /** Makes an island a ChunkBlock island, starting with the chunk its block stands in. */
    public void start(IslandId islandId, int blockX, int blockZ) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        ChunkPos origin = ChunkPos.ofBlock(blockX, blockZ);
        port.start(islandId, origin);
        memory.put(islandId, port.find(islandId).orElseGet(() -> ChunkTerritory.startingAt(origin)));
    }

    /**
     * Whether a block of the island may be touched: empty for an island that is not a ChunkBlock
     * island, which this mode has nothing to say about. Memory only.
     */
    public Optional<Boolean> isOpen(IslandId islandId, ChunkPos chunk) {
        ChunkTerritory territory = memory.get(islandId);
        return territory == null ? Optional.empty() : Optional.of(territory.isOpen(chunk));
    }

    /** The island's territory as held in memory, a copy the caller may keep. */
    public Optional<ChunkTerritory> inMemory(IslandId islandId) {
        ChunkTerritory territory = memory.get(islandId);
        return territory == null ? Optional.empty() : Optional.of(copyOf(territory));
    }

    /** The island's territory, read from the table when it is not in memory. Off the main thread. */
    public Optional<ChunkTerritory> territory(IslandId islandId) {
        ChunkTerritory held = memory.get(islandId);
        if (held != null) {
            return Optional.of(copyOf(held));
        }
        Optional<ChunkTerritory> read = port.find(islandId);
        read.ifPresent(found -> memory.putIfAbsent(islandId, found));
        return read.map(ChunkBlockService::copyOf);
    }

    /** Opens {@code chunk} for an island at {@code level}. Off the main thread: it writes a row. */
    public UnlockResult unlock(IslandId islandId, ChunkPos chunk, IslandBounds bounds, long level) {
        Objects.requireNonNull(chunk, "chunk must not be null");
        Objects.requireNonNull(bounds, "bounds must not be null");
        UnlockResult opened;
        synchronized (lockOf(islandId)) {
            Optional<ChunkTerritory> current = territory(islandId);
            if (current.isEmpty()) {
                return new UnlockResult(Outcome.NOT_CHUNKBLOCK, 0);
            }
            ChunkTerritory next = current.get();
            ChunkTerritory.Unlock unlock = next.unlock(chunk, bounds, rules, level);
            if (unlock != ChunkTerritory.Unlock.UNLOCKED) {
                return new UnlockResult(Outcome.valueOf(unlock.name()), next.nextRequirement(rules));
            }
            if (!port.open(islandId, chunk, next.opened().size())) {
                // Another node opened one first. What the table holds is what stands.
                port.find(islandId).ifPresent(read -> memory.put(islandId, read));
                return new UnlockResult(Outcome.TAKEN, rules.requiredFor(size(islandId)));
            }
            memory.put(islandId, next);
            opened = new UnlockResult(Outcome.UNLOCKED, next.nextRequirement(rules));
        }
        for (BiConsumer<IslandId, ChunkPos> listener : openedListeners) {
            listener.accept(islandId, chunk);
        }
        return opened;
    }

    /**
     * Closes the chunks an island at {@code level} can no longer hold, newest first, and tells the
     * listeners, who move anybody standing in one.
     */
    public List<ChunkPos> onLevel(IslandId islandId, long level) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        List<ChunkPos> closed;
        synchronized (lockOf(islandId)) {
            ChunkTerritory held = memory.get(islandId);
            if (held == null) {
                return List.of();
            }
            ChunkTerritory next = copyOf(held);
            closed = next.relockFor(level, rules);
            if (closed.isEmpty()) {
                return List.of();
            }
            port.close(islandId, closed);
            memory.put(islandId, next);
        }
        for (BiConsumer<IslandId, List<ChunkPos>> listener : closedListeners) {
            listener.accept(islandId, closed);
        }
        return closed;
    }

    /** Told which chunk an island opened, after it is open. */
    public void whenOpened(BiConsumer<IslandId, ChunkPos> listener) {
        openedListeners.add(Objects.requireNonNull(listener, "listener must not be null"));
    }

    /** Told which chunks an island lost, after they are closed. */
    public void whenClosed(BiConsumer<IslandId, List<ChunkPos>> listener) {
        closedListeners.add(Objects.requireNonNull(listener, "listener must not be null"));
    }

    /**
     * Reads the island's territory again after it changed. An island that still has its rows keeps
     * the territory they hold, and one whose rows are gone, because it was erased, is dropped.
     *
     * <p>Forgetting is also what a node does when it hears an island changed, and it hears its own
     * island being created. The rows are what say whether the island is gone. Off the main thread: it
     * reads rows.
     */
    public void forget(IslandId islandId) {
        synchronized (lockOf(islandId)) {
            Optional<ChunkTerritory> stored = port.find(islandId);
            if (stored.isPresent()) {
                memory.put(islandId, stored.get());
                return;
            }
            memory.remove(islandId);
        }
        locks.remove(islandId);
    }

    private int size(IslandId islandId) {
        ChunkTerritory held = memory.get(islandId);
        return held == null ? 1 : held.opened().size() + 1;
    }

    private Object lockOf(IslandId islandId) {
        return locks.computeIfAbsent(Objects.requireNonNull(islandId, "islandId must not be null"), id -> new Object());
    }

    private static ChunkTerritory copyOf(ChunkTerritory territory) {
        return new ChunkTerritory(territory.origin(), territory.opened(), territory.bought());
    }
}
