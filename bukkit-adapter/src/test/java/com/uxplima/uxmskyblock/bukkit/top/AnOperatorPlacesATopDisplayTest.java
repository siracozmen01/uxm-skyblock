package com.uxplima.uxmskyblock.bukkit.top;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import net.kyori.adventure.text.Component;

import com.mojang.brigadier.CommandDispatcher;
import com.uxplima.uxmskyblock.bukkit.command.ModeAdminCommands;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.test.InlineSchedulerPort;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.domain.leaderboard.LeaderboardCategory;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.command.ConsoleCommandSenderMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** {@code /isadmin top}: an operator places a display on the sign or head looked at, or where they stand. */
class AnOperatorPlacesATopDisplayTest extends MockBukkitHarness {

    @TempDir
    @SuppressWarnings("NullAway.Init")
    Path folder;

    private World world;
    private Looking operator;
    private TopDisplays displays;
    private final List<String> drawn = new ArrayList<>();
    private final List<String> cleared = new ArrayList<>();
    private CommandDispatcher<CommandSourceStack> dispatcher;

    /** A player looking at whichever block the test says. */
    private static final class Looking extends PlayerMock {
        @Nullable Block looked;

        Looking(ServerMock server) {
            super(server, "Op", UUID.nameUUIDFromBytes("Op".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }

        @Override
        public @Nullable Block getTargetBlockExact(int maxDistance) {
            return maxDistance == TopAdmin.REACH ? looked : null;
        }
    }

    @BeforeEach
    void setUp() {
        world = server.addSimpleWorld("tops");
        operator = new Looking(server);
        server.addPlayer(operator);
        operator.setOp(true);
        operator.teleport(new Location(world, 10.5, 70, -3.5));
        displays = TopDisplays.read(folder.resolve("top-displays.conf"));
        TopDisplayBeat beat = new TopDisplayBeat(
                displays,
                (board, rank) -> List.of(),
                island -> Optional.empty(),
                player -> Optional.empty(),
                Messages.bundled(),
                new InlineSchedulerPort(),
                new TopPainter() {
                    @Override
                    public void paint(TopDisplay display, Optional<Shown> shown) {
                        drawn.add(display.place());
                    }

                    @Override
                    public void clear(TopDisplay display) {
                        cleared.add(display.place());
                    }
                });
        dispatcher = new CommandDispatcher<>();
        dispatcher.register(new ModeAdminCommands(
                        List.of(new TopAdmin(beat)),
                        mock(IslandLocationService.class),
                        null,
                        new InlineSchedulerPort(),
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
    @DisplayName("A sign or a head looked at, and a line where the operator stands, each show their place at once")
    void placed() throws Exception {
        Block sign = world.getBlockAt(2, 64, 3);
        sign.setType(Material.OAK_SIGN);
        operator.looked = sign;
        assertThat(run(operator, "isadmin top add LEVEL 1 sign"))
                .contains("tops 2 64 3")
                .contains("level");

        Block head = world.getBlockAt(4, 64, 3);
        head.setType(Material.PLAYER_HEAD);
        operator.looked = head;
        run(operator, "isadmin top add worth 2 head");

        operator.looked = null;
        run(operator, "isadmin top add bank 3 Text");

        assertThat(displays.all())
                .containsExactlyInAnyOrder(
                        new TopDisplay("tops", 2, 64, 3, TopDisplay.Kind.SIGN, LeaderboardCategory.LEVEL, 1),
                        new TopDisplay("tops", 4, 64, 3, TopDisplay.Kind.HEAD, LeaderboardCategory.WORTH, 2),
                        new TopDisplay("tops", 10, 70, -4, TopDisplay.Kind.TEXT, LeaderboardCategory.BANK, 3));
        assertThat(drawn).containsExactly("tops 2 64 3", "tops 4 64 3", "tops 10 70 -4");
    }

    @Test
    @DisplayName("A board, a rank, a kind or a block that is not one, and the console, are each refused")
    void refused() throws Exception {
        Block stone = world.getBlockAt(0, 64, 0);
        stone.setType(Material.STONE);
        operator.looked = stone;

        assertThat(run(operator, "isadmin top add fame 1 sign")).contains("level, worth, bank");
        assertThat(run(operator, "isadmin top add level 0 sign")).contains("100");
        assertThat(run(operator, "isadmin top add level 101 sign")).contains("100");
        assertThat(run(operator, "isadmin top add level 1 statue"))
                .contains("sign")
                .contains("head");
        assertThat(run(operator, "isadmin top add level 1 sign")).contains("Look at");
        assertThat(run(operator, "isadmin top add level 1 head")).contains("Look at");
        operator.looked = null;
        assertThat(run(operator, "isadmin top add level 1 sign")).contains("Look at");
        assertThat(run(server.getConsoleSender(), "isadmin top add level 1 text"))
                .contains("in the game");
        assertThat(displays.all()).isEmpty();
        assertThat(drawn).isEmpty();
    }

    @Test
    @DisplayName("The display looked at, or the one stood on, is taken away and wiped; the list names each")
    void removedAndListed() throws Exception {
        assertThat(run(operator, "isadmin top list")).contains("No display");
        displays.add(new TopDisplay("tops", 2, 64, 3, TopDisplay.Kind.SIGN, LeaderboardCategory.LEVEL, 1));
        displays.add(new TopDisplay("tops", 10, 70, -4, TopDisplay.Kind.TEXT, LeaderboardCategory.BANK, 3));
        assertThat(run(server.getConsoleSender(), "isadmin top list"))
                .contains("sign at tops 2 64 3: place 1 of level")
                .contains("text at tops 10 70 -4: place 3 of bank");

        operator.looked = world.getBlockAt(2, 64, 3);
        assertThat(run(operator, "isadmin top remove")).contains("tops 2 64 3");
        operator.looked = world.getBlockAt(0, 0, 0);
        assertThat(run(operator, "isadmin top remove")).contains("tops 10 70 -4");
        assertThat(run(operator, "isadmin top remove")).contains("No display");
        assertThat(cleared).containsExactly("tops 2 64 3", "tops 10 70 -4");
        assertThat(displays.all()).isEmpty();
        assertThat(run(server.getConsoleSender(), "isadmin top remove")).contains("in the game");
    }
}
