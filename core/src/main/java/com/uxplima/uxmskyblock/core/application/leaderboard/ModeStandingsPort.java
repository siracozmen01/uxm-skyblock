package com.uxplima.uxmskyblock.core.application.leaderboard;

import java.util.List;
import java.util.Objects;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/**
 * Where the islands of each mode stand, as storage holds it, for the boards. Each reading asks storage, off the
 * main thread, and keeps nothing.
 */
public interface ModeStandingsPort {

    /**
     * One island's standing.
     *
     * @param islandId the island
     * @param name what the board calls it: its own name, or the start of its id when nobody named it
     * @param value what it is ranked by
     */
    record Standing(IslandId islandId, String name, long value) {

        public Standing {
            Objects.requireNonNull(islandId, "islandId must not be null");
            Objects.requireNonNull(name, "name must not be null");
        }
    }

    /** The OneBlock islands that have broken most, the most first, as last written. */
    List<Standing> mostBroken(int limit);

    /** The Parkour courses by the best time anybody finished them in, in milliseconds, the fastest first. */
    List<Standing> fastestCourses(int limit);

    /** The Boxed islands by the blocks their advancements earned them, the most first. */
    List<Standing> mostEarned(int limit);

    /** The ChunkBlock islands by the chunks they have open, the one they started with among them, the most first. */
    List<Standing> mostChunks(int limit);
}
