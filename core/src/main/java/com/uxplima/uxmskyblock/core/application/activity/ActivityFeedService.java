package com.uxplima.uxmskyblock.core.application.activity;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.domain.activity.ActivityAmount;
import com.uxplima.uxmskyblock.core.domain.activity.ActivityEvent;
import com.uxplima.uxmskyblock.core.domain.activity.ActivityEventType;
import com.uxplima.uxmskyblock.core.domain.activity.ActivityQuery;
import com.uxplima.uxmskyblock.core.domain.activity.ActivityTotal;
import com.uxplima.uxmskyblock.core.domain.activity.ActivityVisibility;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import org.jspecify.annotations.Nullable;

/**
 * Domain application service recording and reading user-facing activity digests (Section 2.19 & 2.42).
 */
public final class ActivityFeedService {

    private final ActivityFeedStoragePort storagePort;
    private volatile java.util.function.Function<ProfileId, java.util.Optional<UUID>> players =
            profile -> java.util.Optional.empty();

    public ActivityFeedService(ActivityFeedStoragePort storagePort) {
        this.storagePort = Objects.requireNonNull(storagePort, "storagePort must not be null");
    }

    /**
     * Tells the feed which player a profile is, so each line names the player who did it and the feed can be
     * narrowed to one member. A line whose profile names nobody the server knows is written without one.
     */
    public void usePlayers(java.util.function.Function<ProfileId, java.util.Optional<UUID>> players) {
        this.players = Objects.requireNonNull(players, "players must not be null");
    }

    public ActivityEvent recordActivity(
            String instanceId,
            @Nullable ProfileId actorProfileId,
            ActivityEventType eventType,
            ActivityVisibility visibility,
            String payloadTypeId,
            int payloadSchemaVersion,
            String payloadData) {

        Objects.requireNonNull(instanceId, "instanceId must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(visibility, "visibility must not be null");
        Objects.requireNonNull(payloadTypeId, "payloadTypeId must not be null");
        Objects.requireNonNull(payloadData, "payloadData must not be null");

        ActivityEvent event = new ActivityEvent(
                UUID.randomUUID(),
                instanceId,
                actorProfileId,
                eventType,
                visibility,
                payloadTypeId,
                payloadSchemaVersion,
                payloadData,
                Instant.now(),
                actorProfileId == null ? null : players.apply(actorProfileId).orElse(null),
                null);

        storagePort.appendEvent(event);
        return event;
    }

    /**
     * Writes down something that happened on an island, for whoever looks at its feed later.
     *
     * <p>The feed, its table, its twelve event types and the command that reads it have been here
     * since the activity work, and nothing ever wrote a row: recordActivity had no caller anywhere,
     * so every island's feed was empty for as long as the server ran.
     *
     * @param messageKey the message in the operator's catalogue, never a sentence
     * @param values what that message has holes for
     */
    public ActivityEvent record(
            String instanceId,
            @Nullable ProfileId actorProfileId,
            ActivityEventType eventType,
            ActivityVisibility visibility,
            String messageKey,
            java.util.Map<String, String> values) {
        Objects.requireNonNull(messageKey, "messageKey must not be null");
        Objects.requireNonNull(values, "values must not be null");
        return recordActivity(
                instanceId,
                actorProfileId,
                eventType,
                visibility,
                messageKey,
                1,
                com.uxplima.uxmskyblock.core.domain.message.MessagePayload.pack(values));
    }

    /**
     * Writes down a bank line: who moved how much of which currency, beside the sentence a reader sees.
     *
     * <p>The sentence alone could be read but not added up, and it named its player only by name, so
     * the feed could neither be narrowed to one member nor say who put in and who took out how much.
     */
    public ActivityEvent recordMove(
            String instanceId,
            UUID player,
            ActivityEventType eventType,
            ActivityVisibility visibility,
            String messageKey,
            java.util.Map<String, String> values,
            ActivityAmount amount) {
        Objects.requireNonNull(instanceId, "instanceId must not be null");
        Objects.requireNonNull(player, "player must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(visibility, "visibility must not be null");
        Objects.requireNonNull(messageKey, "messageKey must not be null");
        Objects.requireNonNull(values, "values must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        ActivityEvent event = new ActivityEvent(
                UUID.randomUUID(),
                instanceId,
                null,
                eventType,
                visibility,
                messageKey,
                1,
                com.uxplima.uxmskyblock.core.domain.message.MessagePayload.pack(values),
                Instant.now(),
                player,
                amount);
        storagePort.appendEvent(event);
        return event;
    }

    /** One page of the lines {@code query} reads, newest first, and how many it reads in all. */
    public ActivityPage page(ActivityQuery query, int page, int size) {
        Objects.requireNonNull(query, "query must not be null");
        int perPage = Math.max(1, size);
        int total = storagePort.count(query);
        int pages = Math.max(1, (total + perPage - 1) / perPage);
        int shown = Math.clamp(page, 0, pages - 1);
        return new ActivityPage(storagePort.find(query, shown * perPage, perPage), shown, pages, total);
    }

    /** What the bank lines {@code query} reads moved, added up by player, way and currency, largest first. */
    public List<ActivityTotal> totals(ActivityQuery query) {
        Objects.requireNonNull(query, "query must not be null");
        return storagePort.totals(query);
    }

    /**
     * One page of a feed.
     *
     * @param page the page shown, from 0, after a page past the last was brought back to it
     * @param pages how many pages there are, at least one
     * @param total how many lines the query reads in all
     */
    public record ActivityPage(List<ActivityEvent> events, int page, int pages, int total) {
        public ActivityPage {
            events = List.copyOf(events);
        }
    }

    /**
     * Drops the events an island's feed has outgrown.
     *
     * <p>Nothing ever deleted one, which is the other half of a table that has finally started
     * being written to.
     *
     * @return how many were deleted
     */
    public int purgeOlderThan(Instant before) {
        Objects.requireNonNull(before, "before must not be null");
        return storagePort.purgeEventsBefore(before);
    }

    public List<ActivityEvent> getRecentActivities(String instanceId, int limit) {
        Objects.requireNonNull(instanceId, "instanceId must not be null");
        return storagePort.findEventsByInstanceId(instanceId, Math.max(1, limit));
    }
}
