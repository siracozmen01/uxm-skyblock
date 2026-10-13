package com.uxplima.uxmskyblock.core.application.boxed;

import java.util.Map;

import com.uxplima.uxmskyblock.core.domain.boxed.BoxSide;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/** Where it is kept which side each Boxed island's box grows toward. */
public interface BoxSidesPort {

    /** Every island that chose a side. One that never chose grows every edge alike and has no entry. */
    Map<IslandId, BoxSide> findAll();

    /** Keeps the island's box growing toward {@code side}. */
    void choose(IslandId islandId, BoxSide side);
}
