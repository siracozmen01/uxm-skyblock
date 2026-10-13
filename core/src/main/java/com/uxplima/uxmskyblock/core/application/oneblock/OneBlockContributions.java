package com.uxplima.uxmskyblock.core.application.oneblock;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import com.uxplima.uxmskyblock.core.application.oneblock.OneBlockContributionPort.Contribution;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;

/**
 * How many blocks each player broke on each OneBlock island. A break is counted in memory, at once and on any
 * thread, and the counts not yet written are added to storage when {@link #flush} runs, as the island's own count
 * is. What storage refuses stays in memory for the next flush.
 */
public final class OneBlockContributions {

    /** One player's breaks on one island: all of them, and those storage has not been told yet. */
    private static final class Count {
        final AtomicLong total = new AtomicLong();
        final AtomicLong unwritten = new AtomicLong();
    }

    private final OneBlockContributionPort port;
    private final Map<IslandId, Map<PlayerUuid, Count>> islands = new ConcurrentHashMap<>();

    public OneBlockContributions(OneBlockContributionPort port) {
        this.port = Objects.requireNonNull(port, "port must not be null");
    }

    /** Reads every stored count into memory, once, before players break anything. Answers how many it read. */
    public int prime() {
        List<Contribution> stored = port.findAll();
        for (Contribution one : stored) {
            countOf(one.islandId(), one.player()).total.addAndGet(one.breaks());
        }
        return stored.size();
    }

    /** Counts one break of {@code player} on the island. */
    public void count(IslandId islandId, PlayerUuid player) {
        Count count = countOf(islandId, player);
        count.total.incrementAndGet();
        count.unwritten.incrementAndGet();
    }

    /** How many blocks {@code player} broke on the island. */
    public long of(IslandId islandId, PlayerUuid player) {
        Objects.requireNonNull(player, "player must not be null");
        Count count = islands.getOrDefault(islandId, Map.of()).get(player);
        return count == null ? 0 : count.total.get();
    }

    /** Every player who broke a block on the island, the most breaks first, and of two alike the lower uuid. */
    public List<Contribution> of(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        return islands.getOrDefault(islandId, Map.of()).entrySet().stream()
                .map(entry -> new Contribution(
                        islandId, entry.getKey(), entry.getValue().total.get()))
                .sorted(Comparator.comparingLong(Contribution::breaks)
                        .reversed()
                        .thenComparing(one -> one.player().value()))
                .toList();
    }

    /**
     * Adds every count not yet written to storage. A count storage refuses is kept for the next flush. Answers how
     * many it wrote.
     */
    public int flush() {
        int written = 0;
        for (Map.Entry<IslandId, Map<PlayerUuid, Count>> island : islands.entrySet()) {
            for (Map.Entry<PlayerUuid, Count> player : island.getValue().entrySet()) {
                long pending = player.getValue().unwritten.getAndSet(0);
                if (pending == 0) {
                    continue;
                }
                try {
                    port.add(island.getKey(), player.getKey(), pending);
                    written++;
                } catch (RuntimeException refused) {
                    player.getValue().unwritten.addAndGet(pending);
                }
            }
        }
        return written;
    }

    /** Forgets every count of the island, in memory and in storage, as when it starts over. */
    public void start(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        islands.remove(islandId);
        port.clear(islandId);
    }

    private Count countOf(IslandId islandId, PlayerUuid player) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(player, "player must not be null");
        return islands.computeIfAbsent(islandId, id -> new ConcurrentHashMap<>())
                .computeIfAbsent(player, who -> new Count());
    }
}
