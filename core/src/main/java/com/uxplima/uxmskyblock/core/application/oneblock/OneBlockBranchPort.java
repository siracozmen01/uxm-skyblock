package com.uxplima.uxmskyblock.core.application.oneblock;

import java.util.List;
import java.util.Objects;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/**
 * The phase each OneBlock island picked to follow a phase that branches, kept durably: an island's way through the
 * phases is read from its picks, so a restart keeps it on the way it chose.
 */
public interface OneBlockBranchPort {

    /** The phase an island picked to follow {@code phase}. */
    record Pick(IslandId islandId, String phase, String chosen) {
        public Pick {
            Objects.requireNonNull(islandId, "islandId must not be null");
            Objects.requireNonNull(phase, "phase must not be null");
            Objects.requireNonNull(chosen, "chosen must not be null");
        }
    }

    /** Every pick of every island, read once when the server starts. */
    List<Pick> findAll();

    /** Every pick of the island. */
    List<Pick> find(IslandId islandId);

    /** Records the pick, over any the island made for the same phase. */
    void pick(Pick pick);

    /** Forgets every pick of the island, as when it starts over. */
    void clear(IslandId islandId);

    /** Keeps nothing, for a server whose phases never branch. */
    OneBlockBranchPort NONE = new OneBlockBranchPort() {
        @Override
        public List<Pick> findAll() {
            return List.of();
        }

        @Override
        public List<Pick> find(IslandId islandId) {
            return List.of();
        }

        @Override
        public void pick(Pick pick) {}

        @Override
        public void clear(IslandId islandId) {}
    };
}
