package com.uxplima.uxmskyblock.core.application.oneblock;

import java.time.Instant;
import java.util.logging.Level;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import org.jspecify.annotations.Nullable;

/**
 * What an island waits for before its next phase: the wait set and ended, written, and every timed wait the server must end. Split out of {@link OneBlockService}, whose services and words it uses, so neither file is over the size the
 * standards draw a line at.
 */
final class OneBlockWaiting {

    private static final java.util.logging.Logger LOGGER =
            java.util.logging.Logger.getLogger(OneBlockWaiting.class.getName());

    private final OneBlockService host;

    OneBlockWaiting(OneBlockService host) {
        this.host = host;
    }

    /**
     * Has the island wait before {@code phase}, until {@code until} or, when that is none, until the phase's
     * requirements hold. In memory only: {@link #storeWait} writes it, off the region threads.
     *
     * @return whether the island waits now, which it does not when it is no OneBlock island
     */
    public boolean waitBefore(IslandId islandId, String phase, @Nullable Instant until) {
        CountedIsland held = host.memory.held(islandId);
        if (held == null) {
            return false;
        }
        held.waiting.set(new OneBlockWaitPort.Waiting(islandId, phase, until));
        return true;
    }
    /**
     * Ends the island's wait, if it is the one given, in memory; {@link #storeWait} writes it. A wait ended twice,
     * by its timer and by an operator, ends once.
     *
     * @return whether this call ended it
     */
    public boolean endWait(IslandId islandId, OneBlockWaitPort.Waiting ended) {
        CountedIsland held = host.memory.held(islandId);
        return held != null && held.waiting.compareAndSet(ended, null);
    }
    /**
     * Writes the island's wait as it stands in memory, or that it waits no longer. Run off the region threads.
     *
     * @return whether it was written
     */
    public boolean storeWait(IslandId islandId) {
        CountedIsland held = host.memory.held(islandId);
        if (held == null) {
            return false;
        }
        OneBlockWaitPort.Waiting now = held.waiting.get();
        try {
            if (now == null) {
                host.waits.clear(islandId);
            } else {
                host.waits.set(now);
            }
            return true;
        } catch (RuntimeException failed) {
            LOGGER.log(
                    Level.WARNING,
                    failed,
                    () -> "Writing the OneBlock wait of island " + islandId + " failed; it is kept in memory.");
            return false;
        }
    }
    /** Every island waiting for a time to pass, as held in memory: each wait goes on after a restart. */
    public java.util.List<OneBlockWaitPort.Waiting> timedWaits() {
        java.util.List<OneBlockWaitPort.Waiting> timed = new java.util.ArrayList<>();
        for (CountedIsland held : host.counted.values()) {
            OneBlockWaitPort.Waiting now = held.waiting.get();
            if (now != null && now.timed()) {
                timed.add(now);
            }
        }
        return timed;
    }
}
