package com.uxplima.uxmskyblock.core.domain.activity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Adds up what bank lines moved, by player, way and currency. */
public final class ActivityTotals {

    private ActivityTotals() {}

    /**
     * The totals of {@code events}, largest first. A line that names no player or moved nothing is left
     * out: it has nobody to be added to.
     */
    public static List<ActivityTotal> of(List<ActivityEvent> events) {
        Map<Key, long[]> sums = new LinkedHashMap<>();
        for (ActivityEvent event : events) {
            ActivityAmount amount = event.amount();
            UUID player = event.actorPlayer();
            if (amount == null || player == null) {
                continue;
            }
            long[] sum =
                    sums.computeIfAbsent(new Key(player, event.eventType(), amount.currency()), key -> new long[2]);
            sum[0] = Math.addExact(sum[0], Math.abs(amount.minorUnits()));
            sum[1]++;
        }
        List<ActivityTotal> totals = new ArrayList<>(sums.size());
        sums.forEach(
                (key, sum) -> totals.add(new ActivityTotal(key.player, key.type, key.currency, sum[0], (int) sum[1])));
        totals.sort(ORDER);
        return List.copyOf(totals);
    }

    /** Largest first, then by player, way and currency, so two equal sums always come out the same way round. */
    public static final Comparator<ActivityTotal> ORDER = Comparator.comparingLong(ActivityTotal::minorUnits)
            .reversed()
            .thenComparing(total -> total.player().toString())
            .thenComparing(ActivityTotal::type)
            .thenComparing(ActivityTotal::currency);

    private record Key(UUID player, ActivityEventType type, String currency) {}
}
