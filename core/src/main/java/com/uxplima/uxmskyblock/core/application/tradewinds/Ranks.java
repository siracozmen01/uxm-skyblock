package com.uxplima.uxmskyblock.core.application.tradewinds;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The ranks a vessel climbs as it trades, lowest first. A vessel holds the highest rank whose trade it has
 * done, and its rank sets how many rows its cargo hold has.
 */
public record Ranks(List<Rank> ranks) {

    /**
     * One rank.
     *
     * @param id how the file and the language files name it
     * @param volume the trade, in the bank's minor units, a vessel has done to hold it
     * @param holdRows how many rows of nine slots the hold of a vessel of this rank has, from 1 to 6
     */
    public record Rank(String id, long volume, int holdRows) {

        private static final Pattern ID = Pattern.compile("[a-z0-9_-]{1,64}");

        public Rank {
            Objects.requireNonNull(id, "id");
            if (!ID.matcher(id).matches()) {
                throw new IllegalArgumentException("a rank's id is from 1 to 64 of a-z, 0-9, _ and -: " + id);
            }
            if (volume < 0) {
                throw new IllegalArgumentException("a rank's trade is not below 0");
            }
            if (holdRows < 1 || holdRows > 6) {
                throw new IllegalArgumentException("a hold has from 1 to 6 rows");
            }
        }

        /** How many slots the hold of a vessel of this rank has. */
        public int holdSlots() {
            return holdRows * 9;
        }
    }

    public Ranks {
        ranks = List.copyOf(ranks);
        if (ranks.isEmpty() || ranks.get(0).volume() != 0) {
            throw new IllegalArgumentException("the first rank is held from no trade at all");
        }
        for (int i = 1; i < ranks.size(); i++) {
            if (ranks.get(i).volume() <= ranks.get(i - 1).volume()) {
                throw new IllegalArgumentException("every rank takes more trade than the one before it");
            }
            if (ranks.get(i).holdRows() < ranks.get(i - 1).holdRows()) {
                throw new IllegalArgumentException("no rank has a smaller hold than the one before it");
            }
        }
        if (ranks.stream().map(Rank::id).distinct().count() != ranks.size()) {
            throw new IllegalArgumentException("two ranks share an id");
        }
    }

    /** The rank of a vessel that has done {@code volume} of trade. */
    public Rank of(long volume) {
        Rank held = ranks.get(0);
        for (Rank rank : ranks) {
            if (rank.volume() <= volume) {
                held = rank;
            }
        }
        return held;
    }

    /** The rank the file calls {@code id}, if there is one. */
    public Optional<Rank> named(String id) {
        return ranks.stream().filter(rank -> rank.id().equals(id)).findFirst();
    }

    /** The rank after {@code rank}, if there is one. */
    public Optional<Rank> after(Rank rank) {
        int at = ranks.indexOf(rank);
        return at >= 0 && at + 1 < ranks.size() ? Optional.of(ranks.get(at + 1)) : Optional.empty();
    }
}
