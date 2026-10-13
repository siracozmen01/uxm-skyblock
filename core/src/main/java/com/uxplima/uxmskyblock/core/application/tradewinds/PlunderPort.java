package com.uxplima.uxmskyblock.core.application.tradewinds;

import java.time.Instant;
import java.util.Objects;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/** Where every plunder at sea is written down: who took from whom, when and how much. */
public interface PlunderPort {

    /**
     * One plunder.
     *
     * @param raider the vessel whose crew took
     * @param target the vessel they took from
     * @param at when
     * @param items how many items changed holds
     */
    record Plunder(IslandId raider, IslandId target, Instant at, int items) {
        public Plunder {
            Objects.requireNonNull(raider, "raider");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(at, "at");
            if (raider.equals(target)) {
                throw new IllegalArgumentException("a vessel does not plunder itself");
            }
            if (items < 1) {
                throw new IllegalArgumentException("a plunder takes at least one item");
            }
        }
    }

    /** What became of a plunder asked for. */
    enum Outcome {
        /** Both holds were written and the plunder written down. */
        TAKEN,
        /** The raider plundered, or the target was plundered, too recently: nothing moved. */
        TOO_SOON,
        /** A hold moved under it, or a lease is not this node's: nothing moved, and it may be asked again. */
        MOVED
    }

    /** Whether {@code raider} plundered, or {@code target} was plundered, after {@code since}. */
    boolean recent(IslandId raider, IslandId target, Instant since);

    /**
     * Writes both holds over the versions they were read at, under both vessels' leases, and the plunder, in one
     * transaction, unless either vessel took part in a plunder after {@code since}.
     */
    Outcome take(
            Plunder plunder,
            MarketOrdersPort.HoldWrite raiderHold,
            MarketOrdersPort.HoldWrite targetHold,
            Instant since);
}
