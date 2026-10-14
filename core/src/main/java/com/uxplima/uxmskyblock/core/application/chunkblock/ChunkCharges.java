package com.uxplima.uxmskyblock.core.application.chunkblock;

import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;

import com.uxplima.uxmskyblock.core.application.bank.BankCurrencies;
import com.uxplima.uxmskyblock.core.domain.bank.BankTransactionOutcome;
import com.uxplima.uxmskyblock.core.domain.bank.IslandBank;
import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkPos;
import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkRequirement;
import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkTier;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.island.IslandAuthorityRecord;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;

/**
 * What a chunk costs as it opens: the price taken from a purse or the island's bank, points spent, and the chunk opened. Split out of {@link ChunkUnlocks}, whose services and words it uses, so neither file is over the size the
 * standards draw a line at.
 */
final class ChunkCharges {

    private static final java.util.logging.Logger LOGGER =
            java.util.logging.Logger.getLogger(ChunkCharges.class.getName());

    private final ChunkUnlocks host;

    ChunkCharges(ChunkUnlocks host) {
        this.host = host;
    }

    /**
     * Charges up to {@code wanted} of {@code column} out of the island's bank and records it toward the chunk, in one
     * transaction where the store can do that, and in two steps where it cannot.
     */
    ChunkUnlocks.Result charge(
            IslandId islandId,
            ChunkPos chunk,
            ChunkRequirement line,
            String column,
            long wanted,
            ChunkUnlocks.IslandPayer payer) {
        IslandBank held = host.bank.findBankByIslandId(islandId).orElseGet(() -> host.bank.createBank(islandId));
        long balance =
                BankCurrencies.PRIMARY_COLUMN.equals(column) ? held.primaryBalanceMinorUnits() : held.heldOf(column);
        long amount = Math.min(wanted, balance);
        if (amount <= 0) {
            return ChunkUnlocks.Result.of(ChunkUnlocks.Outcome.NOTHING_TAKEN);
        }
        Optional<IslandAuthorityRecord> holder = host.authorities.findAuthority(islandId);
        if (holder.isEmpty() || !holder.get().authoritativeNode().equals(payer.node())) {
            return ChunkUnlocks.Result.of(ChunkUnlocks.Outcome.BANK_REFUSED);
        }
        UUID operation = UUID.randomUUID();
        ChunkIslandCharge charge = new ChunkIslandCharge(
                islandId,
                chunk,
                line.key(),
                payer.actor(),
                column,
                ChunkUnlocks.scaleOf(column),
                amount,
                "Chunk " + chunk.x() + "," + chunk.z(),
                payer.node().value(),
                holder.get().authorityEpoch(),
                held.version(),
                operation,
                "chunk-" + chunk.x() + "-" + chunk.z() + "-" + operation);
        Optional<BankTransactionOutcome> together = host.payments.chargeIslandAndRecord(charge);
        if (together.isPresent()) {
            return together.get() instanceof BankTransactionOutcome.Success
                    ? new ChunkUnlocks.Result(ChunkUnlocks.Outcome.PAID, amount, 0, Optional.empty())
                    : ChunkUnlocks.Result.of(ChunkUnlocks.Outcome.BANK_REFUSED);
        }
        ChunkUnlocks.Result charged = host.chargeOnly(islandId, line, column, amount, payer);
        if (charged.outcome() != ChunkUnlocks.Outcome.PAID) {
            return charged;
        }
        try {
            host.payments.add(islandId, chunk, line.key(), amount);
        } catch (RuntimeException failed) {
            host.giveBack(islandId, line, amount, payer);
            LOGGER.log(
                    Level.WARNING,
                    failed,
                    () -> "A bank payment toward a chunk of island " + islandId
                            + " could not be recorded, so it went back.");
            return ChunkUnlocks.Result.of(ChunkUnlocks.Outcome.FAILED);
        }
        return charged;
    }
    /** Spends up to {@code wanted} chunk points, and records them toward {@code chunk} when there is one. */
    ChunkUnlocks.Result spendPoints(
            IslandId islandId, @org.jspecify.annotations.Nullable ChunkPos chunk, ChunkRequirement line, long wanted) {
        long spend = chunk == null ? wanted : Math.min(wanted, host.payments.points(islandId));
        if (spend <= 0 || !host.payments.spendPoints(islandId, spend)) {
            return ChunkUnlocks.Result.of(ChunkUnlocks.Outcome.NOTHING_TAKEN);
        }
        if (chunk != null) {
            try {
                host.payments.add(islandId, chunk, line.key(), spend);
            } catch (RuntimeException failed) {
                host.payments.addPoints(islandId, spend);
                LOGGER.log(
                        Level.WARNING,
                        failed,
                        () -> "Points paid toward a chunk of island " + islandId
                                + " could not be recorded, so they went back.");
                return ChunkUnlocks.Result.of(ChunkUnlocks.Outcome.FAILED);
            }
        }
        return new ChunkUnlocks.Result(ChunkUnlocks.Outcome.PAID, spend, 0, Optional.empty());
    }

    ChunkUnlocks.Result openNow(
            IslandId islandId,
            ChunkPos chunk,
            IslandBounds bounds,
            ChunkTier tier,
            boolean held,
            long counted,
            long excess) {
        // A chunk held by the level closes when the level falls. Opened again, it gives nothing again.
        boolean rewardedBefore = host.payments.find(islandId, chunk).paid().containsKey(ChunkUnlocks.REWARDED);
        ChunkBlockService.UnlockResult opened = host.service.open(islandId, chunk, bounds, held);
        if (opened.outcome() != ChunkBlockService.Outcome.UNLOCKED) {
            return new ChunkUnlocks.Result(
                    ChunkUnlocks.Outcome.valueOf(opened.outcome().name()), counted, excess, Optional.empty());
        }
        try {
            host.payments.clear(islandId, chunk);
            host.payments.add(islandId, chunk, ChunkUnlocks.REWARDED, 1);
            if (!rewardedBefore && tier.points() > 0) {
                host.payments.addPoints(islandId, tier.points());
            }
        } catch (RuntimeException failed) {
            // The chunk is open. What was paid toward it is only bookkeeping now, and the points a reward.
            LOGGER.log(
                    Level.WARNING,
                    failed,
                    () -> "A chunk of island " + islandId
                            + " opened, and what was paid toward it or the points it gives could not be written.");
        }
        return new ChunkUnlocks.Result(
                ChunkUnlocks.Outcome.OPENED, counted, excess, Optional.of(tier), !rewardedBefore);
    }
}
