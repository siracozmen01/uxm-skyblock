package com.uxplima.uxmskyblock.core.application.oneblock;

import java.util.Optional;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/** Where a OneBlock island's block stands and how many times it has been broken, kept durably. */
public interface OneBlockProgressPort {

    /** A OneBlock island as stored: its block's position and its count of breaks. */
    record OneBlockIsland(IslandId islandId, int x, int y, int z, long blocksBroken) {}

    /** Makes {@code islandId} a OneBlock island whose block stands at x, y, z, with nothing broken yet. */
    void start(IslandId islandId, int x, int y, int z);

    /** Every OneBlock island there is, read once when the server starts. */
    java.util.List<OneBlockIsland> findAll();

    /** The island, if it is a OneBlock island. */
    Optional<OneBlockIsland> find(IslandId islandId);

    /**
     * Adds {@code breaks} to the island's count, on top of whatever it holds now, so two writers that
     * each counted their own breaks never overwrite one another.
     */
    void addBreaks(IslandId islandId, long breaks);

    /** Sets the island's count, as an operator does, whatever it held. A count is never below nothing. */
    void setBreaks(IslandId islandId, long count);
}
