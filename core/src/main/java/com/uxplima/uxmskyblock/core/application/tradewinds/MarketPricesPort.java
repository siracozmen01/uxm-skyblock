package com.uxplima.uxmskyblock.core.application.tradewinds;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The push past trade left on each good of each port, kept durably and shared by every node, so a restart keeps
 * the market and two nodes price alike.
 */
public interface MarketPricesPort {

    /**
     * The push on one good of one port: items sold there less items bought there, faded as of {@code at}.
     *
     * @param version how many times the row was written, which a write names to know nothing came between
     */
    record Pressure(String portId, String item, double pressure, Instant at, long version) {
        public Pressure {
            Objects.requireNonNull(portId, "portId must not be null");
            Objects.requireNonNull(item, "item must not be null");
            Objects.requireNonNull(at, "at must not be null");
            if (version < 0) {
                throw new IllegalArgumentException("a version is not below 0: " + version);
            }
        }
    }

    /** The push on the good, if any trade left one. */
    Optional<Pressure> find(String portId, String item);

    /** Every push of every port. */
    List<Pressure> findAll();

    /**
     * Writes {@code next} over the row at {@code next.version() - 1}, or as a new row when that is 0.
     *
     * @return whether it was written; not when another write came first
     */
    boolean keep(Pressure next);

    /** Keeps nothing: prices never move. */
    MarketPricesPort NONE = new MarketPricesPort() {
        @Override
        public Optional<Pressure> find(String portId, String item) {
            return Optional.empty();
        }

        @Override
        public List<Pressure> findAll() {
            return List.of();
        }

        @Override
        public boolean keep(Pressure next) {
            return false;
        }
    };
}
