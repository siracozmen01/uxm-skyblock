package com.uxplima.uxmskyblock.core.application.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.domain.activity.ActivityAmount;
import com.uxplima.uxmskyblock.core.domain.activity.ActivityEvent;
import com.uxplima.uxmskyblock.core.domain.activity.ActivityEventType;
import com.uxplima.uxmskyblock.core.domain.activity.ActivityQuery;
import com.uxplima.uxmskyblock.core.domain.activity.ActivityTotal;
import com.uxplima.uxmskyblock.core.domain.activity.ActivityVisibility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * An island's feed was the ten newest lines and nothing more. A member could not turn the page, could not ask
 * for one member's lines or one kind of line, and could not ask who put in and who took out how much.
 */
class AFeedIsReadByPageMemberAndKindTest {

    private static final String ISLAND = "island-1";
    private static final UUID ALI = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BERK = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    /** A feed held in memory, read only through the port's own defaults. */
    private static final class Feed implements ActivityFeedStoragePort {
        final List<ActivityEvent> events = new ArrayList<>();

        @Override
        public void appendEvent(ActivityEvent event) {
            events.add(event);
        }

        @Override
        public List<ActivityEvent> findEventsByInstanceId(String instanceId, int limit) {
            return events.stream()
                    .filter(event -> event.instanceId().equals(instanceId))
                    .sorted(Comparator.comparing(ActivityEvent::createdAt).reversed())
                    .limit(limit)
                    .toList();
        }

        @Override
        public int purgeEventsBefore(Instant before) {
            return 0;
        }
    }

    private final Feed store = new Feed();
    private final ActivityFeedService feed = new ActivityFeedService(store);

    private ActivityEvent move(UUID player, ActivityEventType type, String currency, long units) {
        ActivityEvent event = feed.recordMove(
                ISLAND,
                player,
                type,
                ActivityVisibility.MEMBERS_ONLY,
                "activity.bank_deposit",
                Map.of(),
                new ActivityAmount(currency, units));
        sleepPast(event.createdAt());
        return event;
    }

    private static void sleepPast(Instant moment) {
        while (!Instant.now().isAfter(moment)) {
            Thread.onSpinWait();
        }
    }

    @Test
    @DisplayName("A bank move keeps who moved it and how much, beside its sentence")
    void aMoveKeepsItsPlayerAndAmount() {
        ActivityEvent event = move(ALI, ActivityEventType.BANK_DEPOSIT, "primary", 2550);

        assertThat(event.actorPlayer()).isEqualTo(ALI);
        assertThat(event.amount()).isEqualTo(new ActivityAmount("primary", 2550));
        assertThat(store.events).containsExactly(event);
    }

    @Test
    @DisplayName("Pages turn, and a page past the last comes back to the last")
    void pagesTurn() {
        List<ActivityEvent> written = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            written.add(move(ALI, ActivityEventType.BANK_DEPOSIT, "primary", 100 + i));
        }

        ActivityFeedService.ActivityPage first = feed.page(ActivityQuery.all(ISLAND), 0, 2);
        assertThat(first.events()).containsExactly(written.get(4), written.get(3));
        assertThat(first.pages()).isEqualTo(3);
        assertThat(first.total()).isEqualTo(5);

        ActivityFeedService.ActivityPage last = feed.page(ActivityQuery.all(ISLAND), 9, 2);
        assertThat(last.page())
                .describedAs("a page past the end shows the last")
                .isEqualTo(2);
        assertThat(last.events()).containsExactly(written.get(0));

        ActivityFeedService.ActivityPage empty = feed.page(ActivityQuery.all("nowhere"), 0, 2);
        assertThat(empty.pages()).describedAs("an empty feed is one empty page").isEqualTo(1);
        assertThat(empty.events()).isEmpty();
    }

    @Test
    @DisplayName("A feed narrows to one member, to some kinds and to a time")
    void aFeedNarrows() {
        ActivityEvent aliIn = move(ALI, ActivityEventType.BANK_DEPOSIT, "primary", 500);
        ActivityEvent berkOut = move(BERK, ActivityEventType.BANK_WITHDRAW, "primary", -200);
        ActivityEvent aliOut = move(ALI, ActivityEventType.BANK_WITHDRAW, "gems", -3);

        ActivityQuery all = ActivityQuery.all(ISLAND);
        assertThat(feed.page(all.withPlayer(ALI), 0, 10).events()).containsExactly(aliOut, aliIn);
        assertThat(feed.page(all.withTypes(Set.of(ActivityEventType.BANK_WITHDRAW)), 0, 10)
                        .events())
                .containsExactly(aliOut, berkOut);
        assertThat(feed.page(all.withSince(berkOut.createdAt()), 0, 10).events())
                .describedAs("the moment itself is included")
                .containsExactly(aliOut, berkOut);
        assertThat(feed.page(all.withPlayer(BERK).withTypes(Set.of(ActivityEventType.BANK_DEPOSIT)), 0, 10)
                        .events())
                .isEmpty();
    }

    @Test
    @DisplayName("The bank lines add up by member, way and currency, largest first")
    void theBankAddsUp() {
        move(ALI, ActivityEventType.BANK_DEPOSIT, "primary", 500);
        move(BERK, ActivityEventType.BANK_WITHDRAW, "primary", -800);
        move(ALI, ActivityEventType.BANK_DEPOSIT, "primary", 400);
        move(ALI, ActivityEventType.BANK_DEPOSIT, "gems", 7);
        feed.record(
                ISLAND,
                null,
                ActivityEventType.MEMBER_JOINED,
                ActivityVisibility.MEMBERS_ONLY,
                "activity.joined",
                Map.of());

        assertThat(feed.totals(ActivityQuery.all(ISLAND)))
                .containsExactly(
                        new ActivityTotal(ALI, ActivityEventType.BANK_DEPOSIT, "primary", 900, 2),
                        new ActivityTotal(BERK, ActivityEventType.BANK_WITHDRAW, "primary", 800, 1),
                        new ActivityTotal(ALI, ActivityEventType.BANK_DEPOSIT, "gems", 7, 1));
        assertThat(feed.totals(ActivityQuery.all(ISLAND).withPlayer(BERK)))
                .containsExactly(new ActivityTotal(BERK, ActivityEventType.BANK_WITHDRAW, "primary", 800, 1));
    }

    @Test
    @DisplayName("A line written under a profile names the player of that profile, so it narrows by member too")
    void aProfilesLineNamesItsPlayer() {
        com.uxplima.uxmskyblock.core.domain.identity.ProfileId aliProfile =
                new com.uxplima.uxmskyblock.core.domain.identity.ProfileId(UUID.randomUUID());
        feed.usePlayers(
                profile -> profile.equals(aliProfile) ? java.util.Optional.of(ALI) : java.util.Optional.empty());

        ActivityEvent joined = feed.record(
                ISLAND,
                aliProfile,
                ActivityEventType.MEMBER_JOINED,
                ActivityVisibility.MEMBERS_ONLY,
                "activity.joined",
                Map.of());
        ActivityEvent unknown = feed.record(
                ISLAND,
                new com.uxplima.uxmskyblock.core.domain.identity.ProfileId(UUID.randomUUID()),
                ActivityEventType.MEMBER_LEFT,
                ActivityVisibility.MEMBERS_ONLY,
                "activity.left",
                Map.of());

        assertThat(joined.actorPlayer()).isEqualTo(ALI);
        assertThat(unknown.actorPlayer())
                .describedAs("a profile nobody online plays names nobody")
                .isNull();
        assertThat(feed.page(ActivityQuery.all(ISLAND).withPlayer(ALI), 0, 10).events())
                .containsExactly(joined);
    }

    @Test
    @DisplayName("An amount moves something, and a total carries no sign")
    void amountsAndTotalsAreWhole() {
        assertThatThrownBy(() -> new ActivityAmount("primary", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ActivityAmount(" ", 5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ActivityTotal(ALI, ActivityEventType.BANK_DEPOSIT, "primary", -1, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
