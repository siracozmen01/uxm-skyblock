package com.uxplima.uxmskyblock.core.domain.chunkblock;

import java.util.List;
import java.util.Objects;

/**
 * A grade of chunk: which chunks fall to it, what opening one costs, what skips that cost, and what opening one
 * gives. A server names as many as it likes, as common, rare and epic.
 *
 * @param id the name the file gives it
 * @param name what a player reads, a catalogue key with a leading {@code @} or the operator's own words
 * @param nearest the nearest ring around the island's first chunk it falls on, one for the chunks beside it
 * @param farthest the farthest ring it falls on
 * @param weight how often it falls, against the other grades of the same ring
 * @param cost what opening a chunk of it costs, every line of it
 * @param bypass what opens a chunk of it at once instead, none when nothing does
 * @param rewards what opening a chunk of it fires, as action lines
 */
public record ChunkTier(
        String id,
        String name,
        int nearest,
        int farthest,
        int weight,
        List<ChunkRequirement> cost,
        List<ChunkRequirement> bypass,
        List<String> rewards) {

    public ChunkTier {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(name, "name must not be null");
        if (nearest < 1 || farthest < nearest) {
            throw new IllegalArgumentException(
                    "the grade " + id + " falls on rings " + nearest + " to " + farthest + ", which is none");
        }
        if (weight < 1) {
            throw new IllegalArgumentException("the grade " + id + " weighs at least one");
        }
        cost = List.copyOf(Objects.requireNonNull(cost, "cost must not be null"));
        bypass = List.copyOf(Objects.requireNonNull(bypass, "bypass must not be null"));
        rewards = List.copyOf(Objects.requireNonNull(rewards, "rewards must not be null"));
        if (cost.isEmpty()) {
            throw new IllegalArgumentException("the grade " + id + " costs something");
        }
        if (bypass.stream().anyMatch(line -> !line.paid())) {
            throw new IllegalArgumentException("the grade " + id + " is skipped by paying, not by a level");
        }
    }

    /** Whether a chunk of this grade is held by the level alone, and closes again when the level falls. */
    public boolean held() {
        return cost.stream().noneMatch(ChunkRequirement::paid);
    }

    /** Whether this grade falls on {@code ring}. */
    public boolean fallsOn(int ring) {
        return ring >= nearest && ring <= farthest;
    }
}
