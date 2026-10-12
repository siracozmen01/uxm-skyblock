package com.uxplima.uxmskyblock.bukkit.menu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;

import com.uxplima.uxmlib.gui.Guis;
import com.uxplima.uxmlib.gui.SimpleGui;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.bukkit.test.InlineSchedulerPort;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
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
 * The shop window, opened and clicked.
 *
 * <p>The window is built from what was read before it reached the thread that owns the player, so
 * nothing it draws is a query. A click hands the trade back off that thread, and only the items and
 * the answer come back.
 */
class IslandShopMenuTest {

    private static final IslandId ISLAND = IslandId.of(UUID.randomUUID());
    private static final ProfileId PROFILE = new ProfileId(UUID.randomUUID());
    private static final ServerNodeId NODE = ServerNodeId.of("node-1");

    private ServerMock server;
    private PlayerMock player;
    private IslandShopService shop;
    private IslandShopMenu menu;

    private static ShopItemPrice priceOf(String key, long price) {
        return new ShopItemPrice(
                key, price, 0L, 0L, new PricingCurve(price, price / 2, price * 2, 0.5, 100L), Instant.now());
    }

    private static List<ShopItemPrice> catalogue() {
        return List.of(priceOf("DIAMOND", 20_000L), priceOf("STONE", 150L), priceOf("WHEAT", 120L));
    }

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        Guis.install(MockBukkit.createMockPlugin());
        player = server.addPlayer();

        shop = mock(IslandShopService.class);
        when(shop.catalogue()).thenReturn(catalogue());

        IslandStoragePort storagePort = mock(IslandStoragePort.class);
        when(storagePort.findIslandIdByProfileId(PROFILE)).thenReturn(Optional.of(ISLAND));

        PlayerSessionCoordinator sessions = mock(PlayerSessionCoordinator.class);
        when(sessions.activeProfile(player.getUniqueId())).thenReturn(Optional.of(PROFILE));

        menu = new IslandShopMenu(shop, storagePort, sessions, new InlineSchedulerPort(), NODE, Messages.bundled());
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
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
    @DisplayName("The window draws a tile for every commodity and a way out")
    void everyCommodityGetsATile() {
        java.util.concurrent.atomic.AtomicInteger wentBack = new java.util.concurrent.atomic.AtomicInteger();
        menu.useWayBack(viewer -> wentBack.incrementAndGet());
        SimpleGui gui = menu.buildGui(player, ISLAND, catalogue());

        assertThat(gui.getItem(0)).describedAs("the first commodity").isNotNull();
        assertThat(gui.getItem(1)).isNotNull();
        assertThat(gui.getItem(2)).isNotNull();
        com.uxplima.uxmlib.gui.item.GuiItem back =
                java.util.Objects.requireNonNull(gui.getItem(gui.size() - 5), "the way out");
        assertThat(((com.uxplima.uxmlib.gui.item.GuiItem.Static) back).item().getType())
                .describedAs("the way out is the back button, not a close button")
                .isEqualTo(Material.FEATHER);
        back.action(new com.uxplima.uxmlib.gui.item.RenderContext(player, gui, gui.size() - 5))
                .accept(org.mockito.Mockito.mock(org.bukkit.event.inventory.InventoryClickEvent.class));
        assertThat(wentBack).hasValue(1);
    }

    @Test
    @DisplayName("A window opened for a player with an island is a window")
    void openingGivesAWindow() {
        menu.open(player);

        assertThat(player.getOpenInventory()).isNotNull();
        assertThat(player.getOpenInventory().getTopInventory().getSize()).isEqualTo(18);
    }

    @Test
    @DisplayName("Buying hands the player what the shop said it sold them")
    void buyingHandsOverTheItems() {
        when(shop.buy(any(), any(), anyString(), anyLong(), any()))
                .thenReturn(new IslandShopService.TradeResult.Traded("DIAMOND", 1L, 20_000L, 20_000L, 1_000L));

        menu.trade(player, ISLAND, Material.DIAMOND, 1, true);

        verify(shop).buy(ISLAND, new PlayerUuid(player.getUniqueId()), "DIAMOND", 1L, NODE);
        assertThat(countOf(Material.DIAMOND)).isEqualTo(1);
    }

    @Test
    @DisplayName("A purchase the bank refused hands the player nothing")
    void arefusedPurchaseHandsOverNothing() {
        when(shop.buy(any(), any(), anyString(), anyLong(), any()))
                .thenReturn(new IslandShopService.TradeResult.CannotAfford("DIAMOND", 20_000L, 5L));

        menu.trade(player, ISLAND, Material.DIAMOND, 1, true);

        assertThat(countOf(Material.DIAMOND)).isZero();
    }

    @Test
    @DisplayName("A player whose items are kept aside on a creative plot sells nothing from the window")
    void aSealedPlayerSellsNothing() {
        // Sealed on the plot, then holding what was made there.
        new com.uxplima.uxmskyblock.bukkit.creative.SealedInventory(
                        new com.uxplima.uxmskyblock.bukkit.creative.ServerInventoryCodec(), "brix")
                .seal(player);
        player.getInventory().addItem(new ItemStack(Material.DIAMOND, 10));

        menu.trade(player, ISLAND, Material.DIAMOND, 4, false);

        verify(shop, never()).sell(any(), any(), anyString(), anyLong(), any());
        assertThat(countOf(Material.DIAMOND)).isEqualTo(10);
        assertThat(player.nextMessage()).contains("creative place");
    }

    @Test
    @DisplayName("Selling takes the items and keeps them when the bank paid")
    void aPaidSaleKeepsTheItems() {
        player.getInventory().addItem(new ItemStack(Material.DIAMOND, 10));
        when(shop.sell(any(), any(), anyString(), anyLong(), any()))
                .thenReturn(new IslandShopService.TradeResult.Traded("DIAMOND", 4L, 20_000L, 80_000L, 90_000L));

        menu.trade(player, ISLAND, Material.DIAMOND, 4, false);

        assertThat(countOf(Material.DIAMOND)).isEqualTo(6);
    }

    @Test
    @DisplayName("A sale the bank refused hands every item straight back")
    void aRefusedSaleGivesTheItemsBack() {
        player.getInventory().addItem(new ItemStack(Material.DIAMOND, 10));
        when(shop.sell(any(), any(), anyString(), anyLong(), any()))
                .thenReturn(new IslandShopService.TradeResult.Refused("DIAMOND", "another node holds this island"));

        menu.trade(player, ISLAND, Material.DIAMOND, 4, false);

        assertThat(countOf(Material.DIAMOND))
                .describedAs("items handed to a shop that did not pay are items nobody has")
                .isEqualTo(10);
    }

    @Test
    @DisplayName("Clicking sell without the items takes nothing and asks the bank nothing")
    void sellingMoreThanHeldAsksNothing() {
        player.getInventory().addItem(new ItemStack(Material.DIAMOND, 2));

        menu.trade(player, ISLAND, Material.DIAMOND, 4, false);

        verify(shop, never()).sell(any(), any(), anyString(), anyLong(), any());
        assertThat(countOf(Material.DIAMOND)).isEqualTo(2);
    }

    @Test
    @DisplayName("A stack that does not fit is dropped rather than lost")
    void whatDoesNotFitIsDropped() {
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            player.getInventory().setItem(slot, new ItemStack(Material.STONE, 64));
        }
        when(shop.buy(any(), any(), anyString(), anyLong(), any()))
                .thenReturn(new IslandShopService.TradeResult.Traded("DIAMOND", 64L, 20_000L, 1_280_000L, 1L));

        menu.trade(player, ISLAND, Material.DIAMOND, 64, true);

        assertThat(countOf(Material.DIAMOND))
                .describedAs("nothing fitted, so nothing is in the inventory")
                .isZero();
        assertThat(player.getWorld().getEntities())
                .describedAs("what was paid for is on the ground rather than nowhere")
                .isNotEmpty();
    }

    @Test
    @DisplayName("An enchanted sword is not the sword the shop priced")
    void anEnchantedSwordIsNotOnOffer() {
        ItemStack sword = new ItemStack(Material.DIAMOND_SWORD, 1);
        sword.addUnsafeEnchantment(Enchantment.SHARPNESS, 3);
        player.getInventory().addItem(sword);

        menu.trade(player, ISLAND, Material.DIAMOND_SWORD, 1, false);

        verify(shop, never()).sell(any(), any(), anyString(), anyLong(), any());
        assertThat(countOf(Material.DIAMOND_SWORD))
                .describedAs("the shop priced a sword, not this one")
                .isEqualTo(1);
    }
}
