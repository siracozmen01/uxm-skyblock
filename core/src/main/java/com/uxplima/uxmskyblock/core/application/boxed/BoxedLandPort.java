package com.uxplima.uxmskyblock.core.application.boxed;

import java.util.OptionalInt;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/**
 * Where it is kept which Boxed islands play on the template's land, and how far out that land is copied.
 *
 * <p>The land is copied in rings of chunks around the chunk an island's centre stands in: ring 0 is that chunk, ring
 * 1 the eight around it, and so on. An island with no row plays on the land its world made where it stands.
 */
public interface BoxedLandPort {

    /** Marks a Boxed island as one played on the template's land, none of it copied yet. A second mark changes nothing. */
    void begin(IslandId islandId);

    /**
     * How many rings around the island's centre hold the template's land, -1 for none yet, or empty for an island on
     * the land its world made.
     */
    OptionalInt rings(IslandId islandId);

    /** Records the rings out to {@code to} copied, when {@code from} is what was kept. False when it was not. */
    boolean copied(IslandId islandId, int from, int to);
}
