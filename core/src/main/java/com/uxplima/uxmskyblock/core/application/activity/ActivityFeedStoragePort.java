package com.uxplima.uxmskyblock.core.application.activity;

import java.util.List;

import com.uxplima.uxmskyblock.core.domain.activity.ActivityEvent;
import com.uxplima.uxmskyblock.core.domain.activity.ActivityQuery;
import com.uxplima.uxmskyblock.core.domain.activity.ActivityTotal;
import com.uxplima.uxmskyblock.core.domain.activity.ActivityTotals;

/**
 * Storage port for user-facing activity events (Section 2.19 & 2.42).
 */
public interface ActivityFeedStoragePort {

    void appendEvent(ActivityEvent event);

    List<ActivityEvent> findEventsByInstanceId(String instanceId, int limit);

    /**
     * The lines {@code query} reads, newest first, {@code limit} of them after skipping {@code offset}.
     *
     * <p>This default reads the island's whole feed and filters it, which is right for a store held in
     * memory. A database answers it in the query instead.
     */
    default List<ActivityEvent> find(ActivityQuery query, int offset, int limit) {
        return findEventsByInstanceId(query.instanceId(), Integer.MAX_VALUE).stream()
                .filter(query::reads)
                .skip(Math.max(0, offset))
                .limit(Math.max(0, limit))
                .toList();
    }

    /** How many lines {@code query} reads. */
    default int count(ActivityQuery query) {
        return (int) findEventsByInstanceId(query.instanceId(), Integer.MAX_VALUE).stream()
                .filter(query::reads)
                .count();
    }

    /** What the bank lines {@code query} reads moved, added up by player, way and currency. */
    default List<ActivityTotal> totals(ActivityQuery query) {
        return ActivityTotals.of(findEventsByInstanceId(query.instanceId(), Integer.MAX_VALUE).stream()
                .filter(query::reads)
                .toList());
    }

    /**
     * Deletes the events older than {@code before}.
     *
     * <p>An island's feed is a digest of what happened lately, not a ledger. Nothing ever deleted an
     * event, so the table would have grown for as long as the server ran the moment anything started
     * writing to it.
     *
     * @return how many were deleted
     */
    int purgeEventsBefore(java.time.Instant before);
}
