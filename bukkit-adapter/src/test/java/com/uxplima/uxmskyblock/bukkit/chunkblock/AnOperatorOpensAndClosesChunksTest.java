package com.uxplima.uxmskyblock.bukkit.chunkblock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.World;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import net.kyori.adventure.text.Component;

import com.mojang.brigadier.CommandDispatcher;
import com.uxplima.uxmskyblock.bukkit.command.ModeAdminCommands;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.chunkblock.ChunkBlockService;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkPos;
import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkUnlockRules;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.command.ConsoleCommandSenderMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * {@code /isadmin chunkblock}: an operator opens a chunk that stays open, closes one, never the first, and resets an
 * island to its first chunk, the chunk named or the one they stand in, and whoever stood in a closed one is told.
 */
class AnOperatorOpensAndClosesChunksTest extends MockBukkitHarness {

    private static final IslandId ISLAND = IslandId.of(UUID.randomUUID());
    private static final IslandId ORDINARY = IslandId.of(UUID.randomUUID());

    private final ChunkBlockStores.Territories territories = new ChunkBlockStores.Territories();
    private final List<List<ChunkPos>> closedTold = new ArrayList<>();
    private ChunkBlockService service;
    private CommandDispatcher<CommandSourceStack> dispatcher;
    private PlayerMock operator;
    private World world;

    @BeforeEach
    void setUp() {
        world = server.addSimpleWorld("skyblock");
        service = new ChunkBlockService(territories, new ChunkUnlockRules(List.of(1L, 3L), 3));
        service.start(ISLAND, 8, 8);
        service.whenClosed((island, closed) -> closedTold.add(closed));
        IslandLocationService locations = mock(IslandLocationService.class);
        when(locations.findLocation(any(IslandId.class))).thenReturn(Optional.empty());
        when(locations.findLocation(ISLAND))
                .thenReturn(Optional.of(IslandLocation.fromCenterAndRadius(ISLAND, "skyblock", 8, 8, 40)));
        SchedulerPort scheduler = mock(SchedulerPort.class);
        doAnswer(call -> {
                    call.getArgument(0, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .async(any(Runnable.class));
        dispatcher = new CommandDispatcher<>();
        dispatcher.register(new ModeAdminCommands(
                        List.of(new ChunkBlockAdmin(service)), locations, null, scheduler, Messages.bundled())
                .build(null, (source, tag) -> true)
                .orElseThrow());
        operator = server.addPlayer("Op");
        operator.setOp(true);
    }

    private String run(org.bukkit.command.CommandSender sender, String line) throws Exception {
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn(sender);
        dispatcher.execute(line, source);
        StringBuilder said = new StringBuilder();
        Component next;
        while ((next = nextOf(sender)) != null) {
            said.append(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                            .serialize(next))
                    .append('\n');
        }
        return said.toString();
    }

    private static Component nextOf(org.bukkit.command.CommandSender sender) {
        if (sender instanceof PlayerMock player) {
            return player.nextComponentMessage();
        }
        return ((ConsoleCommandSenderMock) sender).nextComponentMessage();
    }

    @Test
    @DisplayName("A chunk an operator opens stays open whatever the level does")
    void opened() throws Exception {
        assertThat(run(operator, "isadmin chunkblock open " + ISLAND.value() + " 1 0"))
                .contains("1 0")
                .contains("open");

        assertThat(territories.bought.get(ISLAND)).containsExactly(new ChunkPos(1, 0));
        assertThat(service.onLevel(ISLAND, 0))
                .describedAs("a low level closes nothing an operator opened")
                .isEmpty();
        assertThat(run(operator, "isadmin chunkblock open " + ISLAND.value() + " 1 0"))
                .contains("open already");
        assertThat(run(operator, "isadmin chunkblock open " + ISLAND.value() + " 0 2"))
                .contains("not beside");
        assertThat(run(operator, "isadmin chunkblock open " + ISLAND.value() + " 5 5"))
                .contains("outside the island");
    }

    @Test
    @DisplayName("Standing in a chunk names it, and the console, which stands nowhere, is asked to name one")
    void theChunkStoodIn() throws Exception {
        operator.teleport(new Location(world, -3, 70, 5));

        assertThat(run(operator, "isadmin chunkblock open " + ISLAND.value())).contains("-1 0");
        assertThat(service.isOpen(ISLAND, new ChunkPos(-1, 0))).contains(true);
        assertThat(run(server.getConsoleSender(), "isadmin chunkblock close " + ISLAND.value()))
                .contains("Name the chunk");
        assertThat(service.isOpen(ISLAND, new ChunkPos(-1, 0))).contains(true);
    }

    @Test
    @DisplayName("A chunk closes unless it is the first or cuts another off, and a reset leaves only the first")
    void closed() throws Exception {
        service.open(
                ISLAND,
                new ChunkPos(1, 0),
                IslandLocation.fromCenterAndRadius(ISLAND, "skyblock", 8, 8, 40).bounds(),
                false);
        service.open(
                ISLAND,
                new ChunkPos(2, 0),
                IslandLocation.fromCenterAndRadius(ISLAND, "skyblock", 8, 8, 40).bounds(),
                false);

        assertThat(run(operator, "isadmin chunkblock close " + ISLAND.value() + " 1 0"))
                .contains("cut other");
        assertThat(run(operator, "isadmin chunkblock close " + ISLAND.value() + " 0 0"))
                .contains("not one that can close");
        assertThat(run(operator, "isadmin chunkblock close " + ISLAND.value() + " 2 0"))
                .contains("is closed");
        assertThat(closedTold).containsExactly(List.of(new ChunkPos(2, 0)));

        assertThat(run(operator, "isadmin chunkblock reset " + ISLAND.value())).contains("1 chunks");
        assertThat(service.isOpen(ISLAND, new ChunkPos(1, 0))).contains(false);
        assertThat(service.isOpen(ISLAND, new ChunkPos(0, 0))).contains(true);
    }

    @Test
    @DisplayName("An island that is not a ChunkBlock island is said to be one no verb changes")
    void notChunkBlock() throws Exception {
        assertThat(run(operator, "isadmin chunkblock open " + ORDINARY.value() + " 0 0"))
                .contains("not a ChunkBlock");
        assertThat(run(operator, "isadmin chunkblock close " + ORDINARY.value() + " 0 0"))
                .contains("not a ChunkBlock");
        assertThat(run(operator, "isadmin chunkblock reset " + ORDINARY.value()))
                .contains("not a ChunkBlock");
    }
}
