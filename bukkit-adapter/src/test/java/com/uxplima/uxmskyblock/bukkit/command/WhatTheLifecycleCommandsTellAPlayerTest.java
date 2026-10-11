package com.uxplima.uxmskyblock.bukkit.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import com.mojang.brigadier.CommandDispatcher;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.schematic.StarterSchematicEngine;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.island.CreateIslandUseCase;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.name.IslandNameService;
import com.uxplima.uxmskyblock.core.application.preset.StarterPresetCatalog;
import com.uxplima.uxmskyblock.core.application.recycle.IslandRecycleService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.name.IslandNameRefusedException;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * What a player reads when an island could not be made, reset or named.
 *
 * <p>Each of these used to put the service's own reason in front of the player. For a failed create
 * that was an exception's message, which can be the database's words; for a refused name it was a
 * sentence with a regular expression's rules in it; and it was English whatever the player read.
 */
class WhatTheLifecycleCommandsTellAPlayerTest extends MockBukkitHarness {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private static final IslandId ISLAND = IslandId.of(UUID.randomUUID());
    private static final ProfileId PROFILE = new ProfileId(UUID.randomUUID());
    private static final String WORLD = "skyblock_world";
    private static final String INTERNALS = "Connection refused: jdbc:mariadb://db.internal:3306/skyblock";

    private PlayerMock player;
    private CreateIslandUseCase create;
    private IslandRecycleService recycle;
    private IslandNameService names;
    private CommandDispatcher<CommandSourceStack> dispatcher;

    @BeforeEach
    void setUp() {
        server.addSimpleWorld(WORLD);
        player = createPlayer("Founder");
        create = mock(CreateIslandUseCase.class);
        recycle = mock(IslandRecycleService.class);
        org.mockito.Mockito.lenient()
                .when(recycle.generateResetChallenge(
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new com.uxplima.uxmskyblock.core.domain.recycle.ResetChallenge(
                        "1234", java.time.Instant.now().plusSeconds(60)));
        names = mock(IslandNameService.class);
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
                () -> null,
                () -> recycle,
                () -> null,
                () -> names,
                Messages.bundled());
        dispatcher = new CommandDispatcher<>();
        dispatcher.register(commands.buildCreate());
        dispatcher.register(commands.buildReset());
        dispatcher.register(commands.buildRename());
    }

    @Test
    @DisplayName("A create that failed in storage never shows the player the storage's words")
    void aFailedCreateHidesItsCause() throws Exception {
        when(create.execute(any(), any(), anyString(), any(ServerNodeId.class), anyString()))
                .thenReturn(new CreateIslandUseCase.CreateIslandResult.Failure(INTERNALS));

        assertThat(run("create"))
                .singleElement()
                .asString()
                .contains("could not be made")
                .doesNotContain("jdbc")
                .doesNotContain("Connection refused");
    }

    @Test
    @DisplayName("A confirmation the service refuses tells the player how to try again, not the service's sentence")
    void aRefusedConfirmationSaysWhatToDo() throws Exception {
        when(recycle.executeReset(any(), any(), any(), anyBoolean()))
                .thenReturn(CompletableFuture.completedFuture(new IslandRecycleService.RecycleResult.InvalidChallenge(
                        "Invalid or expired confirmation code.")));

        assertThat(run("reset confirm"))
                .singleElement()
                .asString()
                .contains("/is delete")
                .doesNotContain("code")
                .doesNotContain("Invalid or expired");
    }

    @Test
    @DisplayName("A reset that failed part way sends the player to an administrator, not to a stack trace")
    void aFailedResetHidesItsCause() throws Exception {
        when(recycle.executeReset(any(), any(), any(), anyBoolean()))
                .thenReturn(CompletableFuture.completedFuture(new IslandRecycleService.RecycleResult.Failure(
                        "Canonical island deletion failed: " + INTERNALS)));

        assertThat(run("reset confirm"))
                .singleElement()
                .asString()
                .contains("administrator")
                .doesNotContain("jdbc")
                .doesNotContain("Canonical");
    }

    @Test
    @DisplayName("A name of the wrong length names the bounds rather than the rule's own sentence")
    void aNameOfTheWrongLengthNamesTheBounds() throws Exception {
        refuseName(new IslandNameRefusedException(
                IslandNameRefusedException.Reason.LENGTH, "Island name length must be between 3 and 16 characters: 2"));

        assertThat(run("rename ab"))
                .singleElement()
                .asString()
                .contains("3 to 16 characters")
                .doesNotContain("Island name length");
    }

    @Test
    @DisplayName("Each refused name reads its own line")
    void eachRefusedNameReadsItsOwnLine() throws Exception {
        refuseName(new IslandNameRefusedException(IslandNameRefusedException.Reason.CHARACTERS, "x"));
        String characters = run("rename a*b").getFirst();
        refuseName(new IslandNameRefusedException(IslandNameRefusedException.Reason.RESERVED, "x"));
        String reserved = run("rename admin").getFirst();
        refuseName(new IslandNameRefusedException(IslandNameRefusedException.Reason.UNSAFE, "x"));
        String unsafe = run("rename rude").getFirst();
        refuseName(new IllegalStateException("Island name 'Rock' is already taken"));
        String taken = run("rename Rock").getFirst();
        refuseName(new SecurityException("Profile lacks permission"));
        String denied = run("rename Rock").getFirst();

        assertThat(characters).contains("letters, digits");
        assertThat(reserved).contains("server keeps that name");
        assertThat(unsafe).contains("not allowed here");
        assertThat(taken).contains("has that name already").doesNotContain("'Rock'");
        assertThat(denied).contains("cannot rename it").doesNotContain("Profile");
    }

    @Test
    @DisplayName("A Turkish player reads a refused name in Turkish, with the bounds in it")
    void aTurkishPlayerReadsTurkish() throws Exception {
        player.setLocale(Locale.forLanguageTag("tr"));
        refuseName(new IslandNameRefusedException(IslandNameRefusedException.Reason.LENGTH, "Island name length"));

        assertThat(run("rename ab"))
                .singleElement()
                .asString()
                .contains("3 ile 16 karakter")
                .doesNotContain("Island");
    }

    private void refuseName(RuntimeException refusal) {
        doThrow(refusal).when(names).renameIsland(any(), any(), anyString());
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
        doAnswer(call -> {
                    call.getArgument(3, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .onRegion(anyString(), anyInt(), anyInt(), any(Runnable.class));
        return scheduler;
    }
}
