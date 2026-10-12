package com.uxplima.uxmskyblock.core.domain.activity;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import org.jspecify.annotations.Nullable;

/**
 * Domain entity representing a user-facing activity feed event (Section 2.19 & 2.42).
 *
 * @param actorPlayer the player whose doing the line tells, when it tells one; a feed is filtered by it
 * @param amount what a bank line moved, or nothing for a line that moved nothing
 */
public record ActivityEvent(
        UUID eventId,
        String instanceId,
        @Nullable ProfileId actorProfileId,
        ActivityEventType eventType,
        ActivityVisibility visibility,
        String payloadTypeId,
        int payloadSchemaVersion,
        String payloadData,
        Instant createdAt,
        @Nullable UUID actorPlayer,
        @Nullable ActivityAmount amount) {

    public ActivityEvent {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(instanceId, "instanceId must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(visibility, "visibility must not be null");
        Objects.requireNonNull(payloadTypeId, "payloadTypeId must not be null");
        Objects.requireNonNull(payloadData, "payloadData must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    /** A line that names no player and moved nothing. */
    public ActivityEvent(
            UUID eventId,
            String instanceId,
            @Nullable ProfileId actorProfileId,
            ActivityEventType eventType,
            ActivityVisibility visibility,
            String payloadTypeId,
            int payloadSchemaVersion,
            String payloadData,
            Instant createdAt) {
        this(
                eventId,
                instanceId,
                actorProfileId,
                eventType,
                visibility,
                payloadTypeId,
                payloadSchemaVersion,
                payloadData,
                createdAt,
                null,
                null);
    }
}
