package com.uxplima.uxmskyblock.bukkit.command;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.bukkit.entity.Player;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import net.kyori.adventure.text.Component;

import com.mojang.brigadier.CommandDispatcher;
import com.uxplima.uxmskyblock.bukkit.config.HomeConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.LanguageConfiguration;
import com.uxplima.uxmskyblock.bukkit.i18n.MessageProvider;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.parkour.ParkourBoard;
import com.uxplima.uxmskyblock.bukkit.schematic.StarterSchematicEngine;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.core.application.bank.IslandBankService;
import com.uxplima.uxmskyblock.core.application.biome.BiomeModificationPort;
import com.uxplima.uxmskyblock.core.application.flag.IslandFlagService;
import com.uxplima.uxmskyblock.core.application.island.CreateIslandUseCase;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.leaderboard.IslandLeaderboardService;
import com.uxplima.uxmskyblock.core.application.preset.StarterPresetCatalog;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.upgrade.IslandUpgradeStoragePort;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The course branch shows the best times on the course, and says so when the server has no Parkour courses.
 */
class TheCourseBranchShowsTheBoardTest {

    private final IslandCommandTree tree = new IslandCommandTree(
            mock(CreateIslandUseCase.class),
            mock(IslandLocationService.class),
            mock(IslandFlagService.class),
            mock(IslandBankService.class),
            mock(IslandUpgradeStoragePort.class),
            mock(IslandLeaderboardService.class),
            mock(BiomeModificationPort.class),
            new StarterPresetCatalog(),
            mock(StarterSchematicEngine.class),
            protectionListenerWithIndex(),
            mock(PlayerSessionCoordinator.class),
            inline(),
            Messages.of(new MessageProvider("en"), LanguageConfiguration.defaults()),
            HomeConfiguration.defaults(),
            ServerNodeId.of("node-1"),
            "world");

    private final Player player = mock(Player.class);

    @Test
    @DisplayName("The branch hands the player to the board")
    void theBranchShowsTheBoard() throws Exception {
        ParkourBoard board = mock(ParkourBoard.class);
        tree.useParkourBoard(board);

        dispatcher().execute("island course", source());

        verify(board).show(player);

        dispatcher().execute("island course season", source());
        verify(board).showSeason(player, java.util.OptionalInt.empty());
        dispatcher().execute("island course season 3", source());
        verify(board).showSeason(player, java.util.OptionalInt.of(3));
    }

    @Test
    @DisplayName("With Parkour off the branch says the player is on no course")
    void withParkourOffThePlayerIsTold() throws Exception {
        dispatcher().execute("island course", source());

        verify(player).sendMessage(any(Component.class));

        dispatcher().execute("island course season", source());
        verify(player, org.mockito.Mockito.times(2)).sendMessage(any(Component.class));
    }

    @Test
    @DisplayName("Nothing is shown once the board is taken away")
    void noBoardShowsNothing() throws Exception {
        ParkourBoard board = mock(ParkourBoard.class);
        tree.useParkourBoard(board);
        tree.useParkourBoard(null);

        dispatcher().execute("island course", source());

        verify(board, never()).show(any());
    }

    @Test
    @DisplayName("The tag and rate branches hand the player to the tagging, and say so when the server has none")
    void theTagAndRateBranches() throws Exception {
        com.uxplima.uxmskyblock.bukkit.parkour.ParkourTagging tagging =
                mock(com.uxplima.uxmskyblock.bukkit.parkour.ParkourTagging.class);
        tree.useParkourTagging(tagging);

        dispatcher().execute("island coursetag difficulty hard", source());
        dispatcher().execute("island coursetag category", source());
        dispatcher().execute("island courserate 4", source());

        verify(tagging).tag(player, com.uxplima.uxmskyblock.bukkit.parkour.ParkourTagging.Kind.DIFFICULTY, "hard");
        verify(tagging).tag(player, com.uxplima.uxmskyblock.bukkit.parkour.ParkourTagging.Kind.CATEGORY, null);
        verify(tagging).rate(player, 4);

        com.uxplima.uxmskyblock.bukkit.parkour.ParkourCourses courses =
                mock(com.uxplima.uxmskyblock.bukkit.parkour.ParkourCourses.class);
        tree.useParkourCourses(courses);
        dispatcher().execute("island courses", source());
        verify(courses).open(player);

        tree.useParkourCourses(null);
        dispatcher().execute("island courses", source());
        tree.useParkourTagging(null);
        dispatcher().execute("island courserate 4", source());
        verify(player, org.mockito.Mockito.times(2)).sendMessage(any(Component.class));
    }

    private CommandDispatcher<CommandSourceStack> dispatcher() {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        dispatcher.register(tree.buildRoot());
        return dispatcher;
    }

    private CommandSourceStack source() {
        when(player.hasPermission(anyString())).thenReturn(true);
        when(player.getUniqueId()).thenReturn(java.util.UUID.randomUUID());
        when(player.isOnline()).thenReturn(true);
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn(player);
        return source;
    }

    /** A scheduler that runs what the player's thread would run, at once. */
    private static SchedulerPort inline() {
        SchedulerPort scheduler = mock(SchedulerPort.class);
        org.mockito.Mockito.doAnswer(call -> {
                    call.getArgument(1, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .onEntity(any(com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid.class), any(Runnable.class));
        return scheduler;
    }

    private static IslandProtectionListener protectionListenerWithIndex() {
        IslandProtectionListener listener = mock(IslandProtectionListener.class);
        when(listener.spatialIndex())
                .thenReturn(new com.uxplima.uxmskyblock.bukkit.spatial.SpatialIslandIndex(
                        mock(com.uxplima.uxmskyblock.core.application.island.IslandStoragePort.class), null));
        return listener;
    }
}
