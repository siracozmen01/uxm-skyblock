package com.uxplima.uxmskyblock.core.application.oneblock;

import java.util.Optional;
import java.util.logging.Level;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.oneblock.OneBlockPhase;
import com.uxplima.uxmskyblock.core.domain.oneblock.OneBlockPhases;

/**
 * Where an island's way through the phases forks: the phases ahead, the pick an owner makes where a phase branches, and that pick written. Split out of {@link OneBlockService}, whose services and words it uses, so neither file is over the size the
 * standards draw a line at.
 */
final class OneBlockBranching {

    private static final java.util.logging.Logger LOGGER =
            java.util.logging.Logger.getLogger(OneBlockBranching.class.getName());

    private final OneBlockService host;

    OneBlockBranching(OneBlockService host) {
        this.host = host;
    }

    /**
     * The phases the island may pick from to follow the one it stands in, when its next break would end that phase
     * and it has not picked yet; none otherwise.
     */
    public java.util.List<String> branchAhead(IslandId islandId) {
        CountedIsland held = host.memory.held(islandId);
        if (held == null) {
            return java.util.List.of();
        }
        OneBlockPhases way = host.memory.routeOf(held);
        long total = held.total.get();
        if (!way.startsAPhase(total + 1)) {
            return java.util.List.of();
        }
        String standing = way.positionAt(total).phase().key();
        return held.picks.containsKey(standing) ? java.util.List.of() : host.phases.optionsAfter(standing);
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
        CountedIsland held = host.memory.held(islandId);
        if (held == null
                || !host.phases.optionsAfter(phase).contains(chosen)
                || held.picks.putIfAbsent(phase, chosen) != null) {
            return false;
        }
        held.route = null;
        OneBlockService.drawAgain(held);
        return true;
    }
    /**
     * Writes the island's pick for {@code phase} as it stands in memory. Run off the region threads.
     *
     * @return whether it was written
     */
    public boolean storePick(IslandId islandId, String phase) {
        CountedIsland held = host.memory.held(islandId);
        String chosen = held == null ? null : held.picks.get(phase);
        if (chosen == null) {
            return false;
        }
        try {
            host.branches.pick(new OneBlockBranchPort.Pick(islandId, phase, chosen));
            return true;
        } catch (RuntimeException failed) {
            LOGGER.log(
                    Level.WARNING,
                    failed,
                    () -> "Writing the OneBlock pick of island " + islandId + " failed; it is kept in memory.");
            return false;
        }
    }
    /** The phase the island's next break begins, if it begins one. */
    public Optional<OneBlockPhase> phaseAhead(IslandId islandId) {
        CountedIsland held = host.memory.held(islandId);
        if (held == null) {
            return Optional.empty();
        }
        long next = held.total.get() + 1;
        OneBlockPhases way = host.memory.routeOf(held);
        return way.startsAPhase(next) ? Optional.of(way.positionAt(next).phase()) : Optional.empty();
    }
}
