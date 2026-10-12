package com.uxplima.uxmskyblock.core.application.oneblock;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.random.RandomGenerator;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.oneblock.OneBlockPhase;
import com.uxplima.uxmskyblock.core.domain.oneblock.OneBlockPhases;
import org.jspecify.annotations.Nullable;

/**
 * What happens when a OneBlock island's block is broken, and keeping count of it.
 *
 * <p>A block breaks every few ticks on a busy island, and a row written for every one of them would
 * be the busiest table on the server. The count is kept in memory, each island's own, and what has
 * been counted since the last write is added to the stored count on a schedule and when the server
 * stops. A crash loses at most the breaks of one interval, and those only move a phase a little later;
 * no item or balance hangs on the count.
 */
public final class OneBlockService {

    private static final Logger LOGGER = Logger.getLogger(OneBlockService.class.getName());

    /**
     * What one break did: what the block becomes, what appears on it, whether a phase began, the phase it finished
     * when one did, the pass through the phases the broken block belonged to, from 0, and the pass the block it turns
     * into belongs to, one more than that when the break went round to the first phase again.
     */
    public record Broken(
            String nextBlock,
            Optional<String> creature,
            OneBlockPhases.Position position,
            boolean phaseBegan,
            Optional<OneBlockPhase> finished,
            long cycle,
            long nextCycle) {}

    /** What an operator's change did to an island's count. */
    public record Changed(long was, long now) {}

    /** An island's count while it is in memory: what is stored, and what was counted since. */
    private static final class Counted {
        final OneBlockProgressPort.OneBlockIsland stored;
        final AtomicLong total;
        final AtomicLong unwritten = new AtomicLong();
        /** Whether an operator set the count since the last write, so the next write sets it whole. */
        final java.util.concurrent.atomic.AtomicBoolean replaced = new java.util.concurrent.atomic.AtomicBoolean();
        /** The last pass each reward of a phase was paid in, keyed by {@link #paidKey}. */
        final Map<String, Long> paid = new ConcurrentHashMap<>();

        Counted(OneBlockProgressPort.OneBlockIsland stored) {
            this.stored = stored;
            this.total = new AtomicLong(stored.blocksBroken());
        }
    }

    private final OneBlockProgressPort progress;
    private final OneBlockRewardPort rewards;
    private final OneBlockPhases phases;
    private final RandomGenerator random;
    private final Map<IslandId, Counted> counted = new ConcurrentHashMap<>();

    /**
     * Whether every OneBlock island is in memory. Once it is, an island not held is not a OneBlock
     * island, and a block broken on it asks the database nothing: the question is answered on the
     * thread that owns the block, where a query would stall the region.
     */
    private volatile boolean primed;

    /** Islands let go of since priming, which are read again the next time a block breaks on one. */
    private final java.util.Set<IslandId> forgotten = ConcurrentHashMap.newKeySet();

    /** Islands read once and found not to be OneBlock islands, so a block broken on one asks nothing. */
    private final java.util.Set<IslandId> notOneBlock = ConcurrentHashMap.newKeySet();

    public OneBlockService(OneBlockProgressPort progress, OneBlockPhases phases, RandomGenerator random) {
        this(progress, OneBlockRewardPort.NONE, phases, random);
    }

    public OneBlockService(
            OneBlockProgressPort progress, OneBlockRewardPort rewards, OneBlockPhases phases, RandomGenerator random) {
        this.progress = Objects.requireNonNull(progress, "progress must not be null");
        this.rewards = Objects.requireNonNull(rewards, "rewards must not be null");
        this.phases = Objects.requireNonNull(phases, "phases must not be null");
        this.random = Objects.requireNonNull(random, "random must not be null");
    }

    /** The island as a OneBlock island, read once and then kept, or nothing if it is not one. */
    public Optional<OneBlockProgressPort.OneBlockIsland> island(IslandId islandId) {
        Counted held = held(islandId);
        if (held == null) {
            return Optional.empty();
        }
        OneBlockProgressPort.OneBlockIsland stored = held.stored;
        return Optional.of(new OneBlockProgressPort.OneBlockIsland(
                islandId, stored.x(), stored.y(), stored.z(), held.total.get()));
    }

    /**
     * The island as a OneBlock island if it is already in memory, without reading storage for it.
     *
     * <p>For a placeholder, which a scoreboard asks for on the thread that draws it: a query there
     * would stall the region. An island that is not in memory yet answers nothing until a break or a
     * panel has read it.
     */
    public Optional<OneBlockProgressPort.OneBlockIsland> inMemory(IslandId islandId) {
        Counted held = counted.get(islandId);
        if (held == null) {
            return Optional.empty();
        }
        OneBlockProgressPort.OneBlockIsland stored = held.stored;
        return Optional.of(new OneBlockProgressPort.OneBlockIsland(
                islandId, stored.x(), stored.y(), stored.z(), held.total.get()));
    }

    /** The phases every OneBlock island goes through, as the operator wrote them. */
    public OneBlockPhases phases() {
        return phases;
    }

    /** Where the island stands in its phases now, if it is a OneBlock island. */
    public Optional<OneBlockPhases.Position> position(IslandId islandId) {
        Counted held = held(islandId);
        return held == null ? Optional.empty() : Optional.of(phases.positionAt(held.total.get()));
    }

    /** What the island's block is laid as now, drawn from the phase it stands in, or nothing if it is not one. */
    public Optional<String> blockNow(IslandId islandId) {
        Counted held = held(islandId);
        return held == null ? Optional.empty() : Optional.of(phases.nextBlock(held.total.get(), random));
    }

    /** Counts one break of the island's block and says what it turns into, or nothing if it is not one. */
    public Optional<Broken> onBreak(IslandId islandId) {
        Counted held = held(islandId);
        if (held == null) {
            return Optional.empty();
        }
        long broken = held.total.incrementAndGet();
        held.unwritten.incrementAndGet();
        return Optional.of(new Broken(
                phases.nextBlock(broken, random),
                phases.nextCreature(broken, random),
                phases.positionAt(broken),
                phases.startsAPhase(broken),
                phases.startsAPhase(broken)
                        ? Optional.of(phases.positionAt(broken - 1).phase())
                        : Optional.empty(),
                phases.cycleOf(broken - 1),
                phases.cycleOf(broken)));
    }

    /**
     * Whether the island is still owed {@code reward} of {@code phase} in {@code cycle}, and if it is, marks it paid
     * in memory, so the reward is paid once. Asks storage nothing: it is answered on the thread that owns the block.
     * A reward paid in a later pass is not owed again in an earlier one, so a count set back pays nothing twice.
     */
    public boolean claim(IslandId islandId, String phase, String reward, long cycle) {
        Counted held = held(islandId);
        if (held == null) {
            return false;
        }
        boolean[] owed = {false};
        held.paid.compute(paidKey(phase, reward), (key, last) -> {
            if (last != null && last >= cycle) {
                return last;
            }
            owed[0] = true;
            return cycle;
        });
        return owed[0];
    }

    /**
     * Records in storage that the island was paid what {@link #claim} granted. Run off the region threads, before
     * the reward is paid: a reward storage already holds in that pass, written by another node or before a crash
     * that rolled the count back, is not paid, and neither is one storage could not record.
     *
     * @return whether the reward is paid now
     */
    public boolean record(IslandId islandId, String phase, String reward, long cycle) {
        try {
            return rewards.pay(islandId, phase, reward, cycle);
        } catch (RuntimeException failed) {
            LOGGER.log(
                    Level.WARNING,
                    failed,
                    () -> "Recording the OneBlock reward " + phase + "/" + reward + " of island " + islandId
                            + " failed, so it is not paid.");
            return false;
        }
    }

    private static String paidKey(String phase, String reward) {
        return phase + '\n' + reward;
    }

    private static void remember(Counted held, java.util.List<OneBlockRewardPort.Paid> paid) {
        for (OneBlockRewardPort.Paid one : paid) {
            held.paid.merge(paidKey(one.phase(), one.reward()), one.cycle(), Math::max);
        }
    }

    /**
     * Sets the island's count, as an operator does, and writes it at once.
     *
     * @return the count before and after, or nothing if it is not a OneBlock island
     */
    public Optional<Changed> setBroken(IslandId islandId, long count) {
        if (count < 0) {
            throw new IllegalArgumentException("A count of breaks cannot be negative: " + count);
        }
        Counted held = held(islandId);
        if (held == null) {
            return Optional.empty();
        }
        long was = held.total.getAndSet(count);
        held.replaced.set(true);
        writeNow(islandId, held);
        return Optional.of(new Changed(was, count));
    }

    /**
     * Adds {@code breaks} to the island's count, or takes them away when negative, never below nothing, as an
     * operator does, and writes it at once.
     *
     * @return the count before and after, or nothing if it is not a OneBlock island
     */
    public Optional<Changed> addBroken(IslandId islandId, long breaks) {
        Counted held = held(islandId);
        if (held == null) {
            return Optional.empty();
        }
        long was;
        long now;
        do {
            was = held.total.get();
            now = Math.max(0, Math.addExact(was, breaks));
        } while (!held.total.compareAndSet(was, now));
        held.replaced.set(true);
        writeNow(islandId, held);
        return Optional.of(new Changed(was, now));
    }

    /**
     * Writes what the island has counted since the last write, or its whole count when an operator set it. A write
     * that fails keeps what it would have written for the next.
     *
     * @return whether anything was written
     */
    private boolean writeNow(IslandId islandId, Counted held) {
        if (held.replaced.getAndSet(false)) {
            // The whole count holds every break counted so far, so none is added on top of it later.
            held.unwritten.set(0);
            try {
                progress.setBreaks(islandId, held.total.get());
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
            progress.addBreaks(islandId, pending);
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
        java.util.List<OneBlockProgressPort.OneBlockIsland> all = progress.findAll();
        for (OneBlockProgressPort.OneBlockIsland island : all) {
            counted.putIfAbsent(island.islandId(), new Counted(island));
        }
        Map<IslandId, java.util.List<OneBlockRewardPort.Paid>> paid = new java.util.HashMap<>();
        for (OneBlockRewardPort.Paid one : rewards.findAll()) {
            paid.computeIfAbsent(one.islandId(), id -> new java.util.ArrayList<>())
                    .add(one);
        }
        paid.forEach((islandId, those) -> {
            Counted held = counted.get(islandId);
            if (held != null) {
                remember(held, those);
            }
        });
        primed = true;
        return all.size();
    }

    /**
     * Makes a new island a OneBlock island whose block stands at x, y, z.
     *
     * @return what the block is before anything has been broken, drawn from the first phase
     */
    public String start(IslandId islandId, int x, int y, int z) {
        progress.start(islandId, x, y, z);
        // An island that starts over is owed every reward again.
        rewards.clear(islandId);
        notOneBlock.remove(islandId);
        counted.put(islandId, new Counted(new OneBlockProgressPort.OneBlockIsland(islandId, x, y, z, 0)));
        return phases.nextBlock(0, random);
    }

    /**
     * Adds every island's unwritten breaks to its stored count. An island whose write fails keeps its
     * breaks for the next time; the others are written regardless.
     *
     * @return how many islands were written
     */
    public int flush() {
        int written = 0;
        for (Map.Entry<IslandId, Counted> entry : counted.entrySet()) {
            if (writeNow(entry.getKey(), entry.getValue())) {
                written++;
            }
        }
        return written;
    }

    /** Writes what the island has counted and lets go of it, as when it is deleted or leaves this node. */
    public void forgetIsland(IslandId islandId) {
        notOneBlock.remove(islandId);
        // Forgetting is also what a change on another node asks for, and the island may well still be a
        // OneBlock island: it is read again rather than taken for an ordinary one.
        forgotten.add(islandId);
        Counted held = counted.remove(islandId);
        if (held == null) {
            return;
        }
        writeNow(islandId, held);
    }

    private @Nullable Counted held(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Counted held = counted.get(islandId);
        if (held != null) {
            return held;
        }
        if ((primed && !forgotten.contains(islandId)) || notOneBlock.contains(islandId)) {
            return null;
        }
        forgotten.remove(islandId);
        Optional<OneBlockProgressPort.OneBlockIsland> stored = progress.find(islandId);
        if (stored.isEmpty()) {
            notOneBlock.add(islandId);
            return null;
        }
        java.util.List<OneBlockRewardPort.Paid> paid = rewards.find(islandId);
        return counted.computeIfAbsent(islandId, id -> {
            Counted read = new Counted(stored.get());
            remember(read, paid);
            return read;
        });
    }
}
