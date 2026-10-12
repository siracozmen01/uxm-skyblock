package com.uxplima.uxmskyblock.core.application.oneblock;

import java.util.List;
import java.util.Objects;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/**
 * What each OneBlock island has been paid, kept durably: for each reward of a phase, a moment's break or one of its
 * lists, the last pass through the phases it was paid in. A count rolled back by a crash or an operator reaches the
 * same reward again in the same pass, and finds it paid.
 */
public interface OneBlockRewardPort {

    /**
     * One reward paid.
     *
     * @param reward what paid it: {@code moment:<break>} or the name of a phase list
     * @param cycle the last pass through the phases it was paid in, from 0
     */
    record Paid(IslandId islandId, String phase, String reward, long cycle) {
        public Paid {
            Objects.requireNonNull(islandId, "islandId must not be null");
            Objects.requireNonNull(phase, "phase must not be null");
            Objects.requireNonNull(reward, "reward must not be null");
        }
    }

    /** Every reward paid to every island, read once when the server starts. */
    List<Paid> findAll();

    /** Every reward paid to the island. */
    List<Paid> find(IslandId islandId);

    /**
     * Records that the island was paid the reward in {@code cycle}, unless it was paid in that pass or a later one.
     *
     * @return whether this call recorded it, so the reward is paid now
     */
    boolean pay(IslandId islandId, String phase, String reward, long cycle);

    /** Forgets everything the island was paid, as when it starts over as a OneBlock island. */
    void clear(IslandId islandId);

    /** Records nothing and refuses nothing, for a server that keeps no ledger. */
    OneBlockRewardPort NONE = new OneBlockRewardPort() {
        @Override
        public List<Paid> findAll() {
            return List.of();
        }

        @Override
        public List<Paid> find(IslandId islandId) {
            return List.of();
        }

        @Override
        public boolean pay(IslandId islandId, String phase, String reward, long cycle) {
            return true;
        }

        @Override
        public void clear(IslandId islandId) {}
    };
}
