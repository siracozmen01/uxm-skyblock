package com.uxplima.uxmskyblock.core.application.chunkblock;

import java.util.Objects;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkPos;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/**
 * One payment toward a chunk out of the island's bank: the charge and the payment it records, which happen
 * together or not at all.
 *
 * @param lineKey the cost line the payment counts toward
 * @param currencyColumn the bank column charged, {@code PRIMARY} for the island's own money
 * @param scale the column's scale, two for the island's money and none for a currency
 * @param amount how much is charged and recorded, in the column's units
 * @param expectedVersion the bank version read before the charge, which the charge requires to be there still
 */
public record ChunkIslandCharge(
        IslandId islandId,
        ChunkPos chunk,
        String lineKey,
        UUID actorUuid,
        String currencyColumn,
        int scale,
        long amount,
        String reason,
        String currentNode,
        long expectedEpoch,
        long expectedVersion,
        UUID operationId,
        String idempotencyKey) {

    public ChunkIslandCharge {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(chunk, "chunk must not be null");
        Objects.requireNonNull(lineKey, "lineKey must not be null");
        Objects.requireNonNull(actorUuid, "actorUuid must not be null");
        Objects.requireNonNull(currencyColumn, "currencyColumn must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(currentNode, "currentNode must not be null");
        Objects.requireNonNull(operationId, "operationId must not be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        if (amount < 1) {
            throw new IllegalArgumentException("a charge is at least one: " + amount);
        }
    }
}
