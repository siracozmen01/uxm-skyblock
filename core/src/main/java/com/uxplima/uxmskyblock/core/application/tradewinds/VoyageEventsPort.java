package com.uxplima.uxmskyblock.core.application.tradewinds;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/**
 * Where the events a voyage holds wait for their moment: one row for each kind of event of each vessel, planned as
 * it sets sail and taken once when its moment comes.
 */
public interface VoyageEventsPort {

    /** What may happen to a vessel at sea. */
    enum Hazard {
        /** A storm: the voyage takes longer. */
        STORM,
        /** Pirates: the crew pays a toll, or they board. */
        PIRATES,
        /** A find: a loot table into the hold. */
        FIND,
        /** Customs: a lawful port looks through the hold as the vessel arrives. */
        CUSTOMS
    }

    /**
     * An event a voyage holds.
     *
     * @param vessel the vessel at sea
     * @param hazard what happens
     * @param portId the port the voyage is bound for; an event of a voyage that ended another way comes to nothing
     * @param at when it happens
     */
    record Planned(IslandId vessel, Hazard hazard, String portId, Instant at) {
        public Planned {
            Objects.requireNonNull(vessel, "vessel");
            Objects.requireNonNull(hazard, "hazard");
            Objects.requireNonNull(portId, "portId");
            Objects.requireNonNull(at, "at");
        }
    }

    /** Puts {@code events} in place of every event {@code vessel} still held. */
    void plan(IslandId vessel, List<Planned> events);

    /** Every event whose moment has come by {@code now}, of every vessel. */
    List<Planned> due(Instant now);

    /** Takes {@code event} away; says whether it was this call that did, so it happens once. */
    boolean claim(Planned event);

    /**
     * Takes a find away and writes the hold it filled, in one transaction, while the hold stands at the version it
     * was read at under the vessel's lease. Says whether it did.
     */
    boolean stow(Planned find, MarketOrdersPort.HoldWrite hold);

    /** Keeps no events: nothing is planned, and nothing comes. */
    VoyageEventsPort NONE = new VoyageEventsPort() {
        @Override
        public void plan(IslandId vessel, List<Planned> events) {}

        @Override
        public List<Planned> due(Instant now) {
            return List.of();
        }

        @Override
        public boolean claim(Planned event) {
            return false;
        }

        @Override
        public boolean stow(Planned find, MarketOrdersPort.HoldWrite hold) {
            return false;
        }
    };
}
