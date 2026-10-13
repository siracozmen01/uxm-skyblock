package com.uxplima.uxmskyblock.core.application.oneblock;

import java.util.List;
import java.util.Objects;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;

/**
 * How many blocks each player broke on a OneBlock island, kept durably, so the panel and the placeholders can tell
 * who broke what after a restart.
 */
public interface OneBlockContributionPort {

    /** The blocks one player broke on one island. */
    record Contribution(IslandId islandId, PlayerUuid player, long breaks) {
        public Contribution {
            Objects.requireNonNull(islandId, "islandId must not be null");
            Objects.requireNonNull(player, "player must not be null");
            if (breaks < 0) {
                throw new IllegalArgumentException("breaks must not be negative: " + breaks);
            }
        }
    }

    /** Every player's breaks on every island, read once when the server starts. */
    List<Contribution> findAll();

    /** Adds {@code breaks}, one or more, to the player's breaks on the island. */
    void add(IslandId islandId, PlayerUuid player, long breaks);

    /** Forgets every player's breaks on the island, as when it starts over. */
    void clear(IslandId islandId);

    /** Keeps nothing. */
    OneBlockContributionPort NONE = new OneBlockContributionPort() {
        @Override
        public List<Contribution> findAll() {
            return List.of();
        }

        @Override
        public void add(IslandId islandId, PlayerUuid player, long breaks) {}

        @Override
        public void clear(IslandId islandId) {}
    };
}
