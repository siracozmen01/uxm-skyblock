package com.uxplima.uxmskyblock.core.application.tradewinds;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;

/**
 * The orders a vessel's crew placed at the ports' markets, and the trade they add up to.
 *
 * <p>An order is a saga between the vessel's hold, written here under the vessel's lease, and the island
 * bank, which answers a keyed move once. Every state an order passes through is written before the next
 * step is taken:
 * <ul>
 *   <li>A sale takes the goods out of the hold and records the order {@link State#OWED} in one
 *       transaction. The bank then pays, and the order is {@link State#DONE}.</li>
 *   <li>A purchase is recorded {@link State#PAYING}. The bank is paid, and the goods go into the hold with
 *       the order {@link State#DONE} in one transaction. A bank that refuses ends it {@link State#CANCELLED};
 *       a hold with no room for what was paid for has it {@link State#REFUNDING} and then
 *       {@link State#REFUNDED}.</li>
 * </ul>
 *
 * <p>The trade a vessel did and its standing in the port grow with an order as its goods move.
 */
public interface MarketOrdersPort {

    /** Which way the goods of an order go. */
    enum Kind {
        /** From the hold to the port, for money into the bank. */
        SALE,
        /** From the port into the hold, for money out of the bank. */
        PURCHASE
    }

    /** Where an order stands. */
    enum State {
        OWED,
        PAYING,
        REFUNDING,
        DONE,
        CANCELLED,
        REFUNDED;

        /** Whether nothing is left to do for an order in this state. */
        public boolean settled() {
            return this == DONE || this == CANCELLED || this == REFUNDED;
        }
    }

    /**
     * An order.
     *
     * @param id the order, and the root of every key it asks the bank under
     * @param count how many items it moves
     * @param amount what it moves in the bank's minor units, never below 0
     * @param actor whose name the bank writes the move under
     * @param attempts how many keys it has asked the bank under so far
     */
    record Order(
            UUID id,
            IslandId vessel,
            String portId,
            Kind kind,
            String item,
            int count,
            long amount,
            PlayerUuid actor,
            State state,
            int attempts) {
        public Order {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(vessel, "vessel");
            Objects.requireNonNull(portId, "portId");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(item, "item");
            Objects.requireNonNull(actor, "actor");
            Objects.requireNonNull(state, "state");
            if (count < 1 || amount < 0 || attempts < 0) {
                throw new IllegalArgumentException("an order moves at least one item, for no less than nothing");
            }
        }
    }

    /** The hold an order writes, and the lease it writes it under. */
    @SuppressWarnings("ArrayRecordComponent")
    record HoldWrite(long leaseEpoch, ServerNodeId node, long cargoVersion, byte[] cargoAfter) {
        public HoldWrite {
            Objects.requireNonNull(node, "node");
            cargoAfter = cargoAfter.clone();
        }

        @Override
        public byte[] cargoAfter() {
            return cargoAfter.clone();
        }
    }

    /**
     * Writes the hold as a sale leaves it and records {@code order} {@link State#OWED}, with the trade and
     * the standing it adds, in one transaction. Says whether it did: not when the lease or the hold's
     * version moved on.
     */
    boolean recordSale(Order order, HoldWrite hold);

    /** Records {@code order} {@link State#PAYING}, before the bank is asked. */
    void recordPurchase(Order order);

    /**
     * Writes the hold as a paid purchase leaves it and marks the order {@link State#DONE}, with the trade
     * and the standing it adds, in one transaction. Says whether it did.
     */
    boolean deliver(UUID orderId, HoldWrite hold);

    /** Records that {@code orderId} asks the bank under its {@code attempt}th key from now on. */
    void attempt(UUID orderId, int attempt);

    /**
     * Moves {@code orderId} from {@code from} to {@code to}; says whether it stood at {@code from}. An order
     * moved to {@link State#REFUNDING} starts its attempts over, for the keys of the refund.
     */
    boolean settle(UUID orderId, State from, State to);

    /** The order, if there is one. */
    Optional<Order> find(UUID orderId);

    /** Every order of {@code vessel} not yet settled, oldest first. */
    List<Order> open(IslandId vessel);

    /** Every vessel with an order not yet settled. */
    List<IslandId> vesselsWithOpenOrders();

    /** The vessel's standing in {@code portId}: the trade it did there. */
    long standing(IslandId vessel, String portId);

    /** All the trade the vessel did. */
    long tradeVolume(IslandId vessel);

    /** Sets all the trade the vessel did, as an operator does to give it a rank. Says whether it is a vessel. */
    boolean setTradeVolume(IslandId vessel, long volume);

    /** Sets the vessel's standing in {@code portId}, as an operator does. */
    void setStanding(IslandId vessel, String portId, long standing);

    /** A vessel and all the trade it did, with the name its island goes by. */
    record Traded(IslandId vessel, String name, long volume) {
        public Traded {
            Objects.requireNonNull(vessel, "vessel");
            Objects.requireNonNull(name, "name");
        }
    }

    /** The vessels that did the most trade, most first, at most {@code limit} of them. */
    List<Traded> mostTraded(int limit);
}
