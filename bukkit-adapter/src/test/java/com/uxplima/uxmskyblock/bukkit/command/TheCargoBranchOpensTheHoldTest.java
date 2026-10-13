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
import com.uxplima.uxmskyblock.bukkit.schematic.StarterSchematicEngine;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.bukkit.tradewinds.CargoHolds;
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
 * The cargo, sail and market branches open the hold and the harbour of the vessel the player is aboard,
 * and say so when there is none.
 */
class TheCargoBranchOpensTheHoldTest {

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
    @DisplayName("The branch opens the hold for the player")
    void theBranchOpens() throws Exception {
        CargoHolds holds = mock(CargoHolds.class);
        tree.useCargoHolds(holds);

        dispatcher().execute("island cargo", source());

        verify(holds).open(player);
    }

    @Test
    @DisplayName("With TradeWinds off the branch says so and opens nothing")
    void withTradeWindsOffThePlayerIsTold() throws Exception {
        CargoHolds holds = mock(CargoHolds.class);
        tree.useCargoHolds(holds);
        tree.useCargoHolds(null);

        dispatcher().execute("island cargo", source());

        verify(player).sendMessage(any(Component.class));
        verify(holds, never()).open(any());
    }

    @Test
    @DisplayName("The sail and market branches open the harbour, and say so when TradeWinds is off")
    void theHarbourBranches() throws Exception {
        com.uxplima.uxmskyblock.bukkit.tradewinds.Harbour harbour =
                mock(com.uxplima.uxmskyblock.bukkit.tradewinds.Harbour.class);
        tree.useHarbour(harbour);

        dispatcher().execute("island sail", source());
        dispatcher().execute("island market", source());

        verify(harbour).sail(player);
        verify(harbour).market(player);

        tree.useHarbour(null);
        dispatcher().execute("island market", source());
        verify(player).sendMessage(any(Component.class));
    }

    @Test
    @DisplayName("The contracts branch opens the desk, and says so when TradeWinds is off")
    void theContractsBranch() throws Exception {
        com.uxplima.uxmskyblock.bukkit.tradewinds.ContractDesk desk =
                mock(com.uxplima.uxmskyblock.bukkit.tradewinds.ContractDesk.class);
        tree.useContracts(desk);

        dispatcher().execute("island contracts", source());

        verify(desk).open(player);

        tree.useContracts(null);
        dispatcher().execute("island contracts", source());
        verify(player).sendMessage(any(Component.class));
        verify(desk).open(player);
    }

    @Test
    @DisplayName("The plunder branch boards, and says so when TradeWinds is off")
    void thePlunderBranch() throws Exception {
        com.uxplima.uxmskyblock.bukkit.tradewinds.Boarding boarding =
                mock(com.uxplima.uxmskyblock.bukkit.tradewinds.Boarding.class);
        tree.useBoarding(boarding);

        dispatcher().execute("island plunder", source());

        verify(boarding).plunder(player);

        tree.useBoarding(null);
        dispatcher().execute("island plunder", source());
        verify(player).sendMessage(any(Component.class));
        verify(boarding).plunder(player);
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
