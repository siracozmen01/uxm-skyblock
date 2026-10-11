package com.uxplima.uxmskyblock.core.application.boundary;

import java.util.Map;
import java.util.Optional;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/** How each island chose to have its edge drawn. An island that never chose has nothing here. */
public interface IslandEdgePort {

    /** The style the island chose, or nothing when it keeps the server's default. */
    Optional<String> find(IslandId islandId);

    /** Every island's choice, to hold in memory before anybody steps near an edge. */
    Map<IslandId, String> findAll();

    /** Keeps {@code style} as the island's choice, in place of any it made before. */
    void keep(IslandId islandId, String style);
}
