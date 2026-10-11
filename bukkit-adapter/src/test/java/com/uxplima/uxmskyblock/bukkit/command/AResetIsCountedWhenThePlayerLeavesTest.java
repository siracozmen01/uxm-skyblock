package com.uxplima.uxmskyblock.bukkit.command;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import com.mojang.brigadier.CommandDispatcher;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.schematic.StarterSchematicEngine;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.island.CreateIslandUseCase;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.preset.StarterPresetCatalog;
import com.uxplima.uxmskyblock.core.application.recycle.IslandRecycleService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * A reset is counted against the player's allowance whether or not they are still online.
 *
 * <p>The count was written on the player's own thread after the erasure, so a player who left
 * while their island was being erased was never counted, and could reset as often as they liked by
 * logging out each time. It was also a database write on the region thread. The count is now written
 * when the erasure finishes, off any player's thread.
 */
class AResetIsCountedWhenThePlayerLeavesTest extends MockBukkitHarness {

    private static final IslandId ISLAND = IslandId.of(UUID.randomUUID());
    private static final ProfileId PROFILE = new ProfileId(UUID.randomUUID());
    private static final String WORLD = "skyblock_world";

    @Test
    @DisplayName("A player who left before the erasure finished still has the reset counted")
    void aResetIsCountedWithThePlayerGone() throws Exception {
        server.addSimpleWorld(WORLD);
        PlayerMock player = createRegionThreadedPlayer("Leaver");
        IslandRecycleService recycle = mock(IslandRecycleService.class);
        org.mockito.Mockito.lenient()
                .when(recycle.generateResetChallenge(
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new com.uxplima.uxmskyblock.core.domain.recycle.ResetChallenge(
                        "1234", java.time.Instant.now().plusSeconds(60)));
        when(recycle.executeReset(any(), any(), any(), anyBoolean()))
                .thenReturn(CompletableFuture.completedFuture(
                        new IslandRecycleService.RecycleResult.Success(ISLAND, 3L, WORLD, 1, 2)));
        IslandLocationService locations = mock(IslandLocationService.class);
        when(locations.findIslandId(PROFILE)).thenReturn(Optional.of(ISLAND));
        PlayerSessionCoordinator sessions = mock(PlayerSessionCoordinator.class);
        when(sessions.activeProfile(player.getUniqueId())).thenReturn(Optional.of(PROFILE));
        com.uxplima.uxmskyblock.core.application.antiabuse.IslandAntiAbuseService antiAbuse =
                mock(com.uxplima.uxmskyblock.core.application.antiabuse.IslandAntiAbuseService.class);
        when(antiAbuse.checkResetAllowed(any(), anyBoolean()))
                .thenReturn(new com.uxplima.uxmskyblock.core.domain.antiabuse.ResetCheckResult.Allowed(3));
        when(antiAbuse.beginReset(any())).thenReturn(true);

        SchedulerPort gone = mock(SchedulerPort.class);
        doAnswer(call -> {
                    call.getArgument(0, Runnable.class).run();
                    return null;
                })
                .when(gone)
                .async(any(Runnable.class));
        // The player's thread never gets to anything: they have gone.

        IslandLifecycleCommands commands = new IslandLifecycleCommands(
                mock(CreateIslandUseCase.class),
                locations,
                new StarterPresetCatalog(),
                mock(StarterSchematicEngine.class),
                mock(IslandProtectionListener.class),
                sessions,
                gone,
                ServerNodeId.of("node-1"),
                WORLD,
                () -> antiAbuse,
                () -> recycle,
                () -> null,
                () -> null,
                Messages.bundled());
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        dispatcher.register(commands.buildReset());
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn(player);

        dispatcher.execute("reset confirm", source);

        verify(antiAbuse).recordReset(org.mockito.ArgumentMatchers.eq(new PlayerUuid(player.getUniqueId())), any());
    }

    @Test
    @DisplayName("A player who left before the erasure finished still owes the inventory purge")
    void aPurgeIsOwedWithThePlayerGone() throws Exception {
        server.addSimpleWorld(WORLD);
        PlayerMock player = createRegionThreadedPlayer("Leaver2");
        IslandRecycleService recycle = mock(IslandRecycleService.class);
        org.mockito.Mockito.lenient()
                .when(recycle.generateResetChallenge(
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new com.uxplima.uxmskyblock.core.domain.recycle.ResetChallenge(
                        "1234", java.time.Instant.now().plusSeconds(60)));
        when(recycle.executeReset(any(), any(), any(), anyBoolean()))
                .thenReturn(CompletableFuture.completedFuture(
                        new IslandRecycleService.RecycleResult.Success(ISLAND, 3L, WORLD, 1, 2)));
        IslandLocationService locations = mock(IslandLocationService.class);
        when(locations.findIslandId(PROFILE)).thenReturn(Optional.of(ISLAND));
        PlayerSessionCoordinator sessions = mock(PlayerSessionCoordinator.class);
        when(sessions.activeProfile(player.getUniqueId())).thenReturn(Optional.of(PROFILE));
        com.uxplima.uxmskyblock.core.application.antiabuse.IslandAntiAbuseService antiAbuse =
                mock(com.uxplima.uxmskyblock.core.application.antiabuse.IslandAntiAbuseService.class);
        when(antiAbuse.checkResetAllowed(any(), anyBoolean()))
                .thenReturn(new com.uxplima.uxmskyblock.core.domain.antiabuse.ResetCheckResult.Allowed(3));
        when(antiAbuse.beginReset(any())).thenReturn(true);
        when(antiAbuse.purgeInventoryOnReset()).thenReturn(true);
        SchedulerPort gone = mock(SchedulerPort.class);
        doAnswer(call -> {
                    call.getArgument(0, Runnable.class).run();
                    return null;
                })
                .when(gone)
                .async(any(Runnable.class));
        IslandLifecycleCommands commands = new IslandLifecycleCommands(
                mock(CreateIslandUseCase.class),
                locations,
                new StarterPresetCatalog(),
                mock(StarterSchematicEngine.class),
                mock(IslandProtectionListener.class),
                sessions,
                gone,
                ServerNodeId.of("node-1"),
                WORLD,
                () -> antiAbuse,
                () -> recycle,
                () -> null,
                () -> null,
                Messages.bundled());
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        dispatcher.register(commands.buildReset());
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn(player);

        dispatcher.execute("reset confirm", source);

        verify(antiAbuse).oweInventoryPurge(new PlayerUuid(player.getUniqueId()));
        verify(antiAbuse, org.mockito.Mockito.never()).settleInventoryPurge(any());
    }

    @Test
    @DisplayName("A reset confirmed in the window is held to the same rules and counted like a typed one")
    void aResetFromTheWindowIsCounted() throws Exception {
        server.addSimpleWorld(WORLD);
        PlayerMock player = createRegionThreadedPlayer("Clicker");
        IslandRecycleService recycle = mock(IslandRecycleService.class);
        org.mockito.Mockito.lenient()
                .when(recycle.generateResetChallenge(
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new com.uxplima.uxmskyblock.core.domain.recycle.ResetChallenge(
                        "1234", java.time.Instant.now().plusSeconds(60)));
        when(recycle.generateResetChallenge(any(), any()))
                .thenReturn(new com.uxplima.uxmskyblock.core.domain.recycle.ResetChallenge(
                        "4321", java.time.Instant.now().plusSeconds(60)));
        when(recycle.executeReset(any(), any(), any(), anyBoolean()))
                .thenReturn(CompletableFuture.completedFuture(
                        new IslandRecycleService.RecycleResult.Success(ISLAND, 3L, WORLD, 1, 2)));
        IslandLocationService locations = mock(IslandLocationService.class);
        when(locations.findIslandId(PROFILE)).thenReturn(Optional.of(ISLAND));
        PlayerSessionCoordinator sessions = mock(PlayerSessionCoordinator.class);
        when(sessions.activeProfile(player.getUniqueId())).thenReturn(Optional.of(PROFILE));
        com.uxplima.uxmskyblock.core.application.antiabuse.IslandAntiAbuseService antiAbuse =
                mock(com.uxplima.uxmskyblock.core.application.antiabuse.IslandAntiAbuseService.class);
        when(antiAbuse.checkResetAllowed(any(), anyBoolean()))
                .thenReturn(new com.uxplima.uxmskyblock.core.domain.antiabuse.ResetCheckResult.Allowed(3));
        when(antiAbuse.beginReset(any())).thenReturn(true);
        com.uxplima.uxmskyblock.bukkit.menu.IslandResetConfirmationMenu window =
                mock(com.uxplima.uxmskyblock.bukkit.menu.IslandResetConfirmationMenu.class);
        SchedulerPort inline = mock(SchedulerPort.class);
        doAnswer(call -> {
                    call.getArgument(0, Runnable.class).run();
                    return null;
                })
                .when(inline)
                .async(any(Runnable.class));
        doAnswer(call -> {
                    call.getArgument(1, Runnable.class).run();
                    return null;
                })
                .when(inline)
                .onEntity(any(PlayerUuid.class), any(Runnable.class));
        IslandLifecycleCommands commands = new IslandLifecycleCommands(
                mock(CreateIslandUseCase.class),
                locations,
                new StarterPresetCatalog(),
                mock(StarterSchematicEngine.class),
                mock(IslandProtectionListener.class),
                sessions,
                inline,
                ServerNodeId.of("node-1"),
                WORLD,
                () -> antiAbuse,
                () -> recycle,
                () -> window,
                () -> null,
                Messages.bundled());
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        dispatcher.register(commands.buildReset());
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn(player);
        dispatcher.execute("reset", source);
        org.mockito.ArgumentCaptor<Runnable> confirm = org.mockito.ArgumentCaptor.forClass(Runnable.class);
        verify(window).open(org.mockito.ArgumentMatchers.eq(player), confirm.capture());
        verify(recycle, org.mockito.Mockito.never()).generateResetChallenge(any(), any());

        confirm.getValue().run();

        verify(antiAbuse).beginReset(new PlayerUuid(player.getUniqueId()));
        verify(antiAbuse).recordReset(org.mockito.ArgumentMatchers.eq(new PlayerUuid(player.getUniqueId())), any());
        verify(recycle).executeReset(PROFILE, ISLAND, "4321", false);
    }

    @Test
    @DisplayName("An erasure that throws before it starts gives the reset back and says it failed")
    void aResetThatThrowsIsGivenBack() throws Exception {
        server.addSimpleWorld(WORLD);
        PlayerMock player = createRegionThreadedPlayer("Thrown");
        IslandRecycleService recycle = mock(IslandRecycleService.class);
        org.mockito.Mockito.lenient()
                .when(recycle.generateResetChallenge(
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new com.uxplima.uxmskyblock.core.domain.recycle.ResetChallenge(
                        "1234", java.time.Instant.now().plusSeconds(60)));
        when(recycle.executeReset(any(), any(), any(), anyBoolean()))
                .thenThrow(new UnsupportedOperationException("Not on any region"));
        IslandLocationService locations = mock(IslandLocationService.class);
        when(locations.findIslandId(PROFILE)).thenReturn(Optional.of(ISLAND));
        PlayerSessionCoordinator sessions = mock(PlayerSessionCoordinator.class);
        when(sessions.activeProfile(player.getUniqueId())).thenReturn(Optional.of(PROFILE));
        com.uxplima.uxmskyblock.core.application.antiabuse.IslandAntiAbuseService antiAbuse =
                mock(com.uxplima.uxmskyblock.core.application.antiabuse.IslandAntiAbuseService.class);
        when(antiAbuse.checkResetAllowed(any(), anyBoolean()))
                .thenReturn(new com.uxplima.uxmskyblock.core.domain.antiabuse.ResetCheckResult.Allowed(3));
        when(antiAbuse.beginReset(any())).thenReturn(true);
        SchedulerPort inline = mock(SchedulerPort.class);
        doAnswer(call -> {
                    call.getArgument(0, Runnable.class).run();
                    return null;
                })
                .when(inline)
                .async(any(Runnable.class));
        doAnswer(call -> {
                    call.getArgument(1, Runnable.class).run();
                    return null;
                })
                .when(inline)
                .onEntity(any(PlayerUuid.class), any(Runnable.class));
        IslandLifecycleCommands commands = new IslandLifecycleCommands(
                mock(CreateIslandUseCase.class),
                locations,
                new StarterPresetCatalog(),
                mock(StarterSchematicEngine.class),
                mock(IslandProtectionListener.class),
                sessions,
                inline,
                ServerNodeId.of("node-1"),
                WORLD,
                () -> antiAbuse,
                () -> recycle,
                () -> null,
                () -> null,
                Messages.bundled());
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        dispatcher.register(commands.buildReset());
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn(player);

        dispatcher.execute("reset confirm", source);

        verify(antiAbuse).endReset(new PlayerUuid(player.getUniqueId()));
        org.assertj.core.api.Assertions.assertThat(
                        net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                                .serialize(java.util.Objects.requireNonNull(player.nextComponentMessage())))
                .contains("could not finish");
    }

    @Test
    @DisplayName("Everyone who belonged to the island is told it was reset: at once if here, on their next join if not")
    void theMembersAreTold() throws Exception {
        server.addSimpleWorld(WORLD);
        PlayerMock owner = createRegionThreadedPlayer("Owner");
        PlayerMock here = createRegionThreadedPlayer("Here");
        ProfileId hereProfile = new ProfileId(UUID.randomUUID());
        ProfileId awayProfile = new ProfileId(UUID.randomUUID());
        com.uxplima.uxmskyblock.core.domain.island.Island island =
                com.uxplima.uxmskyblock.core.domain.island.Island.create(
                                ISLAND,
                                com.uxplima.uxmskyblock.core.domain.island.IslandBounds.fromCenterAndRadius(0, 0, 50),
                                new PlayerUuid(owner.getUniqueId()),
                                PROFILE,
                                java.time.Instant.now())
                        .addMember(new com.uxplima.uxmskyblock.core.domain.island.IslandMember(
                                new PlayerUuid(here.getUniqueId()),
                                hereProfile,
                                com.uxplima.uxmskyblock.core.domain.island.IslandRole.MEMBER,
                                java.time.Instant.now()))
                        .addMember(new com.uxplima.uxmskyblock.core.domain.island.IslandMember(
                                new PlayerUuid(UUID.randomUUID()),
                                awayProfile,
                                com.uxplima.uxmskyblock.core.domain.island.IslandRole.MEMBER,
                                java.time.Instant.now()));
        IslandRecycleService recycle = mock(IslandRecycleService.class);
        org.mockito.Mockito.lenient()
                .when(recycle.generateResetChallenge(
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new com.uxplima.uxmskyblock.core.domain.recycle.ResetChallenge(
                        "1234", java.time.Instant.now().plusSeconds(60)));
        when(recycle.executeReset(any(), any(), any(), anyBoolean()))
                .thenReturn(CompletableFuture.completedFuture(
                        new IslandRecycleService.RecycleResult.Success(ISLAND, 3L, WORLD, 1, 2)));
        IslandLocationService locations = mock(IslandLocationService.class);
        when(locations.findIslandId(PROFILE)).thenReturn(Optional.of(ISLAND));
        when(locations.findIsland(ISLAND)).thenReturn(Optional.of(island));
        PlayerSessionCoordinator sessions = mock(PlayerSessionCoordinator.class);
        when(sessions.activeProfile(owner.getUniqueId())).thenReturn(Optional.of(PROFILE));
        com.uxplima.uxmskyblock.core.application.antiabuse.IslandAntiAbuseService antiAbuse =
                mock(com.uxplima.uxmskyblock.core.application.antiabuse.IslandAntiAbuseService.class);
        when(antiAbuse.checkResetAllowed(any(), anyBoolean()))
                .thenReturn(new com.uxplima.uxmskyblock.core.domain.antiabuse.ResetCheckResult.Allowed(3));
        when(antiAbuse.beginReset(any())).thenReturn(true);
        SchedulerPort inline = mock(SchedulerPort.class);
        doAnswer(call -> {
                    call.getArgument(0, Runnable.class).run();
                    return null;
                })
                .when(inline)
                .async(any(Runnable.class));
        doAnswer(call -> {
                    call.getArgument(1, Runnable.class).run();
                    return null;
                })
                .when(inline)
                .onEntity(any(PlayerUuid.class), any(Runnable.class));
        IslandLifecycleCommands commands = new IslandLifecycleCommands(
                mock(CreateIslandUseCase.class),
                locations,
                new StarterPresetCatalog(),
                mock(StarterSchematicEngine.class),
                mock(IslandProtectionListener.class),
                sessions,
                inline,
                ServerNodeId.of("node-1"),
                WORLD,
                () -> antiAbuse,
                () -> recycle,
                () -> null,
                () -> null,
                Messages.bundled());
        com.uxplima.uxmskyblock.core.application.notification.NotificationService notices =
                mock(com.uxplima.uxmskyblock.core.application.notification.NotificationService.class);
        commands.useNotifications(notices);
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        dispatcher.register(commands.buildReset());
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn(owner);

        dispatcher.execute("reset confirm", source);

        org.assertj.core.api.Assertions.assertThat(
                        net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                                .serialize(java.util.Objects.requireNonNull(here.nextComponentMessage())))
                .contains("Owner deleted the island you belonged to");
        verify(notices)
                .notify(
                        org.mockito.ArgumentMatchers.eq(awayProfile),
                        org.mockito.ArgumentMatchers.eq(
                                com.uxplima.uxmskyblock.core.domain.notification.NotificationCategory.RESET),
                        org.mockito.ArgumentMatchers.eq("notification.island_reset"),
                        org.mockito.ArgumentMatchers.eq(java.util.Map.of("player", "Owner")),
                        org.mockito.ArgumentMatchers.isNull());
        verify(notices, org.mockito.Mockito.never())
                .notify(org.mockito.ArgumentMatchers.eq(PROFILE), any(), any(), any(), any());
        verify(notices, org.mockito.Mockito.never())
                .notify(org.mockito.ArgumentMatchers.eq(hereProfile), any(), any(), any(), any());
        java.util.List<String> ownerRead = new java.util.ArrayList<>();
        for (net.kyori.adventure.text.Component line = owner.nextComponentMessage();
                line != null;
                line = owner.nextComponentMessage()) {
            ownerRead.add(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                    .serialize(line));
        }
        org.assertj.core.api.Assertions.assertThat(ownerRead)
                .describedAs("the owner reset it, and is told so, not that somebody reset it")
                .noneMatch(line -> line.contains("the island you belonged to"));
    }
}
