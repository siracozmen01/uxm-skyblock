package com.uxplima.uxmskyblock.bukkit.tradewinds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntConsumer;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import com.uxplima.uxmlib.bedrock.BedrockButton;
import com.uxplima.uxmlib.bedrock.BedrockScreen;
import com.uxplima.uxmskyblock.bukkit.bedrock.BedrockFormService;
import com.uxplima.uxmskyblock.bukkit.config.TradeWindsConfiguration;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.session.ActiveSession;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.tradewinds.Port;
import com.uxplima.uxmskyblock.core.application.tradewinds.PortMarket;
import com.uxplima.uxmskyblock.core.application.tradewinds.VesselService;
import com.uxplima.uxmskyblock.core.application.tradewinds.VesselsPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.result.Result;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * The crew of a vessel chooses its next port with {@code /is sail} and trades at the port it lies in with
 * {@code /is market}, in a chest on Java and a native form on Bedrock, the same choices in the same order.
 */
class TheCrewSailsAndTradesTest extends MockBukkitHarness {

    private static final Instant NOW = Instant.parse("2026-10-08T12:00:00Z");
    private static final TradeWindsConfiguration CONFIG = TradeWindsConfiguration.defaultConfiguration();
    private static final Port BAY = CONFIG.ports().get(0);
    private static final Port SALTMARSH = CONFIG.ports().get(1);

    private final IslandId vessel = IslandId.of(UUID.randomUUID());
    private final PortMarket market = mock(PortMarket.class);
    private final Set<UUID> bedrock = new HashSet<>();
    private final List<List<String>> forms = new ArrayList<>();
    private final List<IntConsumer> answers = new ArrayList<>();
    private boolean aboard = true;

    @SuppressWarnings("NullAway.Init")
    private PlayerMock ada;

    @SuppressWarnings("NullAway.Init")
    private PlayerMock bo;

    @SuppressWarnings("NullAway.Init")
    private Harbour harbour;

    @BeforeEach
    void setUpTheVessel() {
        ada = createPlayer("Ada");
        bo = createPlayer("Bo");
        ActiveSession session =
                new ActiveSession(new PlayerUuid(ada.getUniqueId()), ProfileId.of(UUID.randomUUID()), 3L, 7L);
        ActiveSession boSession =
                new ActiveSession(new PlayerUuid(bo.getUniqueId()), ProfileId.of(UUID.randomUUID()), 3L, 7L);
        // Bo may trade at a market and look into the hold, and neither spend the bank nor take goods out.
        com.uxplima.uxmskyblock.core.domain.island.IslandRole trader =
                new com.uxplima.uxmskyblock.core.domain.island.IslandRole(
                        "TRADER",
                        10,
                        "Trader",
                        Set.of(
                                com.uxplima.uxmskyblock.core.domain.island.IslandPermission.SHOP_ACCESS,
                                com.uxplima.uxmskyblock.core.domain.island.IslandPermission.VAULT_VIEW),
                        false);
        Island island = Island.create(
                        vessel,
                        IslandBounds.fromCenterAndRadius(0, 0, 100),
                        PlayerUuid.of(ada.getUniqueId()),
                        session.activeProfileId(),
                        NOW)
                .addMember(new com.uxplima.uxmskyblock.core.domain.island.IslandMember(
                        PlayerUuid.of(bo.getUniqueId()), boSession.activeProfileId(), trader, NOW));
        VesselsPort holds = new VesselsPort() {
            @Override
            public Set<IslandId> findAll() {
                return Set.of(vessel);
            }

            @Override
            public boolean exists(IslandId islandId) {
                return vessel.equals(islandId);
            }

            @Override
            public void add(IslandId islandId) {}

            @Override
            public Optional<Cargo> cargo(IslandId islandId) {
                return Optional.of(new Cargo(
                        com.uxplima.uxmskyblock.bukkit.inventory.BukkitInventorySerializer.serializeItemStacks(
                                new ItemStack[] {new ItemStack(Material.WHEAT, 20)}),
                        1));
            }
        };
        VesselService vessels = new VesselService(holds);
        vessels.start(vessel);
        harbour = new Harbour(
                market,
                CONFIG,
                holds,
                new HoldGoods(),
                new Crew(
                        vessels,
                        location -> aboard ? Optional.of(island) : Optional.empty(),
                        new CargoHolds.Sessions() {
                            @Override
                            public @Nullable ActiveSession session(UUID player) {
                                return player.equals(ada.getUniqueId())
                                        ? session
                                        : player.equals(bo.getUniqueId()) ? boSession : null;
                            }

                            @Override
                            public void fence(UUID player, String why) {}
                        }),
                inline(),
                Messages.bundled(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        harbour.useForms(new BedrockFormService(bedrock::contains, new Screen(), Messages.bundled()));
        when(market.rank(any())).thenReturn(CONFIG.ranks().of(0));
        when(market.pays(any(), any(), any()))
                .thenAnswer(call -> call.getArgument(2, Port.Good.class).pays());
        when(market.asks(any(), any(), any()))
                .thenAnswer(call -> call.getArgument(2, Port.Good.class).asks());
    }

    @Test
    @DisplayName("The ports are listed in the order the file writes them, the one the vessel lies in marked")
    void thePortsAreListed() {
        when(market.where(vessel)).thenReturn(new PortMarket.Where.Docked(BAY.id()));

        harbour.sail(ada);

        Inventory top = top();
        assertThat(text(ada.getOpenInventory().title()).strip()).isEqualTo("Set sail");
        assertThat(type(top, 0)).isEqualTo(Material.EMERALD);
        assertThat(type(top, 1)).isEqualTo(Material.PRISMARINE_SHARD);
        assertThat(type(top, 2)).isEqualTo(Material.IRON_INGOT);
        assertThat(name(top, 0))
                .describedAs("a tile's name is blank, its title opens the lore")
                .isBlank();
        assertThat(lore(top, 0)).contains("◆ Emerald Bay").contains("Your vessel lies here.");
        assertThat(lore(top, 0))
                .describedAs("the port the vessel lies in is no voyage to set sail on")
                .doesNotContain("to set sail");
        assertThat(lore(top, 1)).contains("Click to set sail.");
    }

    @Test
    @DisplayName("Choosing a port sets the vessel sailing and says when it arrives")
    void choosingAPortSetsSail() {
        List<String> fired = listen();
        when(market.sail(vessel, SALTMARSH)).thenReturn(Result.ok(NOW.plusSeconds(90)));

        harbour.setSail(ada, vessel, SALTMARSH);

        verify(market).sail(vessel, SALTMARSH);
        assertThat(said()).contains("sails for Saltmarsh");
        assertThat(fired)
                .describedAs("the port sailed for and the voyage's length")
                .containsExactly("sailed " + SALTMARSH.id() + " 90");
    }

    /** Hands the harbour a {@code vessel-sailed} list that records what it says. */
    private List<String> listen() {
        List<String> fired = new ArrayList<>();
        server.getCommandMap().register("probe", new org.bukkit.command.Command("probe") {
            @Override
            public boolean execute(org.bukkit.command.CommandSender sender, String label, String[] args) {
                fired.add(String.join(" ", args));
                return true;
            }
        });
        harbour.useEffects(
                new com.uxplima.uxmskyblock.bukkit.effect.InteractionEffectPlayer(),
                new com.uxplima.uxmskyblock.bukkit.effect.InteractionEffects(Map.of(
                        "vessel-sailed",
                        com.uxplima.uxmlib.condition.action.ActionList.parse(
                                List.of("[console] probe sailed %port% %seconds%")))));
        return fired;
    }

    @Test
    @DisplayName("The market lists what the port trades with both prices and what the hold has")
    void theMarketIsListed() {
        when(market.where(vessel)).thenReturn(new PortMarket.Where.Docked(BAY.id()));

        harbour.market(ada);

        Inventory top = top();
        assertThat(text(ada.getOpenInventory().title()).strip()).isEqualTo("Market of Emerald Bay");
        assertThat(type(top, 0)).isEqualTo(Material.WHEAT);
        assertThat(lore(top, 0))
                .contains("Buy 16 40.00")
                .contains("Sell 16 24.00")
                .contains("In the hold 20")
                .contains("Left click to buy")
                .contains("Right click to sell");
    }

    @Test
    @DisplayName("A vessel at sea or in no port has no market, and nobody off the vessel opens one")
    void noMarketAtSea() {
        when(market.where(vessel)).thenReturn(new PortMarket.Where.Sailing(BAY.id(), NOW.plusSeconds(30)));
        harbour.market(ada);
        assertThat(said()).contains("still at sea");

        when(market.where(vessel)).thenReturn(new PortMarket.Where.Adrift());
        harbour.market(ada);
        assertThat(said()).contains("lies in no port");

        aboard = false;
        harbour.market(ada);
        harbour.sail(ada);
        assertThat(said()).contains("not aboard a TradeWinds vessel");
        verify(market, never()).sail(any(), any());
    }

    @Test
    @DisplayName("A trade is made through the market and told, and refused once the player left the vessel")
    void aTrade() {
        Port.Good wheat = BAY.goods().get(0);
        when(market.buy(any(), any(), any(), any())).thenReturn(Result.ok(new PortMarket.Deal("WHEAT", 16, 4_000)));
        when(market.sell(any(), any(), any(), any())).thenReturn(Result.err("tradewinds.market.too_few"));
        when(market.where(vessel)).thenReturn(new PortMarket.Where.Docked(BAY.id()));

        harbour.trade(ada, vessel, BAY, wheat, true);
        assertThat(said()).contains("Bought 16").contains("40.00");
        harbour.trade(ada, vessel, BAY, wheat, false);
        assertThat(said()).contains("too few");

        aboard = false;
        harbour.trade(ada, vessel, BAY, wheat, true);
        verify(market, org.mockito.Mockito.times(1)).buy(any(), any(), any(), any());
    }

    @Test
    @DisplayName("A trade that lifts the vessel's rank says so, with the rows its hold has now")
    void aRankUpIsTold() {
        when(market.rank(vessel))
                .thenReturn(CONFIG.ranks().of(0), CONFIG.ranks().of(500_000));
        when(market.sell(any(), any(), any(), any())).thenReturn(Result.ok(new PortMarket.Deal("WHEAT", 16, 2_400)));
        when(market.where(vessel)).thenReturn(new PortMarket.Where.Docked(BAY.id()));

        harbour.trade(ada, vessel, BAY, BAY.goods().get(0), false);

        assertThat(said()).contains("a Sloop now").contains("4 rows");
    }

    @Test
    @DisplayName("A member's role decides: this trader opens the market, and neither buys, sells nor steers")
    void theRoleDecides() {
        List<String> fired = listen();
        Port.Good wheat = BAY.goods().get(0);
        when(market.where(vessel)).thenReturn(new PortMarket.Where.Docked(BAY.id()));

        harbour.market(bo);
        assertThat(bo.getOpenInventory().getTopInventory().getItem(0)).isNotNull();

        harbour.trade(bo, vessel, BAY, wheat, true);
        harbour.trade(bo, vessel, BAY, wheat, false);
        harbour.setSail(bo, vessel, SALTMARSH);

        verify(market, never()).buy(any(), any(), any(), any());
        verify(market, never()).sell(any(), any(), any(), any());
        verify(market, never()).sail(any(), any());
        assertThat(fired).describedAs("nobody set sail").isEmpty();
        StringBuilder all = new StringBuilder();
        Component line;
        while ((line = bo.nextComponentMessage()) != null) {
            all.append(text(line)).append('\n');
        }
        assertThat(all.toString()).contains("cannot do that");
    }

    @Test
    @DisplayName("A Bedrock player gets the same ports and goods as a native form, and a tap does what a click does")
    void bedrockGetsForms() {
        bedrock.add(ada.getUniqueId());
        when(market.where(vessel)).thenReturn(new PortMarket.Where.Docked(BAY.id()));
        when(market.buy(any(), any(), any(), any())).thenReturn(Result.ok(new PortMarket.Deal("WHEAT", 16, 4_000)));

        harbour.sail(ada);
        assertThat(forms.get(0)).hasSize(3);
        assertThat(forms.get(0).get(1)).startsWith("Saltmarsh");

        harbour.market(ada);
        assertThat(forms.get(1)).hasSize(6);
        assertThat(forms.get(1).get(0)).startsWith("Buy 16");
        assertThat(forms.get(1).get(1)).startsWith("Sell 16");

        answers.get(1).accept(0);
        verify(market)
                .buy(vessel, PlayerUuid.of(ada.getUniqueId()), BAY, BAY.goods().get(0));
    }

    private String said() {
        StringBuilder all = new StringBuilder();
        Component line;
        while ((line = ada.nextComponentMessage()) != null) {
            all.append(text(line)).append('\n');
        }
        return all.toString();
    }

    @Test
    @DisplayName("The ports are handed to their menu file, the one the vessel lies in drawn without a click line")
    void thePortsAreHandedToTheirFile(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dataDir) {
        when(market.where(vessel)).thenReturn(new PortMarket.Where.Docked(BAY.id()));
        com.uxplima.uxmskyblock.bukkit.menu.SkyblockMenuEngine engine =
                org.mockito.Mockito.mock(com.uxplima.uxmskyblock.bukkit.menu.SkyblockMenuEngine.class);
        when(engine.open(eq(ada), eq("tradewinds-sail"), anyMap(), anyMap())).thenReturn(true);
        harbour.useMenuEngine(engine);

        harbour.sail(ada);

        verify(engine)
                .open(
                        eq(ada),
                        eq("tradewinds-sail"),
                        eq(Map.of("vessel", vessel.value().toString())),
                        eq(Map.of("tradewinds:ports", harbour.portRows(ada, new PortMarket.Where.Docked(BAY.id())))));
        var drawn = harbour.portRows(ada, new PortMarket.Where.Docked(BAY.id()));
        var renderer = com.uxplima.uxmskyblock.bukkit.menu.ShippedTemplates.renderer(
                new com.uxplima.uxmskyblock.bukkit.menu.SkyblockMenuEngine(
                        org.mockbukkit.mockbukkit.MockBukkit.createMockPlugin(), Messages.bundled(), dataDir),
                Messages.bundled());
        var template = com.uxplima.uxmskyblock.bukkit.menu.ShippedTemplates.template("tradewinds-sail.conf", "ports");
        String here = com.uxplima.uxmskyblock.bukkit.menu.ShippedTemplates.lore(
                renderer,
                template,
                com.uxplima.uxmlib.menu.runtime.MenuContext.of(ada, null, 0).withEntry(drawn.get(0)));
        String away = com.uxplima.uxmskyblock.bukkit.menu.ShippedTemplates.lore(
                renderer,
                template,
                com.uxplima.uxmlib.menu.runtime.MenuContext.of(ada, null, 0).withEntry(drawn.get(1)));

        assertThat(here)
                .contains("◆ Emerald Bay")
                .contains("Your vessel lies here.")
                .doesNotContain("set sail");
        assertThat(away).contains("Elsewhere").contains("Click to set sail.").doesNotContain("<entry_");
    }

    @Test
    @DisplayName("A port chosen in the menu file sets the vessel sailing for it")
    void aPortChosenInTheFileSetsSail(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dataDir) {
        when(market.sail(vessel, SALTMARSH)).thenReturn(Result.ok(NOW.plusSeconds(90)));
        com.uxplima.uxmskyblock.bukkit.menu.SkyblockMenuEngine engine =
                new com.uxplima.uxmskyblock.bukkit.menu.SkyblockMenuEngine(
                        org.mockbukkit.mockbukkit.MockBukkit.createMockPlugin(), Messages.bundled(), dataDir);
        harbour.useMenuEngine(engine);
        var row = harbour.portRows(ada, new PortMarket.Where.Docked(BAY.id())).get(1);

        com.uxplima.uxmskyblock.bukkit.menu.ShippedTemplates.click(
                engine,
                "tradewinds:set-sail",
                com.uxplima.uxmlib.menu.runtime.MenuContext.of(
                                ada, null, 0, Map.of("vessel", vessel.value().toString()))
                        .withEntry(row),
                ada,
                com.uxplima.uxmlib.menu.spec.ClickKind.LEFT,
                "");

        verify(market).sail(vessel, SALTMARSH);
    }

    @Test
    @DisplayName("The market is handed to its menu file, and a left click there buys a lot")
    void theMarketIsHandedToItsFile(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dataDir) {
        when(market.where(vessel)).thenReturn(new PortMarket.Where.Docked(BAY.id()));
        com.uxplima.uxmskyblock.bukkit.menu.SkyblockMenuEngine handed =
                org.mockito.Mockito.mock(com.uxplima.uxmskyblock.bukkit.menu.SkyblockMenuEngine.class);
        when(handed.open(eq(ada), eq("tradewinds-market"), anyMap(), anyMap())).thenReturn(true);
        harbour.useMenuEngine(handed);
        harbour.market(ada);
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Map<String, List<?>>> lists = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(handed).open(eq(ada), eq("tradewinds-market"), anyMap(), lists.capture());
        Object wheat = java.util.Objects.requireNonNull(lists.getValue().get("tradewinds:goods"))
                .get(0);

        com.uxplima.uxmskyblock.bukkit.menu.SkyblockMenuEngine engine =
                new com.uxplima.uxmskyblock.bukkit.menu.SkyblockMenuEngine(
                        org.mockbukkit.mockbukkit.MockBukkit.createMockPlugin(), Messages.bundled(), dataDir);
        harbour.useMenuEngine(engine);
        var drawn = com.uxplima.uxmlib.menu.runtime.MenuContext.of(
                        ada, null, 0, Map.of("vessel", vessel.value().toString(), "port", "Emerald Bay"))
                .withEntry(wheat);
        assertThat(com.uxplima.uxmskyblock.bukkit.menu.ShippedTemplates.lore(
                        com.uxplima.uxmskyblock.bukkit.menu.ShippedTemplates.renderer(engine, Messages.bundled()),
                        com.uxplima.uxmskyblock.bukkit.menu.ShippedTemplates.template(
                                "tradewinds-market.conf", "goods"),
                        drawn))
                .contains("Buy 16 40.00")
                .contains("In the hold 20")
                .doesNotContain("<entry_");
        when(market.buy(any(), any(), any(), any())).thenReturn(Result.err("tradewinds.market.no_funds"));

        com.uxplima.uxmskyblock.bukkit.menu.ShippedTemplates.click(
                engine, "tradewinds:buy", drawn, ada, com.uxplima.uxmlib.menu.spec.ClickKind.LEFT, "");

        verify(market).buy(eq(vessel), any(), eq(BAY), any());
    }

    private Inventory top() {
        return java.util.Objects.requireNonNull(ada.getOpenInventory().getTopInventory());
    }

    private static Material type(Inventory top, int slot) {
        return java.util.Objects.requireNonNull(top.getItem(slot)).getType();
    }

    private static String name(Inventory top, int slot) {
        return text(java.util.Objects.requireNonNull(java.util.Objects.requireNonNull(top.getItem(slot))
                .getItemMeta()
                .displayName()));
    }

    private static String lore(Inventory top, int slot) {
        List<Component> lines = java.util.Objects.requireNonNull(top.getItem(slot))
                .getItemMeta()
                .lore();
        return lines == null
                ? ""
                : String.join(
                        "\n",
                        lines.stream().map(TheCrewSailsAndTradesTest::text).toList());
    }

    private static String text(Component line) {
        return PlainTextComponentSerializer.plainText().serialize(line);
    }

    /** A Bedrock screen that keeps every form it was asked to draw, and the answer of each. */
    private final class Screen implements BedrockScreen {
        @Override
        public void sendSimpleForm(
                Player player,
                String title,
                @Nullable String content,
                List<BedrockButton> buttons,
                IntConsumer onSelect) {
            forms.add(buttons.stream().map(BedrockButton::text).toList());
            answers.add(onSelect);
        }

        @Override
        public void sendModalForm(
                Player player,
                String title,
                @Nullable String content,
                String button1,
                String button2,
                Runnable onButton1,
                Runnable onButton2) {}

        @Override
        public void sendInputForm(
                Player player,
                String title,
                String inputLabel,
                @Nullable String initial,
                java.util.function.Consumer<String> onSubmit,
                Runnable onClose) {}

        @Override
        public void sendCustomForm(
                Player player,
                String title,
                @Nullable String content,
                List<com.uxplima.uxmlib.bedrock.BedrockWidget> widgets,
                java.util.function.Consumer<java.util.Map<String, String>> onSubmit,
                Runnable onClose) {}
    }

    /** A scheduler that runs everything at once, on the calling thread. */
    private static SchedulerPort inline() {
        return new SchedulerPort() {
            @Override
            public void onGlobal(Runnable task) {
                task.run();
            }

            @Override
            public void onRegion(String worldName, int chunkX, int chunkZ, Runnable task) {
                task.run();
            }

            @Override
            public void onEntity(PlayerUuid playerUuid, Runnable task) {
                task.run();
            }

            @Override
            public boolean ownsEntity(PlayerUuid playerUuid) {
                return true;
            }

            @Override
            public void async(Runnable task) {
                task.run();
            }

            @Override
            public void asyncAfter(Duration delay, Runnable task) {
                task.run();
            }

            @Override
            public void laterGlobal(Duration delay, Runnable task) {
                task.run();
            }

            @Override
            public AutoCloseable repeatGlobal(Runnable task, Duration initialDelay, Duration period) {
                return () -> {};
            }

            @Override
            public AutoCloseable repeatAsync(Runnable task, Duration initialDelay, Duration period) {
                return () -> {};
            }
        };
    }
}
