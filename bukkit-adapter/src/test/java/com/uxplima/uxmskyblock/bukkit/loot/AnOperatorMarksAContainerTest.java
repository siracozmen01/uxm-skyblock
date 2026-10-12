package com.uxplima.uxmskyblock.bukkit.loot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.UUID;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.inventory.ItemStack;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import net.kyori.adventure.text.Component;

import com.mojang.brigadier.CommandDispatcher;
import com.uxplima.uxmskyblock.bukkit.command.ModeAdminCommands;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.command.ConsoleCommandSenderMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * {@code /isadmin loot mark}: an operator gives the container they look at a loot table the folder has, and it waits
 * to be filled the first time a player opens it.
 */
class AnOperatorMarksAContainerTest extends MockBukkitHarness {

    private World world;
    private Looking operator;
    private CommandDispatcher<CommandSourceStack> dispatcher;

    /** A player looking at whichever block the test says. */
    private static final class Looking extends PlayerMock {
        @Nullable Block looked;

        int reach;

        Looking(ServerMock server) {
            super(server, "Op", UUID.nameUUIDFromBytes("Op".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }

        @Override
        public @Nullable Block getTargetBlockExact(int maxDistance) {
            reach = maxDistance;
            return looked;
        }
    }

    @BeforeEach
    void setUp() {
        world = server.addSimpleWorld("loot");
        operator = new Looking(server);
        server.addPlayer(operator);
        operator.setOp(true);
        LootTables tables = LootTables.of(new LootTable(
                "gems",
                Span.of(1),
                Map.of("common", new Rarity("common", 1, "")),
                List.of(new LootEntry.Item(
                        "common", 1, new ItemStack(Material.EMERALD), Span.of(5), OptionalInt.empty()))));
        dispatcher = new CommandDispatcher<>();
        dispatcher.register(new ModeAdminCommands(
                        List.of(new LootAdmin(() -> tables)),
                        mock(IslandLocationService.class),
                        null,
                        mock(SchedulerPort.class),
                        Messages.bundled())
                .build(null, (source, tag) -> true)
                .orElseThrow());
    }

    private String run(CommandSender sender, String line) throws Exception {
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn(sender);
        dispatcher.execute(line, source);
        StringBuilder said = new StringBuilder();
        Component next;
        while ((next = sender instanceof PlayerMock player
                        ? player.nextComponentMessage()
                        : ((ConsoleCommandSenderMock) sender).nextComponentMessage())
                != null) {
            said.append(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                            .serialize(next))
                    .append('\n');
        }
        return said.toString();
    }

    @Test
    @DisplayName("The container looked at waits for the table, within reach")
    void marked() throws Exception {
        Block chest = world.getBlockAt(2, 64, 3);
        chest.setType(Material.CHEST);
        operator.looked = chest;

        assertThat(run(operator, "isadmin loot mark GEMS")).contains("2 64 3").contains("gems");

        assertThat(LootContainers.waitingFor(chest)).isEqualTo("gems");
        assertThat(operator.reach).isEqualTo(LootAdmin.REACH);
    }

    @Test
    @DisplayName("A table the folder lacks, a block that holds nothing and the console are each refused")
    void refused() throws Exception {
        Block stone = world.getBlockAt(0, 64, 0);
        stone.setType(Material.STONE);
        Block chest = world.getBlockAt(1, 64, 0);
        chest.setType(Material.CHEST);

        operator.looked = chest;
        assertThat(run(operator, "isadmin loot mark rubies")).contains("rubies").contains("gems");
        assertThat(LootContainers.waitingFor(chest)).isNull();
        operator.looked = stone;
        assertThat(run(operator, "isadmin loot mark gems")).contains("Look at a chest");
        operator.looked = null;
        assertThat(run(operator, "isadmin loot mark gems")).contains("Look at a chest");
        assertThat(run(server.getConsoleSender(), "isadmin loot mark gems")).contains("in game");
    }
}
