package com.uxplima.uxmskyblock.bukkit.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.bukkit.Location;
import org.bukkit.World;

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
import com.uxplima.uxmskyblock.core.application.preset.StarterPresetCatalog;
import com.uxplima.uxmskyblock.core.application.recycle.IslandRecycleService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * A reset that finished on a region threaded server.
 *
 * <p>The player was sent to spawn with a synchronous teleport, which Folia refuses by throwing. The
 * island was already gone, so the player was left standing over the void where it had been, and
 * the two lines saying it was reset never came.
 */
class AResetOnARegionThreadedServerTest extends MockBukkitHarness {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private static final IslandId ISLAND = IslandId.of(UUID.randomUUID());
    private static final ProfileId PROFILE = new ProfileId(UUID.randomUUID());
    private static final String WORLD = "skyblock_world";

    private PlayerMock player;
    private World world;
    private CommandDispatcher<CommandSourceStack> dispatcher;

    @BeforeEach
    void setUp() {
        world = server.addSimpleWorld(WORLD);
        player = createRegionThreadedPlayer("Resetter");
        world.setSpawnLocation(12, 70, -34);

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

        IslandLifecycleCommands commands = new IslandLifecycleCommands(
                mock(CreateIslandUseCase.class),
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
                () -> null,
                Messages.bundled());
        dispatcher = new CommandDispatcher<>();
        dispatcher.register(commands.buildReset());
    }

    @Test
    @DisplayName(
            "A finished reset sends the player to spawn and says so, on a server that refuses a synchronous teleport")
    void aFinishedResetMovesThePlayerAndSaysSo() throws Exception {
        assertThat(player.teleportAsync(new Location(world, 500, 64, 500))).isCompletedWithValue(true);

        List<String> said = run("reset confirm");

        Location at = player.getLocation();
        assertThat(at.getBlockX()).isEqualTo(12);
        assertThat(at.getBlockZ()).isEqualTo(-34);
        assertThat(said).anyMatch(line -> line.contains("is deleted")).anyMatch(line -> line.contains("/is create"));
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
