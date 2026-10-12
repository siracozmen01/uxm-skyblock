package com.uxplima.uxmskyblock.core.application.boxed;

import java.util.Map;
import java.util.OptionalLong;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/** Where the Boxed islands and the advancements each has earned are kept. */
public interface BoxedIslandsPort {

    /** Every Boxed island and the blocks its advancements have earned it. */
    Map<IslandId, Long> findAll();

    /** One island's earned blocks, or empty when it is not a Boxed island. */
    OptionalLong find(IslandId islandId);

    /** Records an island as a Boxed island. A second record changes nothing. */
    void add(IslandId islandId);

    /**
     * Records an advancement the island earned, worth {@code blocks}. Returns false when the island had
     * earned it already, which changes nothing.
     */
    boolean earn(IslandId islandId, String advancement, int blocks);

    /**
     * The row an operator's changes to a box are kept under, among the advancements, so every reader that sums
     * what an island earned counts them. No advancement of the game is in this namespace.
     */
    String OPERATOR = "uxmskyblock:operator";

    /** Adds {@code blocks} to the operator's row of the island, or takes them when negative, making the row if absent. */
    void grant(IslandId islandId, long blocks);
}
