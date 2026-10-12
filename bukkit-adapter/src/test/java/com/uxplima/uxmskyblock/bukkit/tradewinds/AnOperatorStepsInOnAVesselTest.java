package com.uxplima.uxmskyblock.bukkit.tradewinds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import net.kyori.adventure.text.Component;

import com.mojang.brigadier.CommandDispatcher;
import com.uxplima.uxmskyblock.bukkit.command.ModeAdminCommands;
import com.uxplima.uxmskyblock.bukkit.config.TradeWindsConfiguration;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.tradewinds.Port;
import com.uxplima.uxmskyblock.core.application.tradewinds.PortMarket;
import com.uxplima.uxmskyblock.core.application.tradewinds.Ranks;
import com.uxplima.uxmskyblock.core.application.tradewinds.VesselService;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.result.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

/**
 * {@code /isadmin tradewinds}: an operator gives a vessel a rank, sets its standing in a port and puts it in a port,
 * naming ports and ranks the file has, and only for an island that is a vessel.
 */
class AnOperatorStepsInOnAVesselTest extends MockBukkitHarness {

    private static final IslandId VESSEL = IslandId.of(UUID.randomUUID());
    private static final IslandId ORDINARY = IslandId.of(UUID.randomUUID());

    private final PortMarket market = mock(PortMarket.class);
    private CommandDispatcher<CommandSourceStack> dispatcher;
    private PlayerMock operator;

    @BeforeEach
    void setUp() throws Exception {
        TradeWindsConfiguration config =
                TradeWindsConfiguration.load(HoconConfigurationLoader.builder().buildAndLoadString("""
                        ranks = [
                            { id = "dinghy", volume = 0, hold-rows = 1 }
                            { id = "sloop", volume = 1000, hold-rows = 2 }
                        ]
                        ports = [
                            { id = "bay", voyage-seconds = 5, market = [ { item = "WHEAT", pays = 1, asks = 3 } ] }
                            { id = "cove", voyage-seconds = 5 }
                        ]
                        """));
        VesselService vessels = mock(VesselService.class);
        when(vessels.isVessel(VESSEL)).thenReturn(true);
        SchedulerPort scheduler = mock(SchedulerPort.class);
        doAnswer(call -> {
                    call.getArgument(0, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .async(any(Runnable.class));
        dispatcher = new CommandDispatcher<>();
        dispatcher.register(new ModeAdminCommands(
                        List.of(new TradeWindsAdmin(market, vessels, config)),
                        mock(IslandLocationService.class),
                        null,
                        scheduler,
                        Messages.bundled())
                .build(null, (source, tag) -> true)
                .orElseThrow());
        operator = server.addPlayer("Op");
        operator.setOp(true);
    }

    private String run(String line) throws Exception {
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn(operator);
        dispatcher.execute(line, source);
        StringBuilder said = new StringBuilder();
        Component next;
        while ((next = operator.nextComponentMessage()) != null) {
            said.append(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                            .serialize(next))
                    .append('\n');
        }
        return said.toString();
    }

    @Test
    @DisplayName("A rank the file has is given, and one it does not is refused with the ranks there are")
    void rank() throws Exception {
        when(market.setRank(VESSEL, "sloop")).thenReturn(Result.ok(new Ranks.Rank("sloop", 1000, 2)));
        when(market.setRank(VESSEL, "galleon")).thenReturn(Result.err("isadmin.tradewinds.unknown_rank"));

        assertThat(run("isadmin tradewinds setrank " + VESSEL.value() + " sloop"))
                .contains("a Sloop now")
                .contains("2 rows");
        assertThat(run("isadmin tradewinds setrank " + VESSEL.value() + " galleon"))
                .contains("galleon")
                .contains("dinghy, sloop");
    }

    @Test
    @DisplayName("A standing is set in a port the file has, and a port it does not have is refused before anything")
    void standing() throws Exception {
        assertThat(run("isadmin tradewinds setstanding " + VESSEL.value() + " cove 2500"))
                .contains("cove")
                .contains("2500");
        verify(market)
                .setStanding(
                        org.mockito.ArgumentMatchers.eq(VESSEL),
                        org.mockito.ArgumentMatchers.argThat(port -> port.id().equals("cove")),
                        org.mockito.ArgumentMatchers.eq(2500L));

        assertThat(run("isadmin tradewinds setstanding " + VESSEL.value() + " moon 5"))
                .contains("moon")
                .contains("bay, cove");
        verify(market).setStanding(any(IslandId.class), any(Port.class), anyLong());
    }

    @Test
    @DisplayName("A vessel is put in a port, or the operator is told another server holds it")
    void move() throws Exception {
        when(market.moveTo(any(IslandId.class), any(Port.class)))
                .thenReturn(Result.ok(Instant.EPOCH))
                .thenReturn(Result.err("tradewinds.hold.elsewhere"));

        assertThat(run("isadmin tradewinds moveport " + VESSEL.value() + " bay"))
                .contains("lies in bay");
        assertThat(run("isadmin tradewinds moveport " + VESSEL.value() + " bay"))
                .contains("Another server holds");
    }

    @Test
    @DisplayName("An island that is not a vessel is said to be one, and nothing is asked of the market")
    void notAVessel() throws Exception {
        assertThat(run("isadmin tradewinds setrank " + ORDINARY.value() + " sloop"))
                .contains("not a TradeWinds vessel");
        assertThat(run("isadmin tradewinds moveport " + ORDINARY.value() + " bay"))
                .contains("not a TradeWinds vessel");
        verify(market, never()).setRank(any(IslandId.class), anyString());
        verify(market, never()).moveTo(any(IslandId.class), any(Port.class));
    }
}
