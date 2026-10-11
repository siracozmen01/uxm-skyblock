package com.uxplima.uxmskyblock.bukkit.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import com.mojang.brigadier.CommandDispatcher;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.activity.ActivityFeedService;
import com.uxplima.uxmskyblock.core.application.antiabuse.IslandAntiAbuseService;
import com.uxplima.uxmskyblock.core.application.booster.IslandBoosterService;
import com.uxplima.uxmskyblock.core.application.boundary.IslandBoundaryService;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.limit.IslandLimitService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.activity.ActivityEventType;
import com.uxplima.uxmskyblock.core.domain.activity.ActivityVisibility;
import com.uxplima.uxmskyblock.core.domain.booster.BoosterApplyResult;
import com.uxplima.uxmskyblock.core.domain.booster.BoosterCategory;
import com.uxplima.uxmskyblock.core.domain.booster.IslandBooster;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.limit.LimitType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * What {@code /is limits}, {@code /is quarantine}, {@code /is border} and the admin booster tell a
 * player, read off the catalogue that ships.
 *
 * <p>The sibling test pins what each command asks its service. This one pins what comes back, because
 * a command that asks the right question and reads out the wrong answer is still wrong.
 */
class WhatTheMechanicsCommandsSayTest extends MockBukkitHarness {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private static final IslandId ISLAND = IslandId.of(UUID.randomUUID());
    private static final ProfileId PROFILE = new ProfileId(UUID.randomUUID());

    private PlayerMock player;
    private IslandLimitService limits;
    private IslandAntiAbuseService antiAbuse;
    private IslandBoosterService boosters;
    private IslandBoundaryService boundaries;
    private IslandLocationService locations;
    private PlayerSessionCoordinator sessions;
    private ActivityFeedService feed;
    private CommandDispatcher<CommandSourceStack> dispatcher;

    @BeforeEach
    void setUp() {
        player = createPlayer("Mechanic");
        player.addAttachment(MockBukkit.createMockPlugin(), "uxmskyblock.admin.booster", true);

        limits = mock(IslandLimitService.class);
        antiAbuse = mock(IslandAntiAbuseService.class);
        boosters = mock(IslandBoosterService.class);
        boundaries = mock(IslandBoundaryService.class);
        feed = mock(ActivityFeedService.class);

        locations = mock(IslandLocationService.class);
        when(locations.findIslandId(PROFILE)).thenReturn(Optional.of(ISLAND));
        sessions = mock(PlayerSessionCoordinator.class);
        when(sessions.activeProfile(player.getUniqueId())).thenReturn(Optional.of(PROFILE));

        IslandMechanicsCommands commands = new IslandMechanicsCommands(
                locations,
                sessions,
                inlineScheduler(),
                () -> limits,
                () -> antiAbuse,
                () -> boosters,
                () -> null,
                () -> null,
                () -> boundaries,
                Messages.bundled());
        commands.useActivityFeed(feed);

        dispatcher = new CommandDispatcher<>();
        dispatcher.register(commands.buildLimits());
        dispatcher.register(commands.buildQuarantine());
        dispatcher.register(commands.buildBooster());
        dispatcher.register(commands.buildBorder());
    }

    @Test
    @DisplayName("Limits lists every capped type with its count and its cap, and nothing uncapped")
    void limitsListsEveryCappedType() throws Exception {
        when(limits.getCounts(ISLAND)).thenReturn(Map.of(LimitType.HOPPER, 8, LimitType.VILLAGER, 2));
        when(limits.getLimits(ISLAND)).thenReturn(Map.of(LimitType.HOPPER, 10, LimitType.VILLAGER, 20));

        List<String> lines = run("limits");

        assertThat(lines).anyMatch(line -> line.endsWith(" • Hopper 8/10"));
        assertThat(lines).anyMatch(line -> line.endsWith(" • Villager 2/20"));
        assertThat(lines).noneMatch(line -> line.contains("Piston") || line.contains("Boats"));
    }

    @Test
    @DisplayName("Every capped type is named in words: the game's own name, or the catalogue's for boats")
    void noTypeIsNamedByItsConstant() throws Exception {
        Map<LimitType, Integer> every = new java.util.EnumMap<>(LimitType.class);
        for (LimitType type : LimitType.values()) {
            every.put(type, 5);
        }
        when(limits.getCounts(ISLAND)).thenReturn(Map.of());
        when(limits.getLimits(ISLAND)).thenReturn(every);

        List<String> lines = run("limits");

        assertThat(lines).anyMatch(line -> line.endsWith(" • Boats 0/5"));
        assertThat(lines).anyMatch(line -> line.endsWith(" • Sticky Piston 0/5"));
        for (LimitType type : LimitType.values()) {
            assertThat(lines).noneMatch(line -> line.contains(type.name()));
        }
    }

    @Test
    @DisplayName("A capped type nobody has placed yet reads zero, not a blank")
    void anUnplacedTypeReadsZero() throws Exception {
        when(limits.getCounts(ISLAND)).thenReturn(Map.of());
        when(limits.getLimits(ISLAND)).thenReturn(Map.of(LimitType.SPAWNER, 4));

        assertThat(run("limits")).anyMatch(line -> line.endsWith(" • Monster Spawner 0/4"));
    }

    @Test
    @DisplayName("A tile entity row sits under the tile header and an entity row under the entity header")
    void eachRowSitsUnderItsOwnHeader() throws Exception {
        when(limits.getCounts(ISLAND)).thenReturn(Map.of());
        when(limits.getLimits(ISLAND)).thenReturn(Map.of(LimitType.HOPPER, 10, LimitType.VILLAGER, 20));

        List<String> lines = run("limits");

        int tiles = indexContaining(lines, "Blocks that work");
        int entities = indexContaining(lines, "Mobs and vehicles");
        int hopper = indexContaining(lines, "Hopper");
        int villager = indexContaining(lines, "Villager");
        assertThat(tiles).isLessThan(hopper);
        assertThat(hopper).isLessThan(entities);
        assertThat(entities).isLessThan(villager);
    }

    @Test
    @DisplayName("A player without an island is told so rather than shown empty limits")
    void noIslandIsSaid() throws Exception {
        when(locations.findIslandId(PROFILE)).thenReturn(Optional.empty());

        List<String> lines = run("limits");

        assertThat(lines).hasSize(1);
        verify(limits, never()).getLimits(any());
    }

    @Test
    @DisplayName("A player whose session is not active reaches no service at all")
    void noSessionReachesNothing() throws Exception {
        when(sessions.activeProfile(player.getUniqueId())).thenReturn(Optional.empty());

        run("limits");
        run("quarantine");
        run("booster apply spawner_rate 2 1h");

        verify(locations, never()).findIslandId(any());
        verify(boosters, never()).applyBooster(any(), any(), anyDouble(), any(), any());
    }

    @Test
    @DisplayName("An active quarantine names how long it has left")
    void anActiveQuarantineNamesItsRemainder() throws Exception {
        when(antiAbuse.getQuarantineRemaining(eq(ISLAND), any(Instant.class)))
                .thenReturn(Optional.of(Duration.ofSeconds(3_661)));

        assertThat(String.join("\n", run("quarantine"))).contains("is on,").contains("1h 1m 1s left");
    }

    @Test
    @DisplayName("The quarantine remainder is written in ASCII digits on a server that writes its own")
    void theRemainderIsAsciiEverywhere() throws Exception {
        when(antiAbuse.getQuarantineRemaining(eq(ISLAND), any(Instant.class)))
                .thenReturn(Optional.of(Duration.ofSeconds(3_661)));
        java.util.Locale before = java.util.Locale.getDefault();
        java.util.Locale.setDefault(java.util.Locale.forLanguageTag("ar-EG"));
        try {
            assertThat(String.join("\n", run("quarantine"))).contains("1h 1m 1s left");
        } finally {
            java.util.Locale.setDefault(before);
        }
    }

    @Test
    @DisplayName("An island out of quarantine reads inactive")
    void noQuarantineReadsInactive() throws Exception {
        when(antiAbuse.getQuarantineRemaining(eq(ISLAND), any(Instant.class))).thenReturn(Optional.empty());

        assertThat(run("quarantine")).singleElement().asString().contains("is over");
    }

    @Test
    @DisplayName("The border says which way it was switched")
    void theBorderSaysWhichWay() throws Exception {
        when(boundaries.toggleBorder(any(PlayerUuid.class))).thenReturn(true, false);

        assertThat(run("border")).singleElement().asString().contains("is shown");
        assertThat(run("border")).singleElement().asString().contains("is hidden");
    }

    @Test
    @DisplayName("An applied booster names its category, multiplier and length, and is written in the feed")
    void anAppliedBoosterIsSaidAndRecorded() throws Exception {
        IslandBooster booster =
                IslandBooster.create(ISLAND, BoosterCategory.SPAWNER_RATE, 2.5, Duration.ofHours(3), Instant.now());
        when(boosters.applyBooster(any(), any(), anyDouble(), any(), any()))
                .thenReturn(new BoosterApplyResult.Success(booster, 2.5, Duration.ofHours(3)));

        assertThat(run("booster apply spawner_rate 2.5 3h"))
                .singleElement()
                .asString()
                .endsWith("Gave a 2.50x Spawner Rate booster for 3h.");
        verify(feed)
                .record(
                        eq(ISLAND.value().toString()),
                        eq(PROFILE),
                        eq(ActivityEventType.BOOSTER_ACTIVATED),
                        eq(ActivityVisibility.MEMBERS_ONLY),
                        eq("activity.booster_activated"),
                        eq(Map.of("player", "Mechanic", "category", "SPAWNER_RATE", "multiplier", "2.50")));
    }

    @Test
    @DisplayName("A booster refused for a lower tier names both multipliers and is not written in the feed")
    void aRefusedBoosterIsSaidAndNotRecorded() throws Exception {
        when(boosters.applyBooster(any(), any(), anyDouble(), any(), any()))
                .thenReturn(new BoosterApplyResult.RejectedLowerTier(3.0, 1.5));

        String said = String.join("\n", run("booster apply spawner_rate 1.5 1h"));

        assertThat(said).contains("3.00").contains("1.50");
        verify(feed, never()).record(anyString(), any(), any(), any(), anyString(), anyMap());
    }

    @Test
    @DisplayName("A booster for a switched off category says so and is not written in the feed")
    void aDisabledCategoryIsSaidAndNotRecorded() throws Exception {
        when(boosters.applyBooster(any(), any(), anyDouble(), any(), any()))
                .thenReturn(new BoosterApplyResult.CategoryDisabled(BoosterCategory.SPAWNER_RATE));

        assertThat(String.join("\n", run("booster apply spawner_rate 2 1h"))).contains("Spawner Rate");
        verify(feed, never()).record(anyString(), any(), any(), any(), anyString(), anyMap());
    }

    @Test
    @DisplayName("A capped extension reads differently from a plain one")
    void aCappedExtensionIsSaid() throws Exception {
        IslandBooster booster =
                IslandBooster.create(ISLAND, BoosterCategory.SPAWNER_RATE, 2.0, Duration.ofHours(1), Instant.now());
        when(boosters.applyBooster(any(), any(), anyDouble(), any(), any()))
                .thenReturn(
                        new BoosterApplyResult.DurationExtended(booster, 2.0, Duration.ofHours(2), false),
                        new BoosterApplyResult.DurationExtended(booster, 2.0, Duration.ofHours(4), true));

        String plain = String.join("\n", run("booster apply spawner_rate 2 1h"));
        String capped = String.join("\n", run("booster apply spawner_rate 2 1h"));

        assertThat(plain).contains("runs longer: 2h at").doesNotContain("longest");
        assertThat(capped).contains("its longest, 4h at");
    }

    private List<String> run(String line) throws Exception {
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn(player);
        dispatcher.execute(line, source);

        List<String> lines = new ArrayList<>();
        Component next;
        while ((next = player.nextComponentMessage()) != null) {
            lines.add(PLAIN.serialize(next));
        }
        return lines;
    }

    private static int indexContaining(List<String> lines, String text) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(text)) {
                return i;
            }
        }
        throw new AssertionError("no line contains " + text + " in " + lines);
    }

    private static SchedulerPort inlineScheduler() {
        SchedulerPort scheduler = mock(SchedulerPort.class);
        doAnswer(call -> {
                    call.getArgument(0, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .async(any(Runnable.class));
        doAnswer(call -> {
                    call.getArgument(1, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .onEntity(any(PlayerUuid.class), any(Runnable.class));
        return scheduler;
    }
}
