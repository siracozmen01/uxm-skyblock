package com.uxplima.uxmskyblock.persistence.activity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.domain.activity.ActivityAmount;
import com.uxplima.uxmskyblock.core.domain.activity.ActivityEvent;
import com.uxplima.uxmskyblock.core.domain.activity.ActivityEventType;
import com.uxplima.uxmskyblock.core.domain.activity.ActivityQuery;
import com.uxplima.uxmskyblock.core.domain.activity.ActivityTotal;
import com.uxplima.uxmskyblock.core.domain.activity.ActivityVisibility;
import org.jspecify.annotations.Nullable;

/** What every engine must do with an island's feed: page it, narrow it and add up its bank lines. */
final class ActivityFeedStorageScenario {

    private static final UUID ALI = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BERK = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    // Whole seconds: a MySQL TIMESTAMP keeps no fraction, and a line must read back as it was written.
    private static final Instant T0 = Instant.parse("2026-10-01T10:00:00Z");

    private ActivityFeedStorageScenario() {}

    static void run(Database database) {
        String island = UUID.randomUUID().toString();
        String other = UUID.randomUUID().toString();
        SqlActivityFeedAdapter feed = new SqlActivityFeedAdapter(database.dataSource());

        ActivityEvent aliIn =
                line(island, 0, ActivityEventType.BANK_DEPOSIT, ALI, new ActivityAmount("primary", 50_000));
        ActivityEvent berkOut =
                line(island, 1, ActivityEventType.BANK_WITHDRAW, BERK, new ActivityAmount("primary", -20_000));
        ActivityEvent aliGems = line(island, 2, ActivityEventType.BANK_DEPOSIT, ALI, new ActivityAmount("gems", 30));
        ActivityEvent aliAgain =
                line(island, 3, ActivityEventType.BANK_DEPOSIT, ALI, new ActivityAmount("primary", 10_000));
        ActivityEvent joined = line(island, 4, ActivityEventType.MEMBER_JOINED, null, null);
        ActivityEvent elsewhere =
                line(other, 5, ActivityEventType.BANK_DEPOSIT, ALI, new ActivityAmount("primary", 99_900));
        for (ActivityEvent event : List.of(aliIn, berkOut, aliGems, aliAgain, joined, elsewhere)) {
            feed.appendEvent(event);
        }

        ActivityQuery all = ActivityQuery.all(island);
        assertThat(feed.find(all, 0, 2))
                .describedAs("the newest page first, every field read back as written")
                .containsExactly(joined, aliAgain);
        assertThat(feed.find(all, 2, 2))
                .describedAs("the next page goes on where it stopped")
                .containsExactly(aliGems, berkOut);
        assertThat(feed.find(all, 4, 2)).containsExactly(aliIn);
        assertThat(feed.count(all))
                .describedAs("another island's lines are not this one's")
                .isEqualTo(5);
        assertThat(feed.findEventsByInstanceId(island, 10)).containsExactly(joined, aliAgain, aliGems, berkOut, aliIn);

        assertThat(feed.find(all.withPlayer(ALI), 0, 10))
                .describedAs("one member's lines")
                .containsExactly(aliAgain, aliGems, aliIn);
        assertThat(feed.count(all.withPlayer(BERK))).isEqualTo(1);
        assertThat(feed.find(
                        all.withTypes(Set.of(ActivityEventType.BANK_WITHDRAW, ActivityEventType.MEMBER_JOINED)), 0, 10))
                .describedAs("lines of the kinds asked for")
                .containsExactly(joined, berkOut);
        assertThat(feed.find(all.withSince(T0.plusSeconds(2 * 60)), 0, 10))
                .describedAs("lines written from a moment on, that moment included")
                .containsExactly(joined, aliAgain, aliGems);

        assertThat(feed.totals(all))
                .describedAs("who put in and took out how much, largest first, a line with no amount left out")
                .containsExactly(
                        new ActivityTotal(ALI, ActivityEventType.BANK_DEPOSIT, "primary", 60_000, 2),
                        new ActivityTotal(BERK, ActivityEventType.BANK_WITHDRAW, "primary", 20_000, 1),
                        new ActivityTotal(ALI, ActivityEventType.BANK_DEPOSIT, "gems", 30, 1));
        assertThat(feed.totals(all.withSince(T0.plusSeconds(3 * 60))))
                .describedAs("the totals of the lines the query reads, and no others")
                .containsExactly(new ActivityTotal(ALI, ActivityEventType.BANK_DEPOSIT, "primary", 10_000, 1));
        assertThat(feed.totals(all.withPlayer(BERK)))
                .containsExactly(new ActivityTotal(BERK, ActivityEventType.BANK_WITHDRAW, "primary", 20_000, 1));
    }

    private static ActivityEvent line(
            String island, int minute, ActivityEventType type, @Nullable UUID player, @Nullable ActivityAmount amount) {
        return new ActivityEvent(
                UUID.randomUUID(),
                island,
                null,
                type,
                ActivityVisibility.MEMBERS_ONLY,
                "activity.test",
                1,
                "minute=" + minute,
                T0.plusSeconds(minute * 60L),
                player,
                amount);
    }
}
