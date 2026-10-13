package com.uxplima.uxmskyblock.core.application.tradewinds;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The prices of each port as its trade moved them, by {@link PriceDrift}. A sale pushes a good's price down and a
 * purchase pushes it up, written to storage at once, so every node and every restart sees the same market. A
 * price read is answered from what this node last read of storage, read again once it is {@code fresh} old.
 *
 * <p>Reads and writes durable storage: call it off the main thread.
 */
public final class MarketPrices {

    /** How many times a push is written again when another node wrote the same good in between. */
    private static final int WRITE_TRIES = 3;

    private record Key(String portId, String item) {}

    private record Read(double pressure, Instant at, Instant readAt) {}

    private final MarketPricesPort port;
    private final PriceDrift drift;
    private final Clock clock;
    private final Duration fresh;
    private final Map<Key, Read> read = new ConcurrentHashMap<>();

    public MarketPrices(MarketPricesPort port, PriceDrift drift, Clock clock, Duration fresh) {
        this.port = Objects.requireNonNull(port, "port");
        this.drift = Objects.requireNonNull(drift, "drift");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.fresh = Objects.requireNonNull(fresh, "fresh");
    }

    /** Prices that never move. */
    public static MarketPrices none() {
        return new MarketPrices(MarketPricesPort.NONE, PriceDrift.NONE, Clock.systemUTC(), Duration.ZERO);
    }

    /** {@code good} as {@code market} prices it now. */
    public Port.Good priced(Port market, Port.Good good) {
        if (!drift.moves()) {
            return good;
        }
        return drift.priced(good, pressure(market.id(), good.item()));
    }

    /** The push on the good now, items sold less items bought, faded since it was written. */
    public double pressure(String portId, String item) {
        Instant now = clock.instant();
        Key key = new Key(portId, item);
        Read held = read.get(key);
        if (held == null || !held.readAt().plus(fresh).isAfter(now)) {
            Optional<MarketPricesPort.Pressure> stored = port.find(portId, item);
            held = new Read(
                    stored.map(MarketPricesPort.Pressure::pressure).orElse(0.0),
                    stored.map(MarketPricesPort.Pressure::at).orElse(now),
                    now);
            read.put(key, held);
        }
        return drift.faded(held.pressure(), Duration.between(held.at(), now));
    }

    /**
     * Counts a trade at {@code market}: {@code items} sold to it, or bought from it when below 0.
     *
     * @return whether the push was written; not when other nodes kept writing the good in between
     */
    public boolean traded(Port market, Port.Good good, int items) {
        if (!drift.moves() || items == 0) {
            return true;
        }
        for (int attempt = 0; attempt < WRITE_TRIES; attempt++) {
            Instant now = clock.instant();
            Optional<MarketPricesPort.Pressure> stored = port.find(market.id(), good.item());
            double was = stored.map(held -> drift.faded(held.pressure(), Duration.between(held.at(), now)))
                    .orElse(0.0);
            long version = stored.map(MarketPricesPort.Pressure::version).orElse(0L) + 1;
            MarketPricesPort.Pressure next =
                    new MarketPricesPort.Pressure(market.id(), good.item(), was + items, now, version);
            if (port.keep(next)) {
                read.put(new Key(market.id(), good.item()), new Read(next.pressure(), now, now));
                return true;
            }
        }
        return false;
    }
}
