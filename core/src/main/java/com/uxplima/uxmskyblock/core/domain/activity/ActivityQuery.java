package com.uxplima.uxmskyblock.core.domain.activity;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * Which lines of an island's feed a reader asks for.
 *
 * @param instanceId the island whose feed is read
 * @param player only the lines of this player, or every line
 * @param types only lines of these kinds; empty reads every kind
 * @param since only lines written at or after this moment, or every line
 */
public record ActivityQuery(
        String instanceId,
        @Nullable UUID player,
        Set<ActivityEventType> types,
        @Nullable Instant since) {

    public ActivityQuery {
        Objects.requireNonNull(instanceId, "instanceId must not be null");
        Objects.requireNonNull(types, "types must not be null");
        types = types.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(types));
    }

    /** Every line of an island's feed. */
    public static ActivityQuery all(String instanceId) {
        return new ActivityQuery(instanceId, null, Set.of(), null);
    }

    /** This query, narrowed to one player's lines, or widened to everyone's with {@code null}. */
    public ActivityQuery withPlayer(@Nullable UUID player) {
        return new ActivityQuery(instanceId, player, types, since);
    }

    /** This query, narrowed to these kinds of line; empty reads every kind. */
    public ActivityQuery withTypes(Set<ActivityEventType> types) {
        return new ActivityQuery(instanceId, player, types, since);
    }

    /** This query, narrowed to lines written at or after {@code since}, or to every line with {@code null}. */
    public ActivityQuery withSince(@Nullable Instant since) {
        return new ActivityQuery(instanceId, player, types, since);
    }

    /** Whether {@code event} is a line this query reads. */
    public boolean reads(ActivityEvent event) {
        return event.instanceId().equals(instanceId)
                && (player == null || player.equals(event.actorPlayer()))
                && (types.isEmpty() || types.contains(event.eventType()))
                && (since == null || !event.createdAt().isBefore(since));
    }
}
