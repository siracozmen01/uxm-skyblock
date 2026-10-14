package com.uxplima.uxmskyblock.core.application.oneblock;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.oneblock.OneBlockPhases;
import org.jspecify.annotations.Nullable;

/**
 * What the OneBlock service keeps in memory: an island read in once, its count written back when due, every island primed at start, and the draws made ahead along its route. Split out of {@link OneBlockService}, whose services and words it uses, so neither file is over the size the
 * standards draw a line at.
 */
final class OneBlockMemory {

    private static final java.util.logging.Logger LOGGER =
            java.util.logging.Logger.getLogger(OneBlockMemory.class.getName());

    private final OneBlockService host;

    OneBlockMemory(OneBlockService host) {
        this.host = host;
    }

    @Nullable CountedIsland held(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        CountedIsland held = host.counted.get(islandId);
        if (held != null) {
            return held;
        }
        if ((host.primed && !host.forgotten.contains(islandId)) || host.notOneBlock.contains(islandId)) {
            return null;
        }
        host.forgotten.remove(islandId);
        Optional<OneBlockProgressPort.OneBlockIsland> stored = host.progress.find(islandId);
        if (stored.isEmpty()) {
            host.notOneBlock.add(islandId);
            return null;
        }
        java.util.List<OneBlockRewardPort.Paid> paid = host.rewards.find(islandId);
        Optional<OneBlockWaitPort.Waiting> waiting = host.waits.find(islandId);
        java.util.List<OneBlockBranchPort.Pick> picked = host.branches.find(islandId);
        return host.counted.computeIfAbsent(islandId, id -> {
            CountedIsland read = new CountedIsland(stored.get());
            OneBlockService.remember(read, paid);
            read.waiting.set(waiting.orElse(null));
            picked.forEach(one -> read.picks.put(one.phase(), one.chosen()));
            return read;
        });
    }
    /**
     * Writes what the island has counted since the last write, or its whole count when an operator set it. A write
     * that fails keeps what it would have written for the next.
     *
     * @return whether anything was written
     */
    boolean writeNow(IslandId islandId, CountedIsland held) {
        if (held.replaced.getAndSet(false)) {
            // The whole count holds every break counted so far, so none is added on top of it later.
            held.unwritten.set(0);
            try {
                host.progress.setBreaks(islandId, held.total.get());
                return true;
            } catch (RuntimeException failed) {
                held.replaced.set(true);
                LOGGER.log(
                        Level.WARNING,
                        failed,
                        () -> "Setting the OneBlock count of island " + islandId + " failed; it is kept.");
                return false;
            }
        }
        long pending = held.unwritten.getAndSet(0);
        if (pending == 0) {
            return false;
        }
        try {
            host.progress.addBreaks(islandId, pending);
            return true;
        } catch (RuntimeException failed) {
            held.unwritten.addAndGet(pending);
            LOGGER.log(
                    Level.WARNING,
                    failed,
                    () -> "Writing the OneBlock count of island " + islandId + " failed; it is kept.");
            return false;
        }
    }
    /**
     * Reads every OneBlock island into memory. Run off the region threads when the server starts;
     * until it has, an island is read the first time a block breaks on it.
     *
     * @return how many OneBlock islands there are
     */
    public int prime() {
        java.util.List<OneBlockProgressPort.OneBlockIsland> all = host.progress.findAll();
        for (OneBlockProgressPort.OneBlockIsland island : all) {
            host.counted.putIfAbsent(island.islandId(), new CountedIsland(island));
        }
        Map<IslandId, java.util.List<OneBlockRewardPort.Paid>> paid = new java.util.HashMap<>();
        for (OneBlockRewardPort.Paid one : host.rewards.findAll()) {
            paid.computeIfAbsent(one.islandId(), id -> new java.util.ArrayList<>())
                    .add(one);
        }
        paid.forEach((islandId, those) -> {
            CountedIsland held = host.counted.get(islandId);
            if (held != null) {
                OneBlockService.remember(held, those);
            }
        });
        for (OneBlockBranchPort.Pick one : host.branches.findAll()) {
            CountedIsland held = host.counted.get(one.islandId());
            if (held != null) {
                held.picks.putIfAbsent(one.phase(), one.chosen());
                held.route = null;
            }
        }
        for (OneBlockWaitPort.Waiting one : host.waits.findAll()) {
            CountedIsland held = host.counted.get(one.islandId());
            if (held != null) {
                held.waiting.compareAndSet(null, one);
            }
        }
        host.primed = true;
        return all.size();
    }

    DrawnAhead draw(OneBlockPhases way, long at) {
        String block = way.nextBlock(at, host.random);
        return new DrawnAhead(at, block, way.nextCreature(at, host.random));
    }

    OneBlockPhases routeOf(CountedIsland held) {
        OneBlockPhases way = held.route;
        if (way == null) {
            way = host.phases.route(Map.copyOf(held.picks));
            held.route = way;
        }
        return way;
    }
}
