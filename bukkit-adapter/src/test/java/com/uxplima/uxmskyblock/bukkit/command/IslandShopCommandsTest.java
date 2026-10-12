package com.uxplima.uxmskyblock.bukkit.command;

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
import java.util.Optional;
import java.util.UUID;

import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import com.mojang.brigadier.CommandDispatcher;
import com.uxplima.uxmskyblock.bukkit.config.LanguageConfiguration;
import com.uxplima.uxmskyblock.bukkit.i18n.MessageProvider;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.shop.IslandShopService;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import com.uxplima.uxmskyblock.core.domain.shop.PricingCurve;
import com.uxplima.uxmskyblock.core.domain.shop.ShopItemPrice;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * {@code /is shop}, end to end through Brigadier.
 *
 * <p>The pricing engine, its damping, its elasticity and its curve had been here since the economy
 * work and nothing ever put an item in it or asked it for a price.
 *
 * <p>The part worth pinning hardest is the selling. The items leave the player before the bank is
 * asked, because the inventory can only be touched on the thread that owns them and the bank cannot
 * be asked there. A sale the bank refuses has to hand them straight back.
 */
class IslandShopCommandsTest {

    private static final IslandId ISLAND = IslandId.of(UUID.randomUUID());
    private static final ProfileId PROFILE = new ProfileId(UUID.randomUUID());
    private static final ServerNodeId NODE = ServerNodeId.of("node-1");

    private ServerMock server;
    private PlayerMock player;
    private IslandShopService shop;
    private CommandDispatcher<CommandSourceStack> dispatcher;
    private IslandLocationService locations;

    private static SchedulerPort inlineScheduler() {
        SchedulerPort scheduler = mock(SchedulerPort.class);
        doAnswer(invocation -> {
                    invocation.getArgument(0, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .async(any(Runnable.class));
        doAnswer(invocation -> {
                    invocation.getArgument(1, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .onEntity(any(PlayerUuid.class), any(Runnable.class));
        doAnswer(invocation -> {
                    invocation.getArgument(1, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .onEntity(any(PlayerUuid.class), any(Runnable.class), any(Runnable.class));
        return scheduler;
    }

    private static ShopItemPrice priceOf(String key, long price) {
        return new ShopItemPrice(
                key, price, 0L, 0L, new PricingCurve(price, price / 2, price * 2, 0.5, 100L), Instant.now());
    }

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        player = server.addPlayer();

        shop = mock(IslandShopService.class);
        when(shop.catalogue()).thenReturn(List.of(priceOf("DIAMOND", 20_000L), priceOf("STONE", 150L)));

        locations = mock(IslandLocationService.class);
        when(locations.findIslandId(PROFILE)).thenReturn(Optional.of(ISLAND));

        PlayerSessionCoordinator sessions = mock(PlayerSessionCoordinator.class);
        when(sessions.activeProfile(player.getUniqueId())).thenReturn(Optional.of(PROFILE));

        IslandShopCommands commands = new IslandShopCommands(
                () -> shop,
                // No window on this node, so the bare verb prints the list, which is what these
                // tests drive. The window has its own.
                () -> null,
                locations,
                inlineScheduler(),
                NODE,
                Messages.of(new MessageProvider("en"), LanguageConfiguration.defaults()),
                sessions);

        dispatcher = new CommandDispatcher<>();
        dispatcher.register(commands.build());
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    /** Puts the caller on the island as a member holding exactly these permissions. */
    private void callerHolds(com.uxplima.uxmskyblock.core.domain.island.IslandPermission... permissions) {
        com.uxplima.uxmskyblock.core.domain.island.Island island =
                com.uxplima.uxmskyblock.core.domain.island.Island.create(
                        ISLAND,
                        com.uxplima.uxmskyblock.core.domain.island.IslandBounds.fromCenterAndRadius(0, 0, 64),
                        new PlayerUuid(java.util.UUID.randomUUID()),
                        new ProfileId(java.util.UUID.randomUUID()),
                        java.time.Instant.now());
        com.uxplima.uxmskyblock.core.domain.island.IslandRole role =
                new com.uxplima.uxmskyblock.core.domain.island.IslandRole(
                        "CUSTOM",
                        400,
                        "Custom",
                        permissions.length == 0
                                ? java.util.EnumSet.noneOf(
                                        com.uxplima.uxmskyblock.core.domain.island.IslandPermission.class)
                                : java.util.EnumSet.of(permissions[0], permissions),
                        false);
        when(locations.findIsland(ISLAND))
                .thenReturn(java.util.Optional.of(
                        island.addMember(new com.uxplima.uxmskyblock.core.domain.island.IslandMember(
                                new PlayerUuid(player.getUniqueId()), PROFILE, role, java.time.Instant.now()))));
    }

    @Test
    @DisplayName("A member whose role does not allow trading cannot buy from the island bank")
    void arolewithoutShopAccessCannotBuy() throws Exception {
        callerHolds(com.uxplima.uxmskyblock.core.domain.island.IslandPermission.BLOCK_BREAK);

        run("shop buy DIAMOND 1", player);

        verify(shop, never()).buy(any(), any(), anyString(), anyLong(), any());
        assertThat(player.nextMessage()).describedAs("and is told why").isNotNull();
    }

    @Test
    @DisplayName("A refused sale hands every item straight back")
    void arefusedSaleGivesTheItemsBack() throws Exception {
        callerHolds(com.uxplima.uxmskyblock.core.domain.island.IslandPermission.BLOCK_BREAK);
        player.getInventory().addItem(new org.bukkit.inventory.ItemStack(Material.DIAMOND, 4));

        run("shop sell DIAMOND 4", player);

        verify(shop, never()).sell(any(), any(), anyString(), anyLong(), any());
        assertThat(countOf(Material.DIAMOND))
                .describedAs("items taken for a sale that never happened are items nobody has")
                .isEqualTo(4);
    }

    @Test
    @DisplayName("A member whose role allows trading buys")
    void arolewithShopAccessBuys() throws Exception {
        callerHolds(com.uxplima.uxmskyblock.core.domain.island.IslandPermission.SHOP_ACCESS);
        when(shop.buy(any(), any(), anyString(), anyLong(), any()))
                .thenReturn(new IslandShopService.TradeResult.Traded("DIAMOND", 1L, 20_000L, 20_000L, 1_000L));

        run("shop buy DIAMOND 1", player);

        verify(shop).buy(any(), any(), anyString(), anyLong(), any());
    }

    private void run(String line, CommandSender sender) throws Exception {
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn(sender);
        dispatcher.execute(line, source);
    }

    private int countOf(Material material) {
        int held = 0;
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack != null && stack.getType() == material) {
                held += stack.getAmount();
            }
        }
        return held;
    }

    @Test
    @DisplayName("The bare verb lists every commodity with what it costs when there is no window")
    void theBareVerbListsTheCatalogue() throws Exception {
        run("shop", player);

        // A header and one line per commodity.
        for (int line = 0; line < 3; line++) {
            assertThat(player.nextMessage()).describedAs("line %d", line).isNotNull();
        }
        assertThat(player.nextMessage()).describedAs("nothing after the list").isNull();
    }

    @Test
    @DisplayName("Buying names the caller's island, the item and this node")
    void buyingReachesTheService() throws Exception {
        when(shop.buy(any(), any(), anyString(), anyLong(), any()))
                .thenReturn(new IslandShopService.TradeResult.Traded("DIAMOND", 4L, 20_000L, 80_000L, 1_000L));

        run("shop buy diamond 4", player);

        verify(shop).buy(ISLAND, new PlayerUuid(player.getUniqueId()), "DIAMOND", 4L, NODE);
        assertThat(countOf(Material.DIAMOND))
                .describedAs("what the player was handed")
                .isEqualTo(4);
    }

    @Test
    @DisplayName("Buying with no amount buys one")
    void buyingWithNoAmountBuysOne() throws Exception {
        when(shop.buy(any(), any(), anyString(), anyLong(), any()))
                .thenReturn(new IslandShopService.TradeResult.Traded("STONE", 1L, 150L, 150L, 1_000L));

        run("shop buy stone", player);

        verify(shop).buy(ISLAND, new PlayerUuid(player.getUniqueId()), "STONE", 1L, NODE);
    }

    @Test
    @DisplayName("A purchase the bank refused hands the player nothing")
    void arefusedPurchaseHandsOverNothing() throws Exception {
        when(shop.buy(any(), any(), anyString(), anyLong(), any()))
                .thenReturn(new IslandShopService.TradeResult.CannotAfford("DIAMOND", 80_000L, 10L));

        run("shop buy diamond 4", player);

        assertThat(countOf(Material.DIAMOND)).isZero();
        assertThat(player.nextMessage()).describedAs("the player is told why").isNotNull();
    }

    @Test
    @DisplayName("Selling takes the items and keeps them when the bank paid")
    void aPaidSaleKeepsTheItems() throws Exception {
        player.getInventory().addItem(new ItemStack(Material.DIAMOND, 10));
        when(shop.sell(any(), any(), anyString(), anyLong(), any()))
                .thenReturn(new IslandShopService.TradeResult.Traded("DIAMOND", 4L, 20_000L, 80_000L, 90_000L));

        run("shop sell diamond 4", player);

        verify(shop).sell(ISLAND, new PlayerUuid(player.getUniqueId()), "DIAMOND", 4L, NODE);
        assertThat(countOf(Material.DIAMOND))
                .describedAs("what the player kept")
                .isEqualTo(6);
    }

    @Test
    @DisplayName("A player whose items are kept aside on a creative plot sells nothing")
    void aSealedPlayerSellsNothing() throws Exception {
        // Sealed on the plot, then holding what was made there.
        new com.uxplima.uxmskyblock.bukkit.creative.SealedInventory(
                        new com.uxplima.uxmskyblock.bukkit.creative.ServerInventoryCodec(), "brix")
                .seal(player);
        player.getInventory().addItem(new ItemStack(Material.DIAMOND, 10));

        run("shop sell diamond 4", player);

        verify(shop, never()).sell(any(), any(), anyString(), anyLong(), any());
        assertThat(countOf(Material.DIAMOND)).isEqualTo(10);
        assertThat(player.nextMessage())
                .describedAs("this provider holds no text, so the key itself is what reaches the player")
                .isEqualTo("sealed.refused");
    }

    @Test
    @DisplayName("A sale the bank refused hands every item straight back")
    void aRefusedSaleGivesTheItemsBack() throws Exception {
        player.getInventory().addItem(new ItemStack(Material.DIAMOND, 10));
        when(shop.sell(any(), any(), anyString(), anyLong(), any()))
                .thenReturn(new IslandShopService.TradeResult.Refused("DIAMOND", "another node holds this island"));

        run("shop sell diamond 4", player);

        assertThat(countOf(Material.DIAMOND))
                .describedAs("items handed to a shop that did not pay are items nobody has")
                .isEqualTo(10);
    }

    @Test
    @DisplayName("Selling more than the player holds takes nothing and asks nothing of the bank")
    void sellingMoreThanHeldTakesNothing() throws Exception {
        player.getInventory().addItem(new ItemStack(Material.DIAMOND, 2));

        run("shop sell diamond 4", player);

        verify(shop, never()).sell(any(), any(), anyString(), anyLong(), any());
        assertThat(countOf(Material.DIAMOND)).isEqualTo(2);
    }

    @Test
    @DisplayName("A material no server knows is refused before the bank is asked")
    void anUnknownMaterialIsRefusedEarly() throws Exception {
        run("shop buy nonsense 4", player);
        run("shop sell nonsense 4", player);

        verify(shop, never()).buy(any(), any(), anyString(), anyLong(), any());
        verify(shop, never()).sell(any(), any(), anyString(), anyLong(), any());
    }

    @Test
    @DisplayName("The console is told to be a player rather than trading for nobody")
    void theConsoleIsRefused() throws Exception {
        run("shop", server.getConsoleSender());

        verify(shop, never()).buy(any(), any(), anyString(), anyLong(), any());
    }

    @Test
    @DisplayName("An enchanted sword is not the sword the shop priced")
    void anEnchantedSwordIsNotOnOffer() throws Exception {
        ItemStack sword = new ItemStack(Material.DIAMOND_SWORD, 1);
        sword.addUnsafeEnchantment(Enchantment.SHARPNESS, 3);
        player.getInventory().addItem(sword);

        run("shop sell diamond_sword 1", player);

        verify(shop, never()).sell(any(), any(), anyString(), anyLong(), any());
        assertThat(countOf(Material.DIAMOND_SWORD))
                .describedAs("the shop priced a sword, not this one")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("A buyer who left before the items reached them is paid back")
    void aBuyerWhoLeftIsPaidBack() throws Exception {
        SchedulerPort gone = mock(SchedulerPort.class);
        doAnswer(invocation -> {
                    invocation.getArgument(0, Runnable.class).run();
                    return null;
                })
                .when(gone)
                .async(any(Runnable.class));
        doAnswer(invocation -> {
                    invocation.getArgument(2, Runnable.class).run();
                    return null;
                })
                .when(gone)
                .onEntity(any(PlayerUuid.class), any(Runnable.class), any(Runnable.class));
        IslandShopService.TradeResult.Traded bought =
                new IslandShopService.TradeResult.Traded("DIAMOND", 2, 20_000L, 40_000L, 60_000L);
        when(shop.buy(any(), any(), anyString(), anyLong(), any())).thenReturn(bought);
        PlayerSessionCoordinator sessions = mock(PlayerSessionCoordinator.class);
        when(sessions.activeProfile(player.getUniqueId())).thenReturn(Optional.of(PROFILE));
        CommandDispatcher<CommandSourceStack> leaving = new CommandDispatcher<>();
        leaving.register(new IslandShopCommands(
                        () -> shop,
                        () -> null,
                        locations,
                        gone,
                        NODE,
                        Messages.of(new MessageProvider("en"), LanguageConfiguration.defaults()),
                        sessions)
                .build());
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn(player);

        leaving.execute("shop buy DIAMOND 2", source);

        verify(shop)
                .refundPurchase(
                        org.mockito.ArgumentMatchers.eq(ISLAND),
                        any(),
                        org.mockito.ArgumentMatchers.eq(bought),
                        org.mockito.ArgumentMatchers.eq(NODE));
        assertThat(countOf(Material.DIAMOND)).isZero();
    }

    @Test
    @DisplayName("A seller who left before a refused sale's items came back finds them in the reward inbox")
    void aSellerWhoLeftFindsTheItemsInTheInbox() throws Exception {
        SchedulerPort gone = mock(SchedulerPort.class);
        doAnswer(invocation -> {
                    invocation.getArgument(0, Runnable.class).run();
                    return null;
                })
                .when(gone)
                .async(any(Runnable.class));
        doAnswer(invocation -> {
                    invocation.getArgument(2, Runnable.class).run();
                    return null;
                })
                .when(gone)
                .onEntity(any(PlayerUuid.class), any(Runnable.class), any(Runnable.class));
        when(shop.sell(any(), any(), anyString(), anyLong(), any()))
                .thenReturn(new IslandShopService.TradeResult.Refused("STONE", "the bank said no", null));
        callerHolds(com.uxplima.uxmskyblock.core.domain.island.IslandPermission.SHOP_ACCESS);
        player.getInventory().addItem(new org.bukkit.inventory.ItemStack(Material.STONE, 64));
        player.getInventory().addItem(new org.bukkit.inventory.ItemStack(Material.STONE, 6));
        com.uxplima.uxmskyblock.core.application.reward.RewardInboxService inbox =
                mock(com.uxplima.uxmskyblock.core.application.reward.RewardInboxService.class);
        PlayerSessionCoordinator sessions = mock(PlayerSessionCoordinator.class);
        when(sessions.activeProfile(player.getUniqueId())).thenReturn(Optional.of(PROFILE));
        IslandShopCommands commands = new IslandShopCommands(
                () -> shop,
                () -> null,
                locations,
                gone,
                NODE,
                Messages.of(new MessageProvider("en"), LanguageConfiguration.defaults()),
                sessions);
        commands.keepUnreturnedIn(() -> inbox);
        CommandDispatcher<CommandSourceStack> leaving = new CommandDispatcher<>();
        leaving.register(commands.build());
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn(player);

        leaving.execute("shop sell STONE 70", source);

        org.mockito.ArgumentCaptor<List<com.uxplima.uxmskyblock.core.application.reward.RewardDraftComponent>> filed =
                org.mockito.ArgumentCaptor.captor();
        verify(inbox)
                .issueReward(
                        org.mockito.ArgumentMatchers.eq(PROFILE),
                        org.mockito.ArgumentMatchers.eq("SHOP_RETURN"),
                        org.mockito.ArgumentMatchers.eq("STONE"),
                        org.mockito.ArgumentMatchers.isNull(),
                        filed.capture());
        assertThat(filed.getValue())
                .extracting(com.uxplima.uxmskyblock.core.application.reward.RewardDraftComponent::payloadData)
                .containsExactly("{\"item\":\"STONE\",\"amount\":64}", "{\"item\":\"STONE\",\"amount\":6}");
    }
}
