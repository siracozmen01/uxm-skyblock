package com.uxplima.uxmskyblock.core.application.tradewinds;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;

/**
 * Where the contracts each vessel took are kept: one row for each contract of each vessel, the last time it was
 * taken.
 */
public interface ContractsPort {

    /** Where a taken contract stands. */
    enum State {
        /** Taken and not yet delivered, while there is time. */
        TAKEN,
        /** Delivered in time. */
        DELIVERED,
        /** Not delivered in time. */
        FAILED
    }

    /**
     * A contract a vessel took, as it was when taken, so a file changed since keeps the terms the crew agreed to.
     *
     * @param vessel the vessel that took it
     * @param contractId the contract's key in the file
     * @param to the port it is delivered to
     * @param item the good
     * @param count how many of it
     * @param taker the player who took it
     * @param due when it fails if not delivered
     * @param state where it stands
     */
    record Held(
            IslandId vessel,
            String contractId,
            String to,
            String item,
            int count,
            PlayerUuid taker,
            Instant due,
            State state) {
        public Held {
            Objects.requireNonNull(vessel, "vessel");
            Objects.requireNonNull(contractId, "contractId");
            Objects.requireNonNull(to, "to");
            Objects.requireNonNull(item, "item");
            Objects.requireNonNull(taker, "taker");
            Objects.requireNonNull(due, "due");
            Objects.requireNonNull(state, "state");
        }
    }

    /** Every contract {@code vessel} took, each the last time it took it. */
    List<Held> of(IslandId vessel);

    /** Every contract taken and not delivered by {@code now}, of every vessel. */
    List<Held> overdue(Instant now);

    /**
     * Records {@code held} as taken, over a row of the same contract that is no longer taken; says whether it did,
     * which it does not while the vessel still holds it.
     */
    boolean take(Held held);

    /**
     * Marks the contract delivered and writes the hold the goods left, in one transaction, while it is taken, due
     * after {@code now}, and the hold stands at the version it was read at under the vessel's lease. Says whether it
     * did.
     */
    boolean deliver(IslandId vessel, String contractId, Instant now, MarketOrdersPort.HoldWrite hold);

    /** Marks a contract that is still taken as failed; says whether it was this call that did. */
    boolean fail(IslandId vessel, String contractId);

    /** Keeps no contracts: nothing is taken, and taking one says it was not. */
    ContractsPort NONE = new ContractsPort() {
        @Override
        public List<Held> of(IslandId vessel) {
            return List.of();
        }

        @Override
        public List<Held> overdue(Instant now) {
            return List.of();
        }

        @Override
        public boolean take(Held held) {
            return false;
        }

        @Override
        public boolean deliver(IslandId vessel, String contractId, Instant now, MarketOrdersPort.HoldWrite hold) {
            return false;
        }

        @Override
        public boolean fail(IslandId vessel, String contractId) {
            return false;
        }
    };
}
