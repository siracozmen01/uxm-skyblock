package com.uxplima.uxmskyblock.core.application.oneblock;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import com.uxplima.uxmskyblock.core.domain.oneblock.OneBlockPhases;
import org.jspecify.annotations.Nullable;

/** An island's count while it is in memory: what is stored, and what was counted since. */
final class CountedIsland {
    final OneBlockProgressPort.OneBlockIsland stored;
    final AtomicLong total;
    final AtomicLong unwritten = new AtomicLong();
    /** Whether an operator set the count since the last write, so the next write sets it whole. */
    final java.util.concurrent.atomic.AtomicBoolean replaced = new java.util.concurrent.atomic.AtomicBoolean();
    /** The last pass each reward of a phase was paid in, keyed by {@code OneBlockService.paidKey}. */
    final Map<String, Long> paid = new ConcurrentHashMap<>();
    /** The phase the island picked to follow each phase that branches, by the phase it follows. */
    final Map<String, String> picks = new ConcurrentHashMap<>();
    /** The island's way through the phases with its picks, worked out again after a pick. */
    volatile @Nullable OneBlockPhases route;
    /** The draws made ahead for the coming breaks, in order, each for the count it belongs to. */
    final java.util.ArrayDeque<DrawnAhead> ahead = new java.util.ArrayDeque<>();
    /** What the island waits for before its next phase, or nothing. */
    final java.util.concurrent.atomic.AtomicReference<OneBlockWaitPort.@Nullable Waiting> waiting =
            new java.util.concurrent.atomic.AtomicReference<>();

    CountedIsland(OneBlockProgressPort.OneBlockIsland stored) {
        this.stored = stored;
        this.total = new AtomicLong(stored.blocksBroken());
    }
}
