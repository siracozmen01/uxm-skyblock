package com.uxplima.uxmskyblock.core.application.tradewinds;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.SplittableRandom;

/**
 * The goods each port wants more of today: so many of those it buys, drawn afresh each day, for which it pays
 * {@code bonus} more. The draw is made from the port and the day alone, so every node and every restart wants the
 * same goods on the same day and nothing is stored.
 *
 * @param goods how many goods each port wants more of a day
 * @param bonus how much more it pays for them, as a share of the price
 * @param zone the time zone a day begins in
 */
public record DailyDemand(int goods, double bonus, ZoneId zone) {

    /** No port wants anything more than any other day. */
    public static final DailyDemand NONE = new DailyDemand(0, 0, ZoneId.of("UTC"));

    public DailyDemand {
        Objects.requireNonNull(zone, "zone");
        if (goods < 0 || goods > 64) {
            throw new IllegalArgumentException("a port wants from 0 to 64 goods a day: " + goods);
        }
        if (!(bonus >= 0 && bonus <= 10)) {
            throw new IllegalArgumentException("the bonus is a share from 0 to 10: " + bonus);
        }
    }

    /** The goods {@code port} wants more of on the day {@code at} falls on. */
    public Set<String> wanted(Port port, Instant at) {
        if (goods == 0 || bonus == 0) {
            return Set.of();
        }
        LocalDate day = at.atZone(zone).toLocalDate();
        List<String> bought = new ArrayList<>(port.goods().stream()
                .filter(Port.Good::bought)
                .map(Port.Good::item)
                .sorted(Comparator.naturalOrder())
                .toList());
        SplittableRandom draw = new SplittableRandom(seed(port.id(), day));
        // A shuffle of the goods in a fixed order, from a seed of the port and the day.
        for (int index = bought.size() - 1; index > 0; index--) {
            int other = draw.nextInt(index + 1);
            String held = bought.get(index);
            bought.set(index, bought.get(other));
            bought.set(other, held);
        }
        return Set.copyOf(bought.subList(0, Math.min(goods, bought.size())));
    }

    /**
     * {@code good} as {@code port} prices it on the day of {@code at}: paying {@code bonus} more when it wants it
     * today, never as much as it asks.
     */
    public Port.Good priced(Port port, Port.Good good, Instant at) {
        if (!wanted(port, at).contains(good.item())) {
            return good;
        }
        long pays = (long) Math.floor(good.pays() * (1 + bonus) + 1e-9);
        if (good.sold()) {
            pays = Math.min(pays, good.asks() - 1);
        }
        return new Port.Good(good.item(), good.lot(), pays, good.asks());
    }

    private static long seed(String portId, LocalDate day) {
        long seed = day.toEpochDay();
        for (byte b : portId.getBytes(StandardCharsets.UTF_8)) {
            seed = seed * 31 + b;
        }
        return seed;
    }
}
