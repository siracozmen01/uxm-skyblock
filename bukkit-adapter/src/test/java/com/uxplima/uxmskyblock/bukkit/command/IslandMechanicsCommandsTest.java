package com.uxplima.uxmskyblock.bukkit.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.command.CommandSender;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import com.mojang.brigadier.CommandDispatcher;
import com.uxplima.uxmskyblock.bukkit.config.LanguageConfiguration;
import com.uxplima.uxmskyblock.bukkit.i18n.MessageProvider;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.core.application.antiabuse.IslandAntiAbuseService;
import com.uxplima.uxmskyblock.core.application.booster.IslandBoosterService;
import com.uxplima.uxmskyblock.core.application.boundary.IslandBoundaryService;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.limit.IslandLimitService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.booster.BoosterApplyResult;
import com.uxplima.uxmskyblock.core.domain.booster.BoosterCategory;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * {@code /is limits}, {@code /is quarantine}, {@code /is booster}, {@code /is border} and
 * {@code /is bounds}, end to end through Brigadier.
 *
 * <p>The admin booster branch is the one worth pinning. It writes a booster row, it is gated on a
 * permission, and its duration is a string an operator types, so a parser that reads "1x" as zero
 * and a parser that reads it as a day are both silent.
 */
class IslandMechanicsCommandsTest {

    private static final IslandId ISLAND = IslandId.of(UUID.randomUUID());
    private static final ProfileId PROFILE = new ProfileId(UUID.randomUUID());

    private ServerMock server;
    private PlayerMock player;
    private IslandLimitService limits;
    private IslandAntiAbuseService antiAbuse;
    private IslandBoosterService boosters;
    private IslandBoundaryService boundaries;
    private CommandDispatcher<CommandSourceStack> dispatcher;
    private IslandMechanicsCommands commands;

    private static SchedulerPort inlineScheduler() {
        SchedulerPort scheduler = mock(SchedulerPort.class);
        doAnswer(invocation -> {
                    invocation.getArgument(0, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .async(any(Runnable.class));
        doAnswer(invocation -> {
                    invocation.getArgument(1, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .onEntity(any(PlayerUuid.class), any(Runnable.class));
        return scheduler;
    }

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        player = server.addPlayer();
        player.addAttachment(MockBukkit.createMockPlugin(), "uxmskyblock.admin.booster", true);

        limits = mock(IslandLimitService.class);
        when(limits.getCounts(ISLAND)).thenReturn(Map.of());
        when(limits.getLimits(ISLAND)).thenReturn(Map.of());

        antiAbuse = mock(IslandAntiAbuseService.class);
        when(antiAbuse.getQuarantineRemaining(eq(ISLAND), any(Instant.class))).thenReturn(Optional.empty());

        boosters = mock(IslandBoosterService.class);
        when(boosters.applyBooster(any(), any(), anyDouble(), any(), any()))
                .thenReturn(new BoosterApplyResult.RejectedLowerTier(2.0, 1.5));

        boundaries = mock(IslandBoundaryService.class);
        when(boundaries.toggleBorder(any(PlayerUuid.class))).thenReturn(true);

        IslandLocationService locations = mock(IslandLocationService.class);
        when(locations.findIslandId(PROFILE)).thenReturn(Optional.of(ISLAND));

        PlayerSessionCoordinator sessions = mock(PlayerSessionCoordinator.class);
        when(sessions.activeProfile(player.getUniqueId())).thenReturn(Optional.of(PROFILE));

        commands = new IslandMechanicsCommands(
                locations,
                sessions,
                inlineScheduler(),
                () -> limits,
                () -> antiAbuse,
                () -> boosters,
                () -> null,
                () -> missionsMenu,
                () -> boundaries,
                Messages.of(new MessageProvider("en"), LanguageConfiguration.defaults()));

        dispatcher = new CommandDispatcher<>();
        dispatcher.register(commands.buildLimits());
        dispatcher.register(commands.buildQuarantine());
        dispatcher.register(commands.buildBooster());
        dispatcher.register(commands.buildBorder());
        dispatcher.register(commands.buildBounds());
        dispatcher.register(commands.buildMissions());
        dispatcher.register(commands.buildChallenges());
    }

    /** The missions window, none until a test hands one over. */
    private com.uxplima.uxmskyblock.bukkit.menu.@org.jspecify.annotations.Nullable IslandMissionsMenu missionsMenu;

    @org.junit.jupiter.api.Test
    @DisplayName("The daily missions, the weekly ones and the challenges each open their own list")
    void eachKindOpensItsOwnList() throws Exception {
        missionsMenu = mock(com.uxplima.uxmskyblock.bukkit.menu.IslandMissionsMenu.class);

        run("missions", player);
        run("missions daily", player);
        run("missions weekly", player);
        run("missions challenges", player);
        run("challenges", player);

        verify(missionsMenu).open(player, null);
        verify(missionsMenu).open(player, com.uxplima.uxmskyblock.core.domain.mission.MissionRepeat.DAILY);
        verify(missionsMenu).open(player, com.uxplima.uxmskyblock.core.domain.mission.MissionRepeat.WEEKLY);
        verify(missionsMenu, org.mockito.Mockito.times(2))
                .open(player, com.uxplima.uxmskyblock.core.domain.mission.MissionRepeat.ONCE);
    }

    @org.junit.jupiter.api.Test
    @DisplayName("Missions and challenges are the same door, and a node without them says so")
    void missionsAndChallengesAreTheSameDoor() throws Exception {
        run("missions", player);
        assertThat(player.nextMessage()).describedAs("told the menu is off").isNotNull();

        run("challenges", player);
        assertThat(player.nextMessage())
                .describedAs("the other word answers the same way")
                .isNotNull();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private void run(String line, CommandSender sender) throws Exception {
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn(sender);
        dispatcher.execute(line, source);
    }

    @Test
    @DisplayName("/is limits reads both the counts and the limits for the caller's island")
    void limitsReadsBothSides() throws Exception {
        run("limits", player);

        verify(limits).getCounts(ISLAND);
        verify(limits).getLimits(ISLAND);
    }

    @Test
    @DisplayName("/is quarantine asks how much of it is left for the caller's island")
    void quarantineAsksForTheRemainder() throws Exception {
        run("quarantine", player);

        verify(antiAbuse).getQuarantineRemaining(eq(ISLAND), any(Instant.class));
    }

    @Test
    @DisplayName("/is border opens the window of the border when its file is there, and switches nothing")
    void theBorderOpensItsWindow() throws Exception {
        com.uxplima.uxmskyblock.bukkit.menu.BorderWindow window =
                mock(com.uxplima.uxmskyblock.bukkit.menu.BorderWindow.class);
        when(window.show(player)).thenReturn(true);
        commands.useBorderWindow(() -> window);

        run("border", player);

        verify(window).show(player);
        verify(boundaries, never()).toggleBorder(any());
    }

    @Test
    @DisplayName("The border a player hid stays hidden past a rejoin, kept on the player")
    void theChoiceIsKeptOnThePlayer() throws Exception {
        when(boundaries.toggleBorder(any(PlayerUuid.class))).thenReturn(false, true);

        run("border", player);
        assertThat(com.uxplima.uxmskyblock.bukkit.boundary.BorderChoice.hidden(player))
                .isTrue();

        run("border", player);
        assertThat(com.uxplima.uxmskyblock.bukkit.boundary.BorderChoice.hidden(player))
                .isFalse();
    }

    @Test
    @DisplayName("/is border and /is bounds are the same switch")
    void borderAndBoundsAreTheSameSwitch() throws Exception {
        run("border", player);
        run("bounds", player);

        verify(boundaries, org.mockito.Mockito.times(2)).toggleBorder(any(PlayerUuid.class));
    }

    @Test
    @DisplayName("An applied booster carries the category, the multiplier and the duration the admin typed")
    void anAppliedBoosterCarriesWhatWasTyped() throws Exception {
        run("booster apply spawner_rate 2.5 3h", player);

        verify(boosters)
                .applyBooster(
                        eq(ISLAND),
                        eq(BoosterCategory.SPAWNER_RATE),
                        eq(2.5),
                        eq(Duration.ofHours(3)),
                        any(Instant.class));
    }

    @Test
    @DisplayName("Every duration suffix the parser publishes reaches the service as that length")
    void everyDurationSuffixIsUnderstood() throws Exception {
        run("booster apply crop_growth 2.0 90s", player);
        run("booster apply crop_growth 2.0 15m", player);
        run("booster apply crop_growth 2.0 2d", player);
        run("booster apply crop_growth 2.0 45", player);

        verify(boosters).applyBooster(any(), any(), anyDouble(), eq(Duration.ofSeconds(90)), any());
        verify(boosters).applyBooster(any(), any(), anyDouble(), eq(Duration.ofMinutes(15)), any());
        verify(boosters).applyBooster(any(), any(), anyDouble(), eq(Duration.ofDays(2)), any());
        verify(boosters).applyBooster(any(), any(), anyDouble(), eq(Duration.ofSeconds(45)), any());
    }

    @Test
    @DisplayName("A duration nobody can parse never reaches the service, rather than lasting no time")
    void anUnparseableDurationNeverReachesTheService() throws Exception {
        run("booster apply spawner_rate 2.0 forever", player);

        verify(boosters, never()).applyBooster(any(), any(), anyDouble(), any(), any());
    }

    @Test
    @DisplayName("A multiplier below one is refused by the argument type, not by the service")
    void aMultiplierBelowOneIsRefusedByTheType() {
        for (String multiplier : new String[] {"0", "0.5", "-2"}) {
            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> run("booster apply spawner_rate " + multiplier + " 1h", player))
                    .describedAs("a multiplier of %s must be refused by the argument type", multiplier)
                    .isInstanceOf(Exception.class);
        }
        verify(boosters, never()).applyBooster(any(), any(), anyDouble(), any(), any());
    }

    @Test
    @DisplayName("A category nobody publishes never reaches the service")
    void anUnknownCategoryNeverReachesTheService() throws Exception {
        run("booster apply gravity 2.0 1h", player);

        verify(boosters, never()).applyBooster(any(), any(), anyDouble(), any(), any());
    }

    @Test
    @DisplayName("Every category the plugin publishes is a word the command accepts")
    void everyPublishedCategoryIsAccepted() throws Exception {
        for (BoosterCategory category : BoosterCategory.values()) {
            run("booster apply " + category.key() + " 2.0 1h", player);
        }

        for (BoosterCategory category : BoosterCategory.values()) {
            verify(boosters).applyBooster(eq(ISLAND), eq(category), eq(2.0), eq(Duration.ofHours(1)), any());
        }
    }

    @Test
    @DisplayName("A player without the admin permission never applies a booster")
    void withoutThePermissionNothingIsApplied() throws Exception {
        PlayerMock ordinary = server.addPlayer();

        run("booster apply spawner_rate 2.0 1h", ordinary);

        verify(boosters, never()).applyBooster(any(), any(), anyDouble(), any(), any());
        assertThat(ordinary.nextMessage()).isNotNull();
    }

    @Test
    @DisplayName("The console is told to be a player rather than reading limits for nobody")
    void theConsoleIsRefused() throws Exception {
        run("limits", server.getConsoleSender());
        run("quarantine", server.getConsoleSender());
        run("border", server.getConsoleSender());

        verify(limits, never()).getCounts(any());
        verify(antiAbuse, never()).getQuarantineRemaining(any(), any());
        verify(boundaries, never()).toggleBorder(any());
    }
}
