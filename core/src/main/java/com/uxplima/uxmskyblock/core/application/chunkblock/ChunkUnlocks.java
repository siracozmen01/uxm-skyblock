package com.uxplima.uxmskyblock.core.application.chunkblock;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.uxplima.uxmskyblock.core.application.bank.BankCurrencies;
import com.uxplima.uxmskyblock.core.application.bank.IslandBankPort;
import com.uxplima.uxmskyblock.core.application.bank.IslandBankService;
import com.uxplima.uxmskyblock.core.application.island.IslandAuthorityPort;
import com.uxplima.uxmskyblock.core.domain.bank.BankTransactionOutcome;
import com.uxplima.uxmskyblock.core.domain.bank.IslandBank;
import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkPayments;
import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkPos;
import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkRequirement;
import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkTerritory;
import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkTier;
import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkTiers;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.island.IslandAuthorityRecord;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;

/**
 * Paying for a ChunkBlock chunk and opening it.
 *
 * <p>A chunk's grade prices it. A player hands in what they carry: money, a currency, items. That is taken on the
 * player's own thread and recorded here, and whatever the chunk no longer needed by then goes back to them. What the
 * island pays out of its bank is charged and recorded in one transaction. Chunk points are spent here. A chunk whose
 * every line is paid, and whose level line the island's level meets, opens; one bought by anything but the level
 * stays open whatever the level does. Every call reads or writes rows, off the main thread.
 */
public final class ChunkUnlocks {

    private static final Logger LOGGER = Logger.getLogger(ChunkUnlocks.class.getName());

    /** What a payment or an opening came to. */
    public enum Outcome {
        /** Everything is met and the chunk is open. */
        OPENED,
        /** A part was paid, and the chunk still needs more. */
        PAID,
        /** The line asked for was paid in full already. */
        ALREADY_PAID,
        /** The payer had none of what the line asks for. */
        NOTHING_TAKEN,
        /** Every line is paid, and the island's level is below the level line. */
        LEVEL_TOO_LOW,
        /** The line is not one the chunk's grade asks for. */
        NOT_ON_COST,
        /** The chunk's grade names nothing that skips its cost. */
        NO_BYPASS,
        /** The bank refused the charge. */
        BANK_REFUSED,
        /** The payment could not be recorded, and what was taken is to go back. */
        FAILED,
        ALREADY_OPEN,
        NOT_BESIDE_TERRITORY,
        OUTSIDE_ISLAND,
        NOT_CHUNKBLOCK,
        /** Another opening took the chunk's place first. */
        TAKEN
    }

    /**
     * One closed chunk as it stands to be opened.
     *
     * @param place whether it could be opened now, leaving its cost aside
     * @param ladder the level the ladder names for the island's next held chunk
     * @param points the island's chunk points
     */
    public record Offer(
            ChunkPos chunk, ChunkTier tier, ChunkPayments paid, ChunkTerritory.Unlock place, long ladder, long points) {

        /** What is still to pay of {@code line}. */
        public long remaining(ChunkRequirement line) {
            return paid.remaining(line);
        }

        /** Whether the island at {@code level} meets every line. */
        public boolean met(long level) {
            return paid.meets(tier.cost(), level, ladder);
        }
    }

    /**
     * What a payment came to.
     *
     * @param taken how much of the line counts toward the chunk now
     * @param excess how much was taken that the chunk did not need, to go back to the payer
     * @param opened the grade of the chunk when this payment opened it, for its rewards
     */
    public record Result(Outcome outcome, long taken, long excess, Optional<ChunkTier> opened) {

        static Result of(Outcome outcome) {
            return new Result(outcome, 0, 0, Optional.empty());
        }
    }

    /** Who pays out of the island's bank: the member, and the node the bank checks holds the island. */
    public record IslandPayer(UUID actor, ServerNodeId node) {
        public IslandPayer {
            Objects.requireNonNull(actor, "actor must not be null");
            Objects.requireNonNull(node, "node must not be null");
        }
    }

    private final ChunkBlockService service;
    private final ChunkPaymentPort payments;
    private final ChunkTiers tiers;
    private final IslandBankPort bank;
    private final IslandBankService bankMoves;
    private final IslandAuthorityPort authorities;

    /**
     * @param bank where the island's bank is read; a balance moves only through {@code bankMoves}, or together with
     *     the payment it records
     * @param authorities who holds each island, whose epoch a charge recorded with its payment checks
     */
    public ChunkUnlocks(
            ChunkBlockService service,
            ChunkPaymentPort payments,
            ChunkTiers tiers,
            IslandBankPort bank,
            IslandBankService bankMoves,
            IslandAuthorityPort authorities) {
        this.service = Objects.requireNonNull(service, "service must not be null");
        this.payments = Objects.requireNonNull(payments, "payments must not be null");
        this.tiers = Objects.requireNonNull(tiers, "tiers must not be null");
        this.bank = Objects.requireNonNull(bank, "bank must not be null");
        this.bankMoves = Objects.requireNonNull(bankMoves, "bankMoves must not be null");
        this.authorities = Objects.requireNonNull(authorities, "authorities must not be null");
    }

    public ChunkTiers tiers() {
        return tiers;
    }

    /** {@code chunk} as it stands to be opened, or nothing for an island that is not a ChunkBlock island. */
    public Optional<Offer> offer(IslandId islandId, ChunkPos chunk, IslandBounds bounds) {
        Objects.requireNonNull(chunk, "chunk must not be null");
        Objects.requireNonNull(bounds, "bounds must not be null");
        return service.territory(islandId)
                .map(territory -> new Offer(
                        chunk,
                        tiers.of(islandId, territory.origin(), chunk),
                        payments.find(islandId, chunk),
                        territory.placeFor(chunk, bounds),
                        territory.nextRequirement(service.rules()),
                        payments.points(islandId)));
    }

    /**
     * Records {@code taken} of {@code line}, which a player handed in and which was taken from them already. What the
     * chunk no longer needs comes back as the excess, all of it when nothing could be recorded. The chunk opens once
     * every line is met.
     */
    public Result handIn(
            IslandId islandId, ChunkPos chunk, IslandBounds bounds, ChunkRequirement line, long taken, long level) {
        Objects.requireNonNull(line, "line must not be null");
        if (taken < 0) {
            throw new IllegalArgumentException("nothing taken is not below zero: " + taken);
        }
        synchronized (service.lockOf(islandId)) {
            Optional<Offer> read = offer(islandId, chunk, bounds);
            Optional<Result> refused = refusal(read, line);
            if (refused.isPresent()) {
                return new Result(refused.get().outcome(), 0, taken, Optional.empty());
            }
            Offer offer = read.orElseThrow();
            long remaining = offer.remaining(line);
            if (remaining == 0) {
                return new Result(Outcome.ALREADY_PAID, 0, taken, Optional.empty());
            }
            long counted = Math.min(taken, remaining);
            if (counted == 0) {
                return Result.of(Outcome.NOTHING_TAKEN);
            }
            try {
                payments.add(islandId, chunk, line.key(), counted);
            } catch (RuntimeException failed) {
                LOGGER.log(
                        Level.WARNING,
                        failed,
                        () -> "A payment toward a chunk of island " + islandId
                                + " could not be recorded, so it goes back.");
                return new Result(Outcome.FAILED, 0, taken, Optional.empty());
            }
            return settle(islandId, chunk, bounds, offer, line, counted, taken - counted, level);
        }
    }

    /**
     * Pays {@code line} out of the island: its bank for money or a currency kept there, its chunk points for points.
     * As much is paid as the chunk needs and the island holds. The chunk opens once every line is met.
     */
    public Result payFromIsland(
            IslandId islandId,
            ChunkPos chunk,
            IslandBounds bounds,
            ChunkRequirement line,
            IslandPayer payer,
            long level) {
        Objects.requireNonNull(line, "line must not be null");
        Objects.requireNonNull(payer, "payer must not be null");
        synchronized (service.lockOf(islandId)) {
            Optional<Offer> read = offer(islandId, chunk, bounds);
            Optional<Result> refused = refusal(read, line);
            if (refused.isPresent()) {
                return refused.get();
            }
            Offer offer = read.orElseThrow();
            long remaining = offer.remaining(line);
            if (remaining == 0) {
                return Result.of(Outcome.ALREADY_PAID);
            }
            Optional<Result> paid =
                    switch (line) {
                        case ChunkRequirement.Points points ->
                            Optional.of(spendPoints(islandId, chunk, line, remaining));
                        case ChunkRequirement.Money money
                        when money.from() == ChunkRequirement.Source.ISLAND ->
                            Optional.of(charge(islandId, chunk, line, BankCurrencies.PRIMARY_COLUMN, remaining, payer));
                        case ChunkRequirement.Currency currency
                        when currency.from() == ChunkRequirement.Source.ISLAND ->
                            Optional.of(charge(
                                    islandId, chunk, line, BankCurrencies.columnOf(currency.id()), remaining, payer));
                        default -> Optional.empty();
                    };
            if (paid.isEmpty()) {
                // A line the player hands in themselves.
                return Result.of(Outcome.NOT_ON_COST);
            }
            if (paid.get().outcome() != Outcome.PAID) {
                return paid.get();
            }
            return settle(islandId, chunk, bounds, offer, line, paid.get().taken(), 0, level);
        }
    }

    /** Opens {@code chunk} if every line is met now: a chunk priced by the level alone opens this way. */
    public Result open(IslandId islandId, ChunkPos chunk, IslandBounds bounds, long level) {
        synchronized (service.lockOf(islandId)) {
            Optional<Offer> read = offer(islandId, chunk, bounds);
            if (read.isEmpty()) {
                return Result.of(Outcome.NOT_CHUNKBLOCK);
            }
            Offer offer = read.get();
            if (offer.place() != ChunkTerritory.Unlock.UNLOCKED) {
                return Result.of(Outcome.valueOf(offer.place().name()));
            }
            if (!offer.met(level)) {
                boolean paidUp = offer.tier().cost().stream().allMatch(line -> offer.remaining(line) == 0);
                return Result.of(paidUp ? Outcome.LEVEL_TOO_LOW : Outcome.PAID);
            }
            return openNow(islandId, chunk, bounds, offer.tier(), offer.tier().held(), 0, 0);
        }
    }

    /**
     * Opens {@code chunk} by its grade's bypass. Each line the player hands in themselves was taken already, in full,
     * and comes in {@code handedIn}; each line out of the island is charged here, in full. When the chunk does not
     * open, what was charged here goes back, and the caller gives back everything handed in. A chunk opened so is
     * bought.
     */
    public Result bypass(
            IslandId islandId,
            ChunkPos chunk,
            IslandBounds bounds,
            List<ChunkRequirement> handedIn,
            IslandPayer payer) {
        Objects.requireNonNull(handedIn, "handedIn must not be null");
        Objects.requireNonNull(payer, "payer must not be null");
        synchronized (service.lockOf(islandId)) {
            Optional<Offer> read = offer(islandId, chunk, bounds);
            if (read.isEmpty()) {
                return Result.of(Outcome.NOT_CHUNKBLOCK);
            }
            Offer offer = read.get();
            if (offer.place() != ChunkTerritory.Unlock.UNLOCKED) {
                return Result.of(Outcome.valueOf(offer.place().name()));
            }
            List<ChunkRequirement> bypass = offer.tier().bypass();
            if (bypass.isEmpty()) {
                return Result.of(Outcome.NO_BYPASS);
            }
            List<Runnable> undo = new ArrayList<>();
            for (ChunkRequirement line : bypass) {
                if (handedIn.contains(line)) {
                    continue;
                }
                Result paid =
                        switch (line) {
                            case ChunkRequirement.Points points -> spendPoints(islandId, null, line, line.amount());
                            case ChunkRequirement.Money money
                            when money.from() == ChunkRequirement.Source.ISLAND ->
                                chargeOnly(islandId, line, BankCurrencies.PRIMARY_COLUMN, line.amount(), payer);
                            case ChunkRequirement.Currency currency
                            when currency.from() == ChunkRequirement.Source.ISLAND ->
                                chargeOnly(
                                        islandId, line, BankCurrencies.columnOf(currency.id()), line.amount(), payer);
                            default -> Result.of(Outcome.NOTHING_TAKEN);
                        };
                if (paid.outcome() != Outcome.PAID || paid.taken() < line.amount()) {
                    undo.forEach(Runnable::run);
                    return Result.of(paid.outcome() == Outcome.PAID ? Outcome.NOTHING_TAKEN : paid.outcome());
                }
                undo.add(() -> giveBack(islandId, line, line.amount(), payer));
            }
            Result opened = openNow(islandId, chunk, bounds, offer.tier(), false, 0, 0);
            if (opened.outcome() != Outcome.OPENED) {
                undo.forEach(Runnable::run);
                return Result.of(opened.outcome());
            }
            return opened;
        }
    }

    /** Why {@code line} cannot be paid toward the chunk {@code read} describes, or nothing when it can. */
    private static Optional<Result> refusal(Optional<Offer> read, ChunkRequirement line) {
        if (read.isEmpty()) {
            return Optional.of(Result.of(Outcome.NOT_CHUNKBLOCK));
        }
        Offer offer = read.get();
        if (offer.place() != ChunkTerritory.Unlock.UNLOCKED) {
            return Optional.of(Result.of(Outcome.valueOf(offer.place().name())));
        }
        if (!line.paid() || !offer.tier().cost().contains(line)) {
            return Optional.of(Result.of(Outcome.NOT_ON_COST));
        }
        return Optional.empty();
    }

    /** After {@code counted} of {@code line} was recorded: the chunk opens if every line is met now. */
    private Result settle(
            IslandId islandId,
            ChunkPos chunk,
            IslandBounds bounds,
            Offer offer,
            ChunkRequirement line,
            long counted,
            long excess,
            long level) {
        ChunkPayments now = offer.paid().with(line, counted);
        if (!now.meets(offer.tier().cost(), level, offer.ladder())) {
            return new Result(Outcome.PAID, counted, excess, Optional.empty());
        }
        return openNow(islandId, chunk, bounds, offer.tier(), offer.tier().held(), counted, excess);
    }

    private Result openNow(
            IslandId islandId,
            ChunkPos chunk,
            IslandBounds bounds,
            ChunkTier tier,
            boolean held,
            long counted,
            long excess) {
        ChunkBlockService.UnlockResult opened = service.open(islandId, chunk, bounds, held);
        if (opened.outcome() != ChunkBlockService.Outcome.UNLOCKED) {
            return new Result(Outcome.valueOf(opened.outcome().name()), counted, excess, Optional.empty());
        }
        try {
            payments.clear(islandId, chunk);
            if (tier.points() > 0) {
                payments.addPoints(islandId, tier.points());
            }
        } catch (RuntimeException failed) {
            // The chunk is open. What was paid toward it is only bookkeeping now, and the points a reward.
            LOGGER.log(
                    Level.WARNING,
                    failed,
                    () -> "A chunk of island " + islandId
                            + " opened, and what was paid toward it or the points it gives could not be written.");
        }
        return new Result(Outcome.OPENED, counted, excess, Optional.of(tier));
    }

    /** Spends up to {@code wanted} chunk points, and records them toward {@code chunk} when there is one. */
    private Result spendPoints(
            IslandId islandId, @org.jspecify.annotations.Nullable ChunkPos chunk, ChunkRequirement line, long wanted) {
        long spend = chunk == null ? wanted : Math.min(wanted, payments.points(islandId));
        if (spend <= 0 || !payments.spendPoints(islandId, spend)) {
            return Result.of(Outcome.NOTHING_TAKEN);
        }
        if (chunk != null) {
            try {
                payments.add(islandId, chunk, line.key(), spend);
            } catch (RuntimeException failed) {
                payments.addPoints(islandId, spend);
                LOGGER.log(
                        Level.WARNING,
                        failed,
                        () -> "Points paid toward a chunk of island " + islandId
                                + " could not be recorded, so they went back.");
                return Result.of(Outcome.FAILED);
            }
        }
        return new Result(Outcome.PAID, spend, 0, Optional.empty());
    }

    /**
     * Charges up to {@code wanted} of {@code column} out of the island's bank and records it toward the chunk, in one
     * transaction where the store can do that, and in two steps where it cannot.
     */
    private Result charge(
            IslandId islandId, ChunkPos chunk, ChunkRequirement line, String column, long wanted, IslandPayer payer) {
        IslandBank held = bank.findBankByIslandId(islandId).orElseGet(() -> bank.createBank(islandId));
        long balance =
                BankCurrencies.PRIMARY_COLUMN.equals(column) ? held.primaryBalanceMinorUnits() : held.heldOf(column);
        long amount = Math.min(wanted, balance);
        if (amount <= 0) {
            return Result.of(Outcome.NOTHING_TAKEN);
        }
        Optional<IslandAuthorityRecord> holder = authorities.findAuthority(islandId);
        if (holder.isEmpty() || !holder.get().authoritativeNode().equals(payer.node())) {
            return Result.of(Outcome.BANK_REFUSED);
        }
        UUID operation = UUID.randomUUID();
        ChunkIslandCharge charge = new ChunkIslandCharge(
                islandId,
                chunk,
                line.key(),
                payer.actor(),
                column,
                scaleOf(column),
                amount,
                "Chunk " + chunk.x() + "," + chunk.z(),
                payer.node().value(),
                holder.get().authorityEpoch(),
                held.version(),
                operation,
                "chunk-" + chunk.x() + "-" + chunk.z() + "-" + operation);
        Optional<BankTransactionOutcome> together = payments.chargeIslandAndRecord(charge);
        if (together.isPresent()) {
            return together.get() instanceof BankTransactionOutcome.Success
                    ? new Result(Outcome.PAID, amount, 0, Optional.empty())
                    : Result.of(Outcome.BANK_REFUSED);
        }
        Result charged = chargeOnly(islandId, line, column, amount, payer);
        if (charged.outcome() != Outcome.PAID) {
            return charged;
        }
        try {
            payments.add(islandId, chunk, line.key(), amount);
        } catch (RuntimeException failed) {
            giveBack(islandId, line, amount, payer);
            LOGGER.log(
                    Level.WARNING,
                    failed,
                    () -> "A bank payment toward a chunk of island " + islandId
                            + " could not be recorded, so it went back.");
            return Result.of(Outcome.FAILED);
        }
        return charged;
    }

    /** Charges {@code amount} of {@code column} out of the island's bank, recording nothing toward a chunk. */
    private Result chargeOnly(IslandId islandId, ChunkRequirement line, String column, long amount, IslandPayer payer) {
        BankTransactionOutcome outcome = bankMoves.move(
                islandId, new PlayerUuid(payer.actor()), column, -amount, "Chunk " + line.key(), payer.node());
        if (outcome instanceof BankTransactionOutcome.Success) {
            return new Result(Outcome.PAID, amount, 0, Optional.empty());
        }
        return Result.of(
                outcome instanceof BankTransactionOutcome.InsufficientFunds
                        ? Outcome.NOTHING_TAKEN
                        : Outcome.BANK_REFUSED);
    }

    /** Gives back to the island what was paid out of it for {@code line}. */
    private void giveBack(IslandId islandId, ChunkRequirement line, long amount, IslandPayer payer) {
        try {
            switch (line) {
                case ChunkRequirement.Points points -> payments.addPoints(islandId, amount);
                case ChunkRequirement.Money money -> refund(islandId, BankCurrencies.PRIMARY_COLUMN, amount, payer);
                case ChunkRequirement.Currency currency ->
                    refund(islandId, BankCurrencies.columnOf(currency.id()), amount, payer);
                default -> {}
            }
        } catch (RuntimeException failed) {
            LOGGER.log(
                    Level.SEVERE,
                    failed,
                    () -> "Giving " + amount + " of " + line.key() + " back to island " + islandId
                            + " failed; it is owed to the island.");
        }
    }

    private void refund(IslandId islandId, String column, long amount, IslandPayer payer) {
        BankTransactionOutcome outcome = bankMoves.move(
                islandId, new PlayerUuid(payer.actor()), column, amount, "Chunk payment given back", payer.node());
        if (!(outcome instanceof BankTransactionOutcome.Success)) {
            LOGGER.severe(() -> "Giving " + amount + " of " + column + " back to island " + islandId + " was refused: "
                    + outcome + ". It is owed to the island.");
        }
    }

    private static int scaleOf(String column) {
        return BankCurrencies.PRIMARY_COLUMN.equals(column) ? BankCurrencies.PRIMARY_SCALE : 0;
    }
}
