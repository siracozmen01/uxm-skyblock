package com.uxplima.uxmskyblock.bukkit.tradewinds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.entity.Player;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import com.uxplima.uxmlib.menu.runtime.MenuContext;
import com.uxplima.uxmlib.menu.spec.ClickKind;
import com.uxplima.uxmskyblock.bukkit.config.TradeWindsConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.TradeWindsContracts;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffectPlayer;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffects;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.menu.MenuRow;
import com.uxplima.uxmskyblock.bukkit.menu.ShippedTemplates;
import com.uxplima.uxmskyblock.bukkit.menu.SkyblockMenuEngine;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.tradewinds.Contract;
import com.uxplima.uxmskyblock.core.application.tradewinds.ContractBoard;
import com.uxplima.uxmskyblock.core.application.tradewinds.ContractsPort;
import com.uxplima.uxmskyblock.core.application.tradewinds.ContractsPort.Held;
import com.uxplima.uxmskyblock.core.application.tradewinds.PortMarket;
import com.uxplima.uxmskyblock.core.application.tradewinds.VesselsPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.island.IslandMember;
import com.uxplima.uxmskyblock.core.domain.island.IslandPermission;
import com.uxplima.uxmskyblock.core.domain.island.IslandRole;
import com.uxplima.uxmskyblock.core.domain.result.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

/**
 * The ports of {@code modules/tradewinds.conf} offer contracts, each with its reward, and {@code /is contracts} shows
 * the crew what its port offers and what its vessel holds, takes one, delivers one, and tells the crew of one that
 * ran out of time.
 */
class APortOffersContractsTest extends MockBukkitHarness {

    private static final Instant NOW = Instant.parse("2026-10-13T12:00:00Z");

    @TempDir
    Path dataDir;

    private static TradeWindsConfiguration load(String contracts, String atOnce) throws Exception {
        return TradeWindsConfiguration.load(
                HoconConfigurationLoader.builder().buildAndLoadString("""
                %s
                ports = [
                    { id = "bay", market = [ { item = "WHEAT", lot = 16, pays = 100, asks = 200 } ],
                      contracts = [ %s ] }
                    { id = "cove", market = [ { item = "WHEAT", lot = 16, pays = 100, asks = 200 } ] }
                ]
                """.formatted(atOnce, contracts)));
    }

    @Test
    @DisplayName("Each port's contracts are read with their rewards, and one that cannot be is left out")
    void read() throws Exception {
        TradeWindsContracts read = load("""
                        { id = "grain", item = "wheat", count = 64, to = "cove", within = "30m",
                          preview = "Pays well", reward { deliverer = [ "[message] paid" ], members = [ "[message] crew" ] } }
                        { id = "nothing", item = "NOT_AN_ITEM", count = 1, to = "cove", within = "5m" }
                        { id = "air", item = "AIR", count = 1, to = "cove", within = "5m" }
                        { id = "nowhere", item = "WHEAT", count = 1, to = "atlantis", within = "5m" }
                        { id = "home", item = "WHEAT", count = 1, to = "bay", within = "5m" }
                        { id = "never", item = "WHEAT", count = 1, to = "cove", within = "soon" }
                        { id = "grain", item = "WHEAT", count = 2, to = "cove", within = "5m" }
                        { id = "plain", item = "WHEAT", count = 8, to = "cove", within = "2h" }
                        """, "contracts-at-once = 0").contracts();

        assertThat(read.offered())
                .containsExactly(
                        new Contract("grain", "bay", "WHEAT", 64, "cove", Duration.ofMinutes(30)),
                        new Contract("plain", "bay", "WHEAT", 8, "cove", Duration.ofHours(2)));
        assertThat(read.rewardOf("grain").deliverer().actions()).hasSize(1);
        assertThat(read.rewardOf("grain").members().actions()).hasSize(1);
        assertThat(read.rewardOf("plain")).isEqualTo(TradeWindsContracts.Reward.NONE);
        assertThat(read.previewOf("grain")).contains("Pays well");
        assertThat(read.previewOf("plain")).isEmpty();
        assertThat(read.atOnce())
                .describedAs("a count that cannot be is the shipped one")
                .isEqualTo(TradeWindsContracts.SHIPPED_AT_ONCE);
        assertThat(load("", "contracts-at-once = 5").contracts().atOnce()).isEqualTo(5);
        assertThat(load("", "contracts-at-once = 55").contracts().atOnce())
                .isEqualTo(TradeWindsContracts.SHIPPED_AT_ONCE);
        assertThat(TradeWindsConfiguration.load(
                                HoconConfigurationLoader.builder().buildAndLoadString("enabled = true"))
                        .contracts())
                .describedAs("a file that writes no ports offers the shipped contracts")
                .isEqualTo(TradeWindsConfiguration.SHIPPED_CONTRACTS);
    }

    @Test
    @DisplayName("The shipped file offers a contract at two ports, each with its reward and its preview")
    void shipped() throws Exception {
        try (var in = APortOffersContractsTest.class.getResourceAsStream("/modules/tradewinds.conf")) {
            String text = new String(java.util.Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
            TradeWindsContracts shipped = TradeWindsConfiguration.load(
                            HoconConfigurationLoader.builder().buildAndLoadString(text))
                    .contracts();
            assertThat(shipped.offered()).isEqualTo(TradeWindsConfiguration.SHIPPED_CONTRACTS.offered());
            assertThat(shipped.atOnce()).isEqualTo(TradeWindsContracts.SHIPPED_AT_ONCE);
            for (Contract contract : shipped.offered()) {
                assertThat(shipped.rewardOf(contract.id()).deliverer().actions())
                        .isNotEmpty();
                assertThat(shipped.previewOf(contract.id())).isPresent();
                assertThat(Messages.bundled().has("tradewinds.contracts.names." + contract.id()))
                        .isTrue();
            }
        }
    }

    @Test
    @DisplayName("The window shows what the vessel holds, due here or elsewhere, then what its port offers")
    void rows() throws Exception {
        PlayerMock ada = createPlayer("Ada");
        IslandId vessel = IslandId.of(UUID.randomUUID());
        Fixture fixture = new Fixture(vessel, List.of());
        Held dueHere = new Held(
                vessel,
                "grain",
                "bay",
                "WHEAT",
                64,
                PlayerUuid.of(ada.getUniqueId()),
                NOW.plusSeconds(600),
                ContractsPort.State.TAKEN);
        Held elsewhere = new Held(
                vessel, "salt", "cove", "SUGAR", 8, PlayerUuid.of(ada.getUniqueId()), NOW, ContractsPort.State.TAKEN);
        Contract offered = new Contract("grain", "cove", "WHEAT", 64, "bay", Duration.ofMinutes(30));
        Contract more = new Contract("plain", "cove", "PUMPKIN", 8, "bay", Duration.ofHours(2));

        List<MenuRow> rows = fixture.desk.rows(
                ada, new ContractDesk.View("bay", List.of(offered, more), List.of(dueHere, elsewhere), new byte[0]));

        assertThat(rows)
                .hasSize(3)
                .extracting(MenuRow::handle)
                .describedAs("one already held is not offered again")
                .containsExactly(
                        new ContractDesk.Pick("grain", true),
                        new ContractDesk.Pick("salt", true),
                        new ContractDesk.Pick("plain", false));
        assertThat(rows.get(0).words())
                .containsEntry("colour", "good")
                .containsEntry("left", "10m")
                .containsEntry("held", "0")
                .containsEntry("material", "WHEAT")
                .describedAs("the tags a preview writes are left out of a tile's plain words")
                .containsEntry("reward", "Pays well");
        assertThat(rows.get(0).words().get("facts")).endsWith("action:@tradewinds.contracts.contract.deliver");
        assertThat(rows.get(1).words()).containsEntry("colour", "muted").containsEntry("left", "none");
        assertThat(rows.get(1).words().get("facts")).contains("-action").contains("-description");
        assertThat(rows.get(2).words()).containsEntry("colour", "accent").containsEntry("within", "2h");
        assertThat(rows.get(2).words().get("facts")).endsWith("action:@tradewinds.contracts.contract.take");

        SkyblockMenuEngine engine = ShippedTemplates.engineWith(dataDir, "tradewinds-contracts.conf");
        fixture.desk.useMenuEngine(engine);
        String tile = ShippedTemplates.lore(
                ShippedTemplates.renderer(engine, Messages.bundled()),
                ShippedTemplates.template("tradewinds-contracts.conf", "contracts"),
                MenuContext.of(ada, null, 0).withEntry(rows.get(0)));
        assertThat(tile)
                .contains("Deliver", "64", "Due in", "10m", "Due here", "Click to deliver it.")
                .doesNotContain("<entry_", "%entry_");
        assertThat(ShippedTemplates.verbs(ShippedTemplates.template("tradewinds-contracts.conf", "contracts"))
                        .get(ClickKind.ANY))
                .contains("tradewinds:contract");
    }

    @Test
    @DisplayName("A click delivers a contract due here and runs its reward for the deliverer and the crew online")
    void delivered() throws Exception {
        PlayerMock ada = createPlayer("Ada");
        PlayerMock bo = createPlayer("Bo");
        IslandId vessel = IslandId.of(UUID.randomUUID());
        Fixture fixture = new Fixture(vessel, List.of(bo));
        Held held = new Held(
                vessel,
                "grain",
                "bay",
                "WHEAT",
                64,
                PlayerUuid.of(ada.getUniqueId()),
                NOW.plusSeconds(600),
                ContractsPort.State.DELIVERED);
        doReturn(Result.ok(vessel)).when(fixture.crew).aboard(eq(ada), eq(vessel), any(IslandPermission.class));
        when(fixture.board.deliver(vessel, "grain")).thenReturn(Result.ok(held));
        SkyblockMenuEngine engine = ShippedTemplates.engineWith(dataDir, "tradewinds-contracts.conf");
        fixture.desk.useMenuEngine(engine);

        ShippedTemplates.click(
                engine,
                "tradewinds:contract",
                MenuContext.of(ada, null, 0, Map.of("vessel", vessel.value().toString()))
                        .withEntry(new MenuRow(Map.of(), new ContractDesk.Pick("grain", true))),
                ada,
                ClickKind.LEFT,
                "");

        assertThat(said(ada))
                .anyMatch(line -> line.contains("delivered grain: 64 Wheat to bay"))
                .anyMatch(line -> line.equals("paid 64 WHEAT at bay"));
        assertThat(said(bo)).containsExactly("crew grain");
        assertThat(said(ada)).doesNotContain("crew grain");
    }

    @Test
    @DisplayName("A click takes a contract offered here, and one refused says why")
    void taken() throws Exception {
        PlayerMock ada = createPlayer("Ada");
        IslandId vessel = IslandId.of(UUID.randomUUID());
        Fixture fixture = new Fixture(vessel, List.of());
        doReturn(Result.ok(vessel)).when(fixture.crew).aboard(eq(ada), eq(vessel), any(IslandPermission.class));
        when(fixture.board.contract("grain"))
                .thenReturn(Optional.of(new Contract("grain", "bay", "WHEAT", 64, "cove", Duration.ofMinutes(30))));
        when(fixture.board.take(vessel, PlayerUuid.of(ada.getUniqueId()), "grain"))
                .thenReturn(Result.err("tradewinds.contracts.too_many"))
                .thenReturn(Result.ok(new Held(
                        vessel,
                        "grain",
                        "cove",
                        "WHEAT",
                        64,
                        PlayerUuid.of(ada.getUniqueId()),
                        NOW.plusSeconds(1800),
                        ContractsPort.State.TAKEN)));

        fixture.desk.take(ada, vessel, "grain");
        fixture.desk.take(ada, vessel, "grain");

        assertThat(said(ada))
                .anyMatch(line -> line.contains("holds 3 contracts already"))
                .anyMatch(line -> line.contains("took grain: 64 Wheat to cove within 30m"));
    }

    @Test
    @DisplayName("A contract that ran out of time is told to its crew online, and to nobody else")
    void failed() {
        PlayerMock ada = createPlayer("Ada");
        PlayerMock cy = createPlayer("Cy");
        IslandId vessel = IslandId.of(UUID.randomUUID());
        Fixture fixture = new Fixture(vessel, List.of(ada));
        when(fixture.board.failOverdue())
                .thenReturn(List.of(new Held(
                        vessel,
                        "grain",
                        "cove",
                        "WHEAT",
                        64,
                        PlayerUuid.of(ada.getUniqueId()),
                        NOW,
                        ContractsPort.State.TAKEN)));

        fixture.desk.failOverdue();

        assertThat(said(ada)).anyMatch(line -> line.contains("grain was not delivered to cove in time"));
        assertThat(said(cy)).isEmpty();
    }

    private static List<String> said(PlayerMock player) {
        List<String> lines = new ArrayList<>();
        net.kyori.adventure.text.Component line;
        while ((line = player.nextComponentMessage()) != null) {
            lines.add(PlainTextComponentSerializer.plainText().serialize(line));
        }
        return lines;
    }

    /** A desk over a mocked board and crew, its work run at once, its vessel's island crewed by {@code members}. */
    private final class Fixture {
        final ContractBoard board = mock(ContractBoard.class);
        final Crew crew = mock(Crew.class);
        final ContractDesk desk;

        Fixture(IslandId vessel, List<PlayerMock> members) {
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
            // The window opens again after a click; the crew of no vessel is told so.
            doReturn(Result.err("tradewinds.hold.not_vessel")).when(crew).aboard(any(Player.class));
            UUID owner = UUID.randomUUID();
            Island island = Island.create(
                    vessel,
                    IslandBounds.fromCenterAndRadius(0, 0, 50),
                    new PlayerUuid(owner),
                    new ProfileId(owner),
                    NOW);
            for (PlayerMock member : members) {
                island = island.addMember(new IslandMember(
                        new PlayerUuid(member.getUniqueId()),
                        new ProfileId(member.getUniqueId()),
                        IslandRole.MEMBER,
                        NOW));
            }
            Island crewed = island;
            TradeWindsConfiguration config = TradeWindsConfiguration.defaultConfiguration();
            TradeWindsConfiguration withRewards = new TradeWindsConfiguration(
                    config.enabled(),
                    config.sea(),
                    config.ranks(),
                    config.ports(),
                    config.icons(),
                    config.standing(),
                    config.drift(),
                    new TradeWindsContracts(
                            config.contracts().offered(),
                            Map.of(
                                    "grain",
                                    new TradeWindsContracts.Reward(
                                            List.of("[message] paid %count% %item% at %port%"),
                                            List.of("[message] crew %contract%"))),
                            Map.of("grain", "<green>Pays well"),
                            3),
                    config.voyageEvents());
            desk = new ContractDesk(
                    board,
                    mock(PortMarket.class),
                    withRewards,
                    mock(VesselsPort.class),
                    new HoldGoods(),
                    crew,
                    scheduler,
                    Messages.bundled(),
                    Clock.fixed(NOW, ZoneOffset.UTC),
                    server::getPlayer,
                    id -> id.equals(vessel) ? Optional.of(crewed) : Optional.empty());
            desk.useEffects(new InteractionEffectPlayer(null, Messages.bundled()), new InteractionEffects(Map.of()));
        }
    }
}
