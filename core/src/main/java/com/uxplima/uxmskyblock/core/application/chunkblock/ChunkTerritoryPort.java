package com.uxplima.uxmskyblock.core.application.chunkblock;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkPos;
import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkTerritory;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/** Where the chunks each ChunkBlock island has open are kept. */
public interface ChunkTerritoryPort {

    /** Every ChunkBlock island's territory, read once when the server starts. */
    Map<IslandId, ChunkTerritory> findAll();

    /** The island's territory, or empty for an island that is not a ChunkBlock island. */
    Optional<ChunkTerritory> find(IslandId islandId);

    /** Records the chunk the island starts with. A second start keeps the first. */
    void start(IslandId islandId, ChunkPos origin);

    /**
     * Records {@code chunk} as the island's {@code order}th opened chunk, counting from one.
     *
     * @return false when another opening took that place or that chunk first
     */
    boolean open(IslandId islandId, ChunkPos chunk, int order);

    /**
     * Records {@code chunk} as the island's {@code order}th opened chunk, and whether it was paid for, so it stays
     * open whatever the level does.
     *
     * @return false when another opening took that place or that chunk first
     */
    default boolean open(IslandId islandId, ChunkPos chunk, int order, boolean bought) {
        return open(islandId, chunk, order);
    }

    /**
     * Closes these opened chunks, and numbers the ones left from one again in the order they were opened, so the
     * next opening takes the place after them. The chunk the island started with is never closed.
     */
    void close(IslandId islandId, List<ChunkPos> chunks);
}
