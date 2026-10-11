package com.uxplima.uxmskyblock.core.application.chunkblock;

import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkPayments;
import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkPos;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/**
 * Where what has been paid toward each closed chunk of a ChunkBlock island is kept, and each island's chunk points.
 * Every call reads or writes rows, off the main thread.
 */
public interface ChunkPaymentPort {

    /** What has been paid toward opening {@code chunk}, nothing for a chunk nobody has paid toward. */
    ChunkPayments find(IslandId islandId, ChunkPos chunk);

    /** Adds {@code amount} to what has been paid of the line kept under {@code key}. */
    void add(IslandId islandId, ChunkPos chunk, String key, long amount);

    /** Forgets what was paid toward {@code chunk}, once it is open. */
    void clear(IslandId islandId, ChunkPos chunk);

    /** The island's chunk points. */
    long points(IslandId islandId);

    /** Gives the island {@code amount} chunk points. */
    void addPoints(IslandId islandId, long amount);

    /**
     * Takes {@code amount} chunk points from the island, and answers whether it held that many. An island that holds
     * fewer loses none.
     */
    boolean spendPoints(IslandId islandId, long amount);

    /**
     * Charges the island's bank and records the payment toward the chunk in one transaction, so neither happens
     * without the other. A store that can do both at once answers the bank's outcome; one that cannot answers empty,
     * and the caller charges and records in two steps, giving the charge back when the record fails.
     */
    default java.util.Optional<com.uxplima.uxmskyblock.core.domain.bank.BankTransactionOutcome> chargeIslandAndRecord(
            ChunkIslandCharge charge) {
        return java.util.Optional.empty();
    }
}
