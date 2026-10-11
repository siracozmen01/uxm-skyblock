package com.uxplima.uxmskyblock.bukkit.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.bukkit.command.CommandSender;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import com.mojang.brigadier.CommandDispatcher;
import com.uxplima.uxmskyblock.bukkit.config.LanguageConfiguration;
import com.uxplima.uxmskyblock.bukkit.i18n.MessageProvider;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.schematic.StarterSchematicEngine;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.core.application.antiabuse.IslandAntiAbuseService;
import com.uxplima.uxmskyblock.core.application.island.CreateIslandUseCase;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.name.IslandNameService;
import com.uxplima.uxmskyblock.core.application.preset.StarterPresetCatalog;
import com.uxplima.uxmskyblock.core.application.recycle.IslandRecycleService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.name.IslandName;
import com.uxplima.uxmskyblock.core.domain.recycle.ResetChallenge;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * {@code /is create}, {@code /is reset}, {@code /is delete} and {@code /is rename}, end to end
 * through Brigadier.
 *
 * <p>These are the commands that make an island and the commands that erase one, and they shipped
 * with no test at all. The confirmation code is the part worth pinning: a reset that runs without
 * one is a player losing everything to a mistyped command.
 */
class IslandLifecycleCommandsTest {

    private static final IslandId ISLAND = IslandId.of(UUID.randomUUID());
    private static final ProfileId PROFILE = new ProfileId(UUID.randomUUID());
    private static final String WORLD = "skyblock_world";

    private ServerMock server;
    private PlayerMock player;
    private CreateIslandUseCase create;
    private IslandRecycleService recycle;
    private IslandNameService names;
    private CommandDispatcher<CommandSourceStack> dispatcher;
    private IslandLifecycleCommands commands;

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
        doAnswer(invocation -> {
                    invocation.getArgument(3, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .onRegion(anyString(), anyInt(), anyInt(), any(Runnable.class));
        return scheduler;
    }

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        server.addSimpleWorld(WORLD);
        player = server.addPlayer();

        create = mock(CreateIslandUseCase.class);
        when(create.execute(any(), any(), anyString(), any(ServerNodeId.class), anyString()))
                .thenReturn(new CreateIslandUseCase.CreateIslandResult.Failure("not today"));

        recycle = mock(IslandRecycleService.class);
        when(recycle.generateResetChallenge(PROFILE, ISLAND))
                .thenReturn(new ResetChallenge("AB12CD", Instant.now().plusSeconds(60)));
        when(recycle.executeReset(any(), any(), any(), anyBoolean()))
                .thenReturn(CompletableFuture.completedFuture(
                        new IslandRecycleService.RecycleResult.InvalidChallenge("wrong code")));

        names = mock(IslandNameService.class);
        when(names.getIslandName(ISLAND)).thenReturn(Optional.of(IslandName.of("The Rock")));
        when(names.renameIsland(any(), any(), anyString())).thenReturn(IslandName.of("New Rock"));

        IslandLocationService locations = mock(IslandLocationService.class);
        when(locations.findIslandId(PROFILE)).thenReturn(Optional.of(ISLAND));

        PlayerSessionCoordinator sessions = mock(PlayerSessionCoordinator.class);
        when(sessions.activeProfile(player.getUniqueId())).thenReturn(Optional.of(PROFILE));

        commands = new IslandLifecycleCommands(
                create,
                locations,
                new StarterPresetCatalog(
                        java.util.stream.Stream.concat(
                                        StarterPresetCatalog.shipped().stream(),
                                        java.util.stream.Stream.of(
                                                new com.uxplima.uxmskyblock.core.domain.preset.StarterPreset(
                                                        "faraway",
                                                        "@presets.faraway.name",
                                                        "@presets.faraway.description",
                                                        "schematics/faraway.schem",
                                                        com.uxplima.uxmskyblock.core.domain.biome.IslandBiome.PLAINS,
                                                        com.uxplima.uxmskyblock.core.domain.gamemode.GameModeType.BOXED,
                                                        List.of("uxm:platform"),
                                                        com.uxplima.uxmskyblock.core.domain.preset.StartTemplateBundle
                                                                .shipped(),
                                                        "a_world_nobody_loaded")))
                                .toList(),
                        new StarterPresetCatalog().defaultPreset().id()),
                mock(StarterSchematicEngine.class),
                mock(IslandProtectionListener.class),
                sessions,
                inlineScheduler(),
                ServerNodeId.of("node-1"),
                WORLD,
                () -> null,
                () -> recycle,
                () -> null,
                () -> names,
                Messages.of(new MessageProvider("en"), LanguageConfiguration.defaults()));

        dispatcher = new CommandDispatcher<>();
        dispatcher.register(commands.buildCreate());
        dispatcher.register(commands.buildReset());
        dispatcher.register(commands.buildDelete());
        dispatcher.register(commands.buildDisband());
        dispatcher.register(commands.buildRename());
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
    @DisplayName("A bare /is create uses the catalogue's default preset rather than a name in code")
    void createUsesTheCatalogueDefault() throws Exception {
        run("create", player);

        verify(create)
                .execute(
                        any(PlayerUuid.class),
                        eq(PROFILE),
                        eq(new StarterPresetCatalog().defaultPreset().id()),
                        any(ServerNodeId.class),
                        eq(WORLD));
    }

    @Test
    @DisplayName("A bare /is create opens the window of kinds when the operator asks for it, and makes nothing")
    void aBareCreateOffersTheKinds() throws Exception {
        com.uxplima.uxmskyblock.bukkit.menu.PresetList kinds =
                mock(com.uxplima.uxmskyblock.bukkit.menu.PresetList.class);
        when(kinds.show(player)).thenReturn(true);
        commands.usePresetChoices(() -> com.uxplima.uxmskyblock.bukkit.config.PresetChoices.DEFAULT, () -> kinds);

        run("create", player);

        verify(kinds).show(player);
        verify(create, never()).execute(any(), any(), anyString(), any(ServerNodeId.class), anyString());
    }

    @Test
    @DisplayName("A bare /is create makes the default island when the window is gone or the operator wants it made")
    void aBareCreateMakesTheDefault() throws Exception {
        com.uxplima.uxmskyblock.bukkit.menu.PresetList kinds =
                mock(com.uxplima.uxmskyblock.bukkit.menu.PresetList.class);
        commands.usePresetChoices(() -> com.uxplima.uxmskyblock.bukkit.config.PresetChoices.DEFAULT, () -> kinds);
        run("create", player);

        when(kinds.show(player)).thenReturn(true);
        commands.usePresetChoices(
                () -> new com.uxplima.uxmskyblock.bukkit.config.PresetChoices(
                        java.util.Map.of(),
                        com.uxplima.uxmskyblock.bukkit.config.PresetChoices.WhenNoIsland.MENU,
                        false),
                () -> kinds);
        run("create", player);

        verify(create, org.mockito.Mockito.times(2))
                .execute(
                        any(PlayerUuid.class),
                        eq(PROFILE),
                        eq(new StarterPresetCatalog().defaultPreset().id()),
                        any(ServerNodeId.class),
                        eq(WORLD));
        verify(kinds).show(player);
    }

    @Test
    @DisplayName("A kind behind a permission node is made only for a player who holds it")
    void aLockedKindAsksForItsNode() throws Exception {
        commands.usePresetChoices(
                () -> new com.uxplima.uxmskyblock.bukkit.config.PresetChoices(
                        java.util.Map.of(
                                "desert",
                                new com.uxplima.uxmskyblock.bukkit.config.PresetChoices.Look(
                                        "SAND", "myserver.desert")),
                        com.uxplima.uxmskyblock.bukkit.config.PresetChoices.WhenNoIsland.MENU,
                        true),
                () -> null);

        run("create desert", player);
        verify(create, never()).execute(any(), any(), anyString(), any(ServerNodeId.class), anyString());
        assertThat(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                        .serialize(java.util.Objects.requireNonNull(player.nextComponentMessage())))
                .describedAs("this test's catalogue answers with the key")
                .isEqualTo("create.preset_locked");

        player.addAttachment(MockBukkit.createMockPlugin(), "myserver.desert", true);
        run("create desert", player);
        verify(create).execute(any(PlayerUuid.class), eq(PROFILE), eq("desert"), any(ServerNodeId.class), eq(WORLD));
    }

    @Test
    @DisplayName("A creation the database throws out of is still answered, in the player's own words")
    void aThrownCreationIsAnswered() throws Exception {
        when(create.execute(any(), any(), anyString(), any(ServerNodeId.class), anyString()))
                .thenThrow(new IllegalStateException("ERROR: operator does not exist: boolean = integer"));
        while (player.nextComponentMessage() != null) {
            // Only what the creation says matters here.
        }

        run("create", player);

        String said = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(java.util.Objects.requireNonNull(player.nextComponentMessage()));
        assertThat(said)
                .describedAs("the catalogue line, never the database's words")
                .isEqualTo("create.failed");
    }

    @Test
    @DisplayName("A preset whose world the server has not loaded makes nothing, and the player is told which")
    void aMissingWorldMakesNothing() throws Exception {
        while (player.nextComponentMessage() != null) {
            // Only what the creation says matters here.
        }

        run("create faraway", player);

        verify(create, never()).execute(any(), any(), anyString(), any(ServerNodeId.class), anyString());
        String said = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(java.util.Objects.requireNonNull(player.nextComponentMessage()));
        assertThat(said).isEqualTo("create.world_missing");
    }

    @Test
    @DisplayName("A named preset is carried through to the use case")
    void createCarriesTheNamedPreset() throws Exception {
        run("create desert", player);

        verify(create).execute(any(), eq(PROFILE), eq("desert"), any(ServerNodeId.class), eq(WORLD));
    }

    @Test
    @DisplayName("A preset the catalogue never heard of still reaches the use case, which owns the answer")
    void anUnknownPresetIsTheUseCasesAnswer() throws Exception {
        when(create.execute(any(), any(), eq("atlantis"), any(), anyString()))
                .thenReturn(new CreateIslandUseCase.CreateIslandResult.UnknownPreset("atlantis"));

        run("create atlantis", player);

        verify(create).execute(any(), eq(PROFILE), eq("atlantis"), any(ServerNodeId.class), eq(WORLD));
    }

    @Test
    @DisplayName("A bare /is reset asks, with no code to copy, and erases nothing")
    void resetAsksAndErasesNothing() throws Exception {
        run("reset", player);

        java.util.List<String> said = new java.util.ArrayList<>();
        for (net.kyori.adventure.text.Component line = player.nextComponentMessage();
                line != null;
                line = player.nextComponentMessage()) {
            said.add(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                    .serialize(line));
        }
        assertThat(said)
                .describedAs("with no window, the player is told the one line that confirms")
                .contains("reset.confirm_hint");
        assertThat(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                        .serialize(Messages.bundled().render(player, "reset.confirm_hint")))
                .describedAs("and that line carries no code")
                .contains("/is delete confirm")
                .doesNotContainPattern("\\d{4}");

        verify(recycle, never()).generateResetChallenge(any(), any());
        verify(recycle, never()).executeReset(any(), any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("A bare /is delete asks, with no code to copy, and erases nothing")
    void deleteIsTheSameWarning() throws Exception {
        run("delete", player);

        verify(recycle, never()).generateResetChallenge(any(), any());
        verify(recycle, never()).executeReset(any(), any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("A bare /is disband asks, with no code to copy, and erases nothing")
    void disbandIsTheSameWarning() throws Exception {
        run("disband", player);

        verify(recycle, never()).generateResetChallenge(any(), any());
        verify(recycle, never()).executeReset(any(), any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("Confirming under the disband word erases the same island")
    void disbandConfirmErasesTheSameIsland() throws Exception {
        run("disband confirm", player);

        verify(recycle).executeReset(PROFILE, ISLAND, "AB12CD", false);
    }

    @Test
    @DisplayName("The typed confirmation erases at once with the challenge it asked for itself, never the bypass")
    void confirmErasesAtOnceAndNeverBypasses() throws Exception {
        run("reset confirm", player);

        verify(recycle).executeReset(PROFILE, ISLAND, "AB12CD", false);
    }

    @Test
    @DisplayName("A second confirmation while the island is still being erased is refused")
    void aSecondConfirmationIsRefusedWhileTheFirstRuns() throws Exception {
        // The daily limit is recorded when the erasure finishes, so a second confirmation arriving
        // while the first is still running used to pass the same check and erase the island twice
        // for one allowance. The erasure here never finishes, which is the whole window.
        IslandAntiAbuseService antiAbuse = new IslandAntiAbuseService(
                mock(com.uxplima.uxmskyblock.core.application.antiabuse.AntiAbuseStoragePort.class),
                false,
                IslandAntiAbuseService.DEFAULT_QUARANTINE_DURATION,
                IslandAntiAbuseService.DEFAULT_RESET_COOLDOWN,
                IslandAntiAbuseService.DEFAULT_MAX_RESETS_PER_DAY,
                IslandAntiAbuseService.DEFAULT_RESET_WINDOW_DURATION,
                IslandAntiAbuseService.DEFAULT_COOP_JOIN_COOLDOWN,
                IslandAntiAbuseService.DEFAULT_QUARANTINE_LOOKUP_TTL,
                java.time.Clock.systemUTC());

        IslandRecycleService slowRecycle = mock(IslandRecycleService.class);
        when(slowRecycle.generateResetChallenge(PROFILE, ISLAND))
                .thenReturn(new ResetChallenge("AB12CD", Instant.now().plusSeconds(60)));
        when(slowRecycle.executeReset(any(), any(), any(), anyBoolean())).thenReturn(new CompletableFuture<>());

        CommandDispatcher<CommandSourceStack> slow = dispatcherOver(antiAbuse, slowRecycle);

        runOn(slow, "reset confirm", player);
        runOn(slow, "reset confirm", player);

        verify(slowRecycle, org.mockito.Mockito.times(1)).executeReset(PROFILE, ISLAND, "AB12CD", false);
        assertThat(antiAbuse.resetsRunning())
                .describedAs("erasures running for this player")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("An erasure that finishes gives the player their next reset back")
    void aFinishedErasureReleasesThePlayer() throws Exception {
        IslandAntiAbuseService antiAbuse = new IslandAntiAbuseService(
                mock(com.uxplima.uxmskyblock.core.application.antiabuse.AntiAbuseStoragePort.class),
                false,
                IslandAntiAbuseService.DEFAULT_QUARANTINE_DURATION,
                IslandAntiAbuseService.DEFAULT_RESET_COOLDOWN,
                IslandAntiAbuseService.DEFAULT_MAX_RESETS_PER_DAY,
                IslandAntiAbuseService.DEFAULT_RESET_WINDOW_DURATION,
                IslandAntiAbuseService.DEFAULT_COOP_JOIN_COOLDOWN,
                IslandAntiAbuseService.DEFAULT_QUARANTINE_LOOKUP_TTL,
                java.time.Clock.systemUTC());

        IslandRecycleService failing = mock(IslandRecycleService.class);
        when(failing.generateResetChallenge(PROFILE, ISLAND))
                .thenReturn(new ResetChallenge("AB12CD", Instant.now().plusSeconds(60)));
        when(failing.executeReset(any(), any(), any(), anyBoolean()))
                .thenReturn(CompletableFuture.completedFuture(
                        new IslandRecycleService.RecycleResult.InvalidChallenge("wrong code")));

        runOn(dispatcherOver(antiAbuse, failing), "reset confirm", player);

        assertThat(antiAbuse.resetsRunning())
                .describedAs("an erasure that failed must not lock the player out for ever")
                .isZero();
    }

    @org.junit.jupiter.api.Test
    @DisplayName("The permission that skips the reset rules is the one the operator wrote down")
    void thebypassNodeIsTheOperatorsOwn() throws Exception {
        IslandAntiAbuseService antiAbuse = mock(IslandAntiAbuseService.class);
        when(antiAbuse.checkResetAllowed(any(), anyBoolean()))
                .thenReturn(new com.uxplima.uxmskyblock.core.domain.antiabuse.ResetCheckResult.Allowed(3));
        IslandRecycleService recycle = mock(IslandRecycleService.class);
        when(recycle.generateResetChallenge(any(), any()))
                .thenReturn(new ResetChallenge("AB12CD", Instant.now().plusSeconds(60)));

        com.uxplima.uxmskyblock.bukkit.config.AntiAbuseConfiguration rules =
                com.uxplima.uxmskyblock.bukkit.config.AntiAbuseConfiguration.defaultConfiguration();
        player.addAttachment(
                org.mockbukkit.mockbukkit.MockBukkit.createMockPlugin(), rules.resetBypassPermission(), true);

        runOn(dispatcherOver(antiAbuse, recycle, rules), "reset", player);

        verify(antiAbuse).checkResetAllowed(any(), org.mockito.ArgumentMatchers.eq(true));
    }

    @org.junit.jupiter.api.Test
    @DisplayName("The node that used to be written into the code is not a bypass any more")
    void thehardcodedNodeIsNotABypass() throws Exception {
        IslandAntiAbuseService antiAbuse = mock(IslandAntiAbuseService.class);
        when(antiAbuse.checkResetAllowed(any(), anyBoolean()))
                .thenReturn(new com.uxplima.uxmskyblock.core.domain.antiabuse.ResetCheckResult.Allowed(3));
        IslandRecycleService recycle = mock(IslandRecycleService.class);
        when(recycle.generateResetChallenge(any(), any()))
                .thenReturn(new ResetChallenge("AB12CD", Instant.now().plusSeconds(60)));

        com.uxplima.uxmskyblock.bukkit.config.AntiAbuseConfiguration rules =
                com.uxplima.uxmskyblock.bukkit.config.AntiAbuseConfiguration.defaultConfiguration();
        player.addAttachment(
                org.mockbukkit.mockbukkit.MockBukkit.createMockPlugin(), "skyblock.antiabuse.bypass", true);

        runOn(dispatcherOver(antiAbuse, recycle, rules), "reset", player);

        verify(antiAbuse).checkResetAllowed(any(), org.mockito.ArgumentMatchers.eq(false));
    }

    /** The same command tree, over an anti-abuse service and a recycle service this test chose. */
    private CommandDispatcher<CommandSourceStack> dispatcherOver(
            IslandAntiAbuseService antiAbuse, IslandRecycleService recycleService) {
        return dispatcherOver(antiAbuse, recycleService, null);
    }

    /** And with the bypass permissions the operator named, when a test is about those. */
    private CommandDispatcher<CommandSourceStack> dispatcherOver(
            IslandAntiAbuseService antiAbuse,
            IslandRecycleService recycleService,
            com.uxplima.uxmskyblock.bukkit.config.@org.jspecify.annotations.Nullable AntiAbuseConfiguration rules) {
        IslandLocationService locations = mock(IslandLocationService.class);
        when(locations.findIslandId(PROFILE)).thenReturn(Optional.of(ISLAND));
        PlayerSessionCoordinator sessions = mock(PlayerSessionCoordinator.class);
        when(sessions.activeProfile(player.getUniqueId())).thenReturn(Optional.of(PROFILE));

        IslandLifecycleCommands commands = new IslandLifecycleCommands(
                create,
                locations,
                new StarterPresetCatalog(),
                mock(StarterSchematicEngine.class),
                mock(IslandProtectionListener.class),
                sessions,
                inlineScheduler(),
                ServerNodeId.of("node-1"),
                WORLD,
                () -> antiAbuse,
                () -> recycleService,
                () -> null,
                () -> names,
                Messages.of(new MessageProvider("en"), LanguageConfiguration.defaults()));

        commands.useAntiAbuseRules(rules);

        CommandDispatcher<CommandSourceStack> tree = new CommandDispatcher<>();
        tree.register(commands.buildReset());
        return tree;
    }

    private void runOn(CommandDispatcher<CommandSourceStack> tree, String line, org.bukkit.entity.Player sender)
            throws Exception {
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn(sender);
        tree.execute(line, source);
    }

    @Test
    @DisplayName("A code typed after the confirmation is refused by the parser, as there is no code any more")
    void aTypedCodeIsRefused() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> run("reset confirm 1234", player))
                .isInstanceOf(Exception.class);
        verify(recycle, never()).executeReset(any(), any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("A challenge the service refuses is its answer, and the island survives it")
    void aRefusedChallengeLeavesTheIsland() throws Exception {
        run("reset confirm", player);

        verify(recycle).executeReset(PROFILE, ISLAND, "AB12CD", false);
        verify(recycle, never()).cancelResetChallenge(any());
    }

    @Test
    @DisplayName("A bare /is rename reads the name rather than clearing it")
    void bareRenameReadsTheName() throws Exception {
        run("rename", player);

        verify(names).getIslandName(ISLAND);
        verify(names, never()).renameIsland(any(), any(), anyString());
    }

    @Test
    @DisplayName("A rename carries the whole name, spaces and all")
    void renameCarriesTheWholeName() throws Exception {
        run("rename The Quiet Rock", player);

        verify(names).renameIsland(ISLAND, PROFILE, "The Quiet Rock");
    }

    @Test
    @DisplayName("A name the service refuses is an answer, not a stack trace")
    void aRefusedNameIsAnAnswer() throws Exception {
        when(names.renameIsland(any(), any(), anyString())).thenThrow(new IllegalArgumentException("too long"));

        run("rename aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", player);

        assertThat(player.nextMessage()).isNotNull();
    }

    @Test
    @DisplayName("The console is told to be a player rather than creating an island for nobody")
    void theConsoleIsRefused() throws Exception {
        run("create", server.getConsoleSender());
        run("reset", server.getConsoleSender());
        run("rename Something", server.getConsoleSender());

        verify(create, never()).execute(any(), any(), anyString(), any(), anyString());
        verify(recycle, never()).generateResetChallenge(any(), any());
        verify(names, never()).renameIsland(any(), any(), anyString());
    }

    @Test
    @DisplayName("A player with no session never reaches the use case")
    void noSessionNeverReachesTheUseCase() throws Exception {
        PlayerMock stranger = server.addPlayer();

        run("create", stranger);

        verify(create, never()).execute(any(), any(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("Every preset the catalogue publishes is a word /is create accepts")
    void everyPublishedPresetParses() throws Exception {
        for (var preset : new StarterPresetCatalog().allPresets()) {
            run("create " + preset.id(), player);
        }

        List<String> ids = new StarterPresetCatalog()
                .allPresets().stream().map(p -> p.id()).toList();
        for (String id : ids) {
            verify(create).execute(any(), eq(PROFILE), eq(id), any(ServerNodeId.class), eq(WORLD));
        }
    }
}
