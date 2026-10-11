package com.uxplima.uxmskyblock.core.domain.chunkblock;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What has been paid toward opening one chunk, line by line. A line is paid a part at a time, by anybody of the
 * island, and what is paid stays paid until the chunk opens.
 *
 * @param paid what has been paid of each line, by the line's key
 */
public record ChunkPayments(Map<String, Long> paid) {

    public ChunkPayments {
        paid = Map.copyOf(Objects.requireNonNull(paid, "paid must not be null"));
    }

    /** Nothing paid yet. */
    public static ChunkPayments none() {
        return new ChunkPayments(Map.of());
    }

    /** What has been paid of {@code line}. */
    public long of(ChunkRequirement line) {
        return paid.getOrDefault(line.key(), 0L);
    }

    /** What is still to pay of {@code line}: nothing for the level, which is held rather than paid. */
    public long remaining(ChunkRequirement line) {
        return line.paid() ? Math.max(0, line.amount() - of(line)) : 0;
    }

    /** These payments with {@code amount} more paid of {@code line}. */
    public ChunkPayments with(ChunkRequirement line, long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("a payment is not below zero: " + amount);
        }
        Map<String, Long> next = new HashMap<>(paid);
        next.merge(line.key(), amount, Long::sum);
        return new ChunkPayments(next);
    }

    /**
     * Whether {@code cost} is met: every paid line paid in full, and the island's {@code level} at or above every
     * level line, where the ladder prices the next held chunk at {@code ladder}.
     */
    public boolean meets(List<ChunkRequirement> cost, long level, long ladder) {
        for (ChunkRequirement line : cost) {
            if (line instanceof ChunkRequirement.Level held) {
                if (level < held.of(ladder)) {
                    return false;
                }
            } else if (remaining(line) > 0) {
                return false;
            }
        }
        return true;
    }
}
