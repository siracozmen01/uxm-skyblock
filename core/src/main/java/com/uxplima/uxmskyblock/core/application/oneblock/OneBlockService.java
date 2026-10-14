package com.uxplima.uxmskyblock.core.application.oneblock;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
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

    /** What an island waits for before its next phase: the wait set and ended, written, and every timed wait the server must end. */
    private final OneBlockWaiting waiting = new OneBlockWaiting(this);

    /** Where an island's way through the phases forks: the phases ahead, the pick an owner makes where a phase branches, and that pick written. */
    private final OneBlockBranching branching = new OneBlockBranching(this);

    /** What the OneBlock service keeps in memory: an island read in once, its count written back when due, every island primed at start, and the draws made ahead along its route. */
    final OneBlockMemory memory = new OneBlockMemory(this);

    private static final Logger LOGGER = Logger.getLogger(OneBlockService.class.getName());

    /**
     * What one break did: what the block becomes, what appears on it, whether a phase began, the phase it finished
     * when one did, the pass through the phases the broken block belonged to, from 0, and the pass the block it turns
     * into belongs to, one more than that when the break went round to the first phase again, and the creatures the
     * break drew ahead, each so many breaks before it comes.
     */
    public record Broken(
            String nextBlock,
            Optional<String> creature,
            OneBlockPhases.Position position,
            boolean phaseBegan,
            Optional<OneBlockPhase> finished,
            long cycle,
            long nextCycle,
            java.util.List<Coming> coming) {

        public Broken {
            coming = java.util.List.copyOf(coming);
        }
    }

    /** A creature drawn ahead of its break: what it is, and how many breaks from now it comes. */
    public record Coming(String creature, long inBreaks) {}

    /** What an operator's change did to an island's count. */
    public record Changed(long was, long now) {}

    final OneBlockProgressPort progress;
    final OneBlockRewardPort rewards;
    final OneBlockWaitPort waits;
    final OneBlockBranchPort branches;

    /** How many breaks ahead each island's draws are made. */
    private volatile int lookAhead;

    volatile OneBlockPhases phases;
    final RandomGenerator random;
    final Map<IslandId, CountedIsland> counted = new ConcurrentHashMap<>();

    /**
     * Whether every OneBlock island is in memory. Once it is, an island not held is not a OneBlock
     * island, and a block broken on it asks the database nothing: the question is answered on the
     * thread that owns the block, where a query would stall the region.
     */
    volatile boolean primed;

    /** Islands let go of since priming, which are read again the next time a block breaks on one. */
    final java.util.Set<IslandId> forgotten = ConcurrentHashMap.newKeySet();

    /** Islands read once and found not to be OneBlock islands, so a block broken on one asks nothing. */
    final java.util.Set<IslandId> notOneBlock = ConcurrentHashMap.newKeySet();

    public OneBlockService(OneBlockProgressPort progress, OneBlockPhases phases, RandomGenerator random) {
        this(progress, OneBlockRewardPort.NONE, phases, random);
    }

    public OneBlockService(
            OneBlockProgressPort progress, OneBlockRewardPort rewards, OneBlockPhases phases, RandomGenerator random) {
        this(progress, rewards, OneBlockWaitPort.NONE, phases, random);
    }

    public OneBlockService(
            OneBlockProgressPort progress,
            OneBlockRewardPort rewards,
            OneBlockWaitPort waits,
            OneBlockPhases phases,
            RandomGenerator random) {
        this(progress, rewards, waits, OneBlockBranchPort.NONE, phases, random);
    }

    public OneBlockService(
            OneBlockProgressPort progress,
            OneBlockRewardPort rewards,
            OneBlockWaitPort waits,
            OneBlockBranchPort branches,
            OneBlockPhases phases,
            RandomGenerator random) {
        this.progress = Objects.requireNonNull(progress, "progress must not be null");
        this.branches = Objects.requireNonNull(branches, "branches must not be null");
        this.rewards = Objects.requireNonNull(rewards, "rewards must not be null");
        this.waits = Objects.requireNonNull(waits, "waits must not be null");
        this.phases = Objects.requireNonNull(phases, "phases must not be null");
        this.random = Objects.requireNonNull(random, "random must not be null");
    }

    /**
     * Goes on with {@code phases}, as an operator's reload rereads them. Each island keeps its count and its picks;
     * its way through the phases and the draws made ahead are worked out again at its next break.
     */
    public void usePhases(OneBlockPhases phases) {
        this.phases = Objects.requireNonNull(phases, "phases must not be null");
        for (CountedIsland held : counted.values()) {
            held.route = null;
            synchronized (held.ahead) {
                held.ahead.clear();
            }
        }
    }

    /** The island as a OneBlock island, read once and then kept, or nothing if it is not one. */
    public Optional<OneBlockProgressPort.OneBlockIsland> island(IslandId islandId) {
        CountedIsland held = memory.held(islandId);
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
        CountedIsland held = counted.get(islandId);
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
        CountedIsland held = memory.held(islandId);
        return held == null
                ? Optional.empty()
                : Optional.of(memory.routeOf(held).positionAt(held.total.get()));
    }

    /** What the island's block is laid as now, drawn from the phase it stands in, or nothing if it is not one. */
    public Optional<String> blockNow(IslandId islandId) {
        CountedIsland held = memory.held(islandId);
        return held == null
                ? Optional.empty()
                : Optional.of(memory.routeOf(held).nextBlock(held.total.get(), random));
    }

    /** Counts one break of the island's block and says what it turns into, or nothing if it is not one. */
    public Optional<Broken> onBreak(IslandId islandId) {
        CountedIsland held = memory.held(islandId);
        if (held == null) {
            return Optional.empty();
        }
        long broken = held.total.incrementAndGet();
        held.unwritten.incrementAndGet();
        OneBlockPhases way = memory.routeOf(held);
        DrawnAhead now;
        java.util.List<Coming> coming = new java.util.ArrayList<>();
        synchronized (held.ahead) {
            while (!held.ahead.isEmpty() && held.ahead.peekFirst().at() < broken) {
                held.ahead.pollFirst();
            }
            DrawnAhead first = held.ahead.peekFirst();
            now = first != null && first.at() == broken ? held.ahead.pollFirst() : memory.draw(way, broken);
            DrawnAhead last = held.ahead.peekLast();
            for (long at = last == null ? broken + 1 : last.at() + 1; at <= broken + lookAhead; at++) {
                DrawnAhead next = memory.draw(way, at);
                held.ahead.addLast(next);
                long in = at - broken;
                next.creature().ifPresent(creature -> coming.add(new Coming(creature, in)));
            }
        }
        return Optional.of(new Broken(
                now.block(),
                now.creature(),
                way.positionAt(broken),
                way.startsAPhase(broken),
                way.startsAPhase(broken)
                        ? Optional.of(way.positionAt(broken - 1).phase())
                        : Optional.empty(),
                way.cycleOf(broken - 1),
                way.cycleOf(broken),
                coming));
    }

    /**
     * Draws each island's blocks and creatures {@code breaks} breaks ahead, so a creature is known before it comes;
     * none draws each break as it happens.
     */
    public void lookAhead(int breaks) {
        if (breaks < 0) {
            throw new IllegalArgumentException("Nothing is drawn behind a break: " + breaks);
        }
        this.lookAhead = breaks;
    }

    /** Lets go of the draws made ahead, which a count set or a way picked no longer follows. */
    static void drawAgain(CountedIsland held) {
        synchronized (held.ahead) {
            held.ahead.clear();
        }
    }

    /**
     * Whether the island is still owed {@code reward} of {@code phase} in {@code cycle}, and if it is, marks it paid
     * in memory, so the reward is paid once. Asks storage nothing: it is answered on the thread that owns the block.
     * A reward paid in a later pass is not owed again in an earlier one, so a count set back pays nothing twice.
     */
    public boolean claim(IslandId islandId, String phase, String reward, long cycle) {
        CountedIsland held = memory.held(islandId);
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

    /** The island's way through the phases, with the phases it picked, if it is a OneBlock island. */
    public Optional<OneBlockPhases> phasesOf(IslandId islandId) {
        CountedIsland held = memory.held(islandId);
        return held == null ? Optional.empty() : Optional.of(memory.routeOf(held));
    }

    /** The island's way through the phases if it is in memory, without reading storage, for a placeholder. */
    public Optional<OneBlockPhases> phasesInMemory(IslandId islandId) {
        CountedIsland held = counted.get(islandId);
        return held == null ? Optional.empty() : Optional.of(memory.routeOf(held));
    }

    /** What the island waits for before its next phase, if it waits. Asks storage nothing. */
    public Optional<OneBlockWaitPort.Waiting> waiting(IslandId islandId) {
        CountedIsland held = memory.held(islandId);
        return held == null ? Optional.empty() : Optional.ofNullable(held.waiting.get());
    }

    private static String paidKey(String phase, String reward) {
        return phase + '\n' + reward;
    }

    static void remember(CountedIsland held, java.util.List<OneBlockRewardPort.Paid> paid) {
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
        CountedIsland held = memory.held(islandId);
        if (held == null) {
            return Optional.empty();
        }
        long was = held.total.getAndSet(count);
        drawAgain(held);
        held.replaced.set(true);
        memory.writeNow(islandId, held);
        return Optional.of(new Changed(was, count));
    }

    /**
     * Adds {@code breaks} to the island's count, or takes them away when negative, never below nothing, as an
     * operator does, and writes it at once.
     *
     * @return the count before and after, or nothing if it is not a OneBlock island
     */
    public Optional<Changed> addBroken(IslandId islandId, long breaks) {
        CountedIsland held = memory.held(islandId);
        if (held == null) {
            return Optional.empty();
        }
        long was;
        long now;
        do {
            was = held.total.get();
            now = Math.max(0, Math.addExact(was, breaks));
        } while (!held.total.compareAndSet(was, now));
        drawAgain(held);
        held.replaced.set(true);
        memory.writeNow(islandId, held);
        return Optional.of(new Changed(was, now));
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
        waits.clear(islandId);
        branches.clear(islandId);
        notOneBlock.remove(islandId);
        counted.put(islandId, new CountedIsland(new OneBlockProgressPort.OneBlockIsland(islandId, x, y, z, 0)));
        return phases.route(Map.of()).nextBlock(0, random);
    }

    /**
     * Adds every island's unwritten breaks to its stored count. An island whose write fails keeps its
     * breaks for the next time; the others are written regardless.
     *
     * @return how many islands were written
     */
    public int flush() {
        int written = 0;
        for (Map.Entry<IslandId, CountedIsland> entry : counted.entrySet()) {
            if (memory.writeNow(entry.getKey(), entry.getValue())) {
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
        CountedIsland held = counted.remove(islandId);
        if (held == null) {
            return;
        }
        memory.writeNow(islandId, held);
    }

    /**
     * Reads every OneBlock island into memory. Run off the region threads when the server starts;
     * until it has, an island is read the first time a block breaks on it.
     *
     * @return how many OneBlock islands there are
     */
    public int prime() {
        return memory.prime();
    }

    /**
     * The phases the island may pick from to follow the one it stands in, when its next break would end that phase
     * and it has not picked yet; none otherwise.
     */
    public java.util.List<String> branchAhead(IslandId islandId) {
        return branching.branchAhead(islandId);
    }

    /**
     * Has the island follow {@code phase} with {@code chosen}, in memory; {@link #storePick} writes it.
     *
     * <p>A pick is made once. The island's place is read along its way, so a pick made over an earlier one, by a window
     * left open after the island went on, would move it to another phase.
     *
     * @return whether it was picked: {@code chosen} must be one of the phases that may follow {@code phase}, and the
     *     island must not have picked after {@code phase} yet
     */
    public boolean pick(IslandId islandId, String phase, String chosen) {
        return branching.pick(islandId, phase, chosen);
    }

    /**
     * Writes the island's pick for {@code phase} as it stands in memory. Run off the region threads.
     *
     * @return whether it was written
     */
    public boolean storePick(IslandId islandId, String phase) {
        return branching.storePick(islandId, phase);
    }

    /** The phase the island's next break begins, if it begins one. */
    public Optional<OneBlockPhase> phaseAhead(IslandId islandId) {
        return branching.phaseAhead(islandId);
    }

    /**
     * Has the island wait before {@code phase}, until {@code until} or, when that is none, until the phase's
     * requirements hold. In memory only: {@link #storeWait} writes it, off the region threads.
     *
     * @return whether the island waits now, which it does not when it is no OneBlock island
     */
    public boolean waitBefore(IslandId islandId, String phase, @Nullable Instant until) {
        return waiting.waitBefore(islandId, phase, until);
    }

    /**
     * Ends the island's wait, if it is the one given, in memory; {@link #storeWait} writes it. A wait ended twice,
     * by its timer and by an operator, ends once.
     *
     * @return whether this call ended it
     */
    public boolean endWait(IslandId islandId, OneBlockWaitPort.Waiting ended) {
        return waiting.endWait(islandId, ended);
    }

    /**
     * Writes the island's wait as it stands in memory, or that it waits no longer. Run off the region threads.
     *
     * @return whether it was written
     */
    public boolean storeWait(IslandId islandId) {
        return waiting.storeWait(islandId);
    }

    /** Every island waiting for a time to pass, as held in memory: each wait goes on after a restart. */
    public java.util.List<OneBlockWaitPort.Waiting> timedWaits() {
        return waiting.timedWaits();
    }
}
