package com.uxplima.uxmskyblock.core.domain.oneblock;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.random.RandomGenerator;

/**
 * The phases a OneBlock island goes through, in order, and what happens after the last.
 *
 * <p>A phase lasts a number of breaks. The count is the island's whole life, so the phase a count
 * falls in is found by walking the phases. Past the last one the island either stays in it or starts
 * again from the first, as the operator chooses.
 */
public record OneBlockPhases(List<OneBlockPhase> phases, AfterTheLast afterTheLast) {

    /** What an island that has broken its way through every phase does next. */
    public enum AfterTheLast {
        /** It stays in the last phase for good. */
        STAY,
        /** It goes back to the first phase, and the phases come round again. */
        REPEAT
    }

    public OneBlockPhases {
        Objects.requireNonNull(phases, "phases");
        Objects.requireNonNull(afterTheLast, "afterTheLast");
        if (phases.isEmpty()) {
            throw new IllegalArgumentException("OneBlock needs at least one phase");
        }
        Set<String> seen = new HashSet<>();
        for (OneBlockPhase phase : phases) {
            if (!seen.add(phase.key())) {
                throw new IllegalArgumentException("Two phases are both called " + phase.key());
            }
        }
        phases = List.copyOf(phases);
    }

    /** How many breaks one pass through every phase takes. */
    public long cycleLength() {
        long total = 0;
        for (OneBlockPhase phase : phases) {
            total = Math.addExact(total, phase.blocks());
        }
        return total;
    }

    /** How many breaks it takes to reach the phase {@code key} on the first pass, or nothing for no such phase. */
    public java.util.OptionalLong startOf(String key) {
        Objects.requireNonNull(key, "key");
        long before = 0;
        for (OneBlockPhase phase : phases) {
            if (phase.key().equals(key)) {
                return java.util.OptionalLong.of(before);
            }
            before = Math.addExact(before, phase.blocks());
        }
        return java.util.OptionalLong.empty();
    }

    /**
     * Which pass through the phases the block after {@code broken} breaks belongs to, from 0. An island that stays in
     * the last phase never starts another.
     */
    public long cycleOf(long broken) {
        if (broken < 0) {
            throw new IllegalArgumentException("A count of breaks cannot be negative: " + broken);
        }
        return afterTheLast == AfterTheLast.REPEAT ? broken / cycleLength() : 0;
    }

    /** Where an island that has broken {@code broken} blocks stands. */
    public Position positionAt(long broken) {
        if (broken < 0) {
            throw new IllegalArgumentException("A count of breaks cannot be negative: " + broken);
        }
        long cycle = cycleLength();
        long within = broken;
        if (broken >= cycle) {
            if (afterTheLast == AfterTheLast.STAY) {
                OneBlockPhase last = phases.getLast();
                return new Position(phases.size() - 1, last, last.blocks() + (broken - cycle), last.blocks());
            }
            within = broken % cycle;
        }
        for (int index = 0; index < phases.size(); index++) {
            OneBlockPhase phase = phases.get(index);
            if (within < phase.blocks()) {
                return new Position(index, phase, within, phase.blocks());
            }
            within -= phase.blocks();
        }
        throw new IllegalStateException("A count inside the cycle fell outside every phase: " + broken);
    }

    /** What the block becomes once the island has broken {@code broken} blocks. */
    public String nextBlock(long broken, RandomGenerator random) {
        return positionAt(broken).phase().blockPool().draw(random).orElseThrow();
    }

    /** The creature that appears after break number {@code broken}, if one does. */
    public Optional<String> nextCreature(long broken, RandomGenerator random) {
        OneBlockPhase phase = positionAt(broken).phase();
        if (phase.creaturePool().isEmpty() || random.nextDouble() >= phase.creatureChance()) {
            return Optional.empty();
        }
        return phase.creaturePool().draw(random);
    }

    /** Whether break number {@code broken}, counted from zero, is the first of a new phase. */
    public boolean startsAPhase(long broken) {
        return broken > 0 && positionAt(broken).intoPhase() == 0;
    }

    /**
     * Where a count of breaks stands.
     *
     * @param index which phase, from zero
     * @param phase the phase itself
     * @param intoPhase how many breaks into the phase
     * @param phaseLength how many breaks the phase lasts
     */
    public record Position(int index, OneBlockPhase phase, long intoPhase, long phaseLength) {}
}
