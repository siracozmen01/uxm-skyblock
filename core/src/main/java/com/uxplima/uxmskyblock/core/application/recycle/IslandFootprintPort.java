package com.uxplima.uxmskyblock.core.application.recycle;

import java.util.List;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.recycle.IslandFootprint;

/**
 * Where each island wrote blocks past its own border, kept until the island is cleared.
 *
 * <p>An island was cleared as far as its border reached when it was deleted, in its own world only. What its game
 * mode wrote further out, a SkyGrid's grid or an Upside Down in the Nether, stayed, and the next island in the same
 * place found it.
 */
public interface IslandFootprintPort {

    /** Keeps {@code footprint} for the island: one square per world and centre, the widest written. */
    void record(IslandId islandId, IslandFootprint footprint);

    /** Every square the island wrote in, in no order. */
    List<IslandFootprint> of(IslandId islandId);

    /** Forgets the island's squares, once they are cleared. */
    void forget(IslandId islandId);

    /** Keeps nothing, for a node with no storage behind it. */
    static IslandFootprintPort none() {
        return new IslandFootprintPort() {
            @Override
            public void record(IslandId islandId, IslandFootprint footprint) {}

            @Override
            public List<IslandFootprint> of(IslandId islandId) {
                return List.of();
            }

            @Override
            public void forget(IslandId islandId) {}
        };
    }
}
