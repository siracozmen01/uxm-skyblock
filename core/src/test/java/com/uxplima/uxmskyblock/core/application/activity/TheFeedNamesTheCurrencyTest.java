package com.uxplima.uxmskyblock.core.application.activity;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.domain.activity.ActivityAmount;
import com.uxplima.uxmskyblock.core.domain.activity.ActivityEventType;
import com.uxplima.uxmskyblock.core.domain.activity.ActivityVisibility;
import com.uxplima.uxmskyblock.core.domain.event.EventId;
import com.uxplima.uxmskyblock.core.domain.event.OutboxEventRecord;
import com.uxplima.uxmskyblock.core.domain.event.OutboxStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A bank line says what moved. The island's money is kept in cents and an operator currency in whole units,
 * so fifty experience read as fifty cents, "0.5", until the line named the currency, and then by its id.
 */
class TheFeedNamesTheCurrencyTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-00000000000a");

    private final ActivityFeedService feed = mock(ActivityFeedService.class);
    private final ActivityFeedProjection projection = new ActivityFeedProjection(feed, uuid -> Optional.of("Rex"));

    private static OutboxEventRecord moved(long delta, String currency) {
        String payload = "{\"islandId\":\"i\",\"playerUuid\":\"" + PLAYER + "\",\"deltaMinorUnits\":" + delta
                + ",\"currency\":\"" + currency + "\",\"reason\":\"In\"}";
        return new OutboxEventRecord(
                EventId.random(),
                ActivityFeedProjection.BANK_TRANSACTION,
                "island-1",
                payload,
                OutboxStatus.PENDING,
                null,
                null,
                null,
                0,
                null,
                null,
                Instant.now(),
                null);
    }

    @Test
    @DisplayName("The island's money is written in its units")
    void theIslandsMoney() {
        projection.consume(moved(2550L, "VAULT"));

        verify(feed)
                .recordMove(
                        eq("island-1"),
                        eq(PLAYER),
                        eq(ActivityEventType.BANK_DEPOSIT),
                        eq(ActivityVisibility.MEMBERS_ONLY),
                        eq("activity.bank_deposit"),
                        eq(Map.of("player", "Rex", "amount", "25.5", "reason", "In")),
                        eq(new ActivityAmount(ActivityAmount.PRIMARY, 2550L)));
    }

    @Test
    @DisplayName("An operator currency is written whole, on a line of its own, with its id for the reader to name")
    void anOperatorCurrency() {
        projection.consume(moved(-50L, "experience"));

        verify(feed)
                .recordMove(
                        eq("island-1"),
                        eq(PLAYER),
                        eq(ActivityEventType.BANK_WITHDRAW),
                        eq(ActivityVisibility.MEMBERS_ONLY),
                        eq("activity.bank_held_withdraw"),
                        eq(Map.of("player", "Rex", "amount", "50", "currency", "experience", "reason", "In")),
                        eq(new ActivityAmount("experience", -50L)));
    }
}
