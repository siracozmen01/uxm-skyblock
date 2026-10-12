package com.uxplima.uxmskyblock.core.application.activity;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.uxplima.uxmskyblock.core.application.event.OutboxEventConsumer;
import com.uxplima.uxmskyblock.core.domain.activity.ActivityAmount;
import com.uxplima.uxmskyblock.core.domain.activity.ActivityEventType;
import com.uxplima.uxmskyblock.core.domain.activity.ActivityVisibility;
import com.uxplima.uxmskyblock.core.domain.event.OutboxEventRecord;

/**
 * Writes an island's feed out of the events its bank committed.
 *
 * <p>The bank command wrote the line itself once the move had landed, and a feed that would not write
 * then lost it: the money moved and the island never heard. The bank stages its event in the same
 * transaction as the move, so the event exists exactly when the move does. This reads it off the
 * outbox like any other consumer: a feed that fails leaves the move standing, and the dispatcher asks
 * again later until the line is written.
 */
public final class ActivityFeedProjection implements OutboxEventConsumer {

    /** The name the consumer inbox knows this by, the same on every node, so each line is written once. */
    public static final String CONSUMER_NAME = "activity-feed";

    static final String BANK_TRANSACTION = "ISLAND_BANK_TRANSACTION";

    private static final Pattern PLAYER = Pattern.compile("\"playerUuid\":\"([0-9a-fA-F-]{36})\"");
    private static final Pattern DELTA = Pattern.compile("\"deltaMinorUnits\":(-?\\d+)");
    private static final Pattern CURRENCY = Pattern.compile("\"currency\":\"([A-Za-z0-9_-]+)\"");
    private static final Pattern REASON = Pattern.compile("\"reason\":\"((?:[^\"\\\\]|\\\\.)*)\"");

    private final ActivityFeedService feed;
    private final Function<UUID, Optional<String>> playerNames;

    /**
     * @param playerNames the name a player goes by, when the server knows one; the id is written otherwise
     */
    public ActivityFeedProjection(ActivityFeedService feed, Function<UUID, Optional<String>> playerNames) {
        this.feed = Objects.requireNonNull(feed, "feed must not be null");
        this.playerNames = Objects.requireNonNull(playerNames, "playerNames must not be null");
    }

    @Override
    public void consume(OutboxEventRecord event) {
        if (!BANK_TRANSACTION.equals(event.eventType())) {
            return;
        }
        Optional<String> player = first(PLAYER, event.payload());
        Optional<String> delta = first(DELTA, event.payload());
        if (player.isEmpty() || delta.isEmpty()) {
            // Not a shape this was written for. Throwing would retry it for ever, and no retry changes it.
            return;
        }
        long minorUnits = Long.parseLong(delta.get());
        if (minorUnits == 0) {
            return;
        }
        UUID playerUuid = UUID.fromString(player.get());
        boolean in = minorUnits > 0;
        Optional<String> currency = first(CURRENCY, event.payload())
                .filter(written -> !com.uxplima.uxmskyblock.core.application.bank.BankCurrencies.isPrimary(written));
        String playerName = playerNames.apply(playerUuid).orElse(playerUuid.toString());
        String reason = first(REASON, event.payload())
                .map(ActivityFeedProjection::unescaped)
                .orElse("");
        ActivityEventType type = in ? ActivityEventType.BANK_DEPOSIT : ActivityEventType.BANK_WITHDRAW;
        if (currency.isPresent()) {
            // An operator currency is kept in whole units and stored by its id, which the reader's feed names as the
            // operator names it then: fifty experience does not read as fifty coins, nor as an id.
            feed.recordMove(
                    event.aggregateId(),
                    playerUuid,
                    type,
                    ActivityVisibility.MEMBERS_ONLY,
                    in ? "activity.bank_held_deposit" : "activity.bank_held_withdraw",
                    Map.of(
                            "player",
                            playerName,
                            "amount",
                            Long.toString(Math.abs(minorUnits)),
                            "currency",
                            currency.get(),
                            "reason",
                            reason),
                    new ActivityAmount(currency.get(), minorUnits));
            return;
        }
        feed.recordMove(
                event.aggregateId(),
                playerUuid,
                type,
                ActivityVisibility.MEMBERS_ONLY,
                in ? "activity.bank_deposit" : "activity.bank_withdraw",
                Map.of(
                        "player",
                        playerName,
                        "amount",
                        BigDecimal.valueOf(Math.abs(minorUnits), 2)
                                .stripTrailingZeros()
                                .toPlainString(),
                        "reason",
                        reason),
                new ActivityAmount(ActivityAmount.PRIMARY, minorUnits));
    }

    private static Optional<String> first(Pattern pattern, String payload) {
        Matcher matcher = pattern.matcher(payload);
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    private static String unescaped(String json) {
        return json.replace("\\\"", "\"").replace("\\\\", "\\");
    }
}
