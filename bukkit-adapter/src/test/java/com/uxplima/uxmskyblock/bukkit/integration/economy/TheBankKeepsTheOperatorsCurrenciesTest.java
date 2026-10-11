package com.uxplima.uxmskyblock.bukkit.integration.economy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import net.kyori.adventure.text.Component;

import com.uxplima.uxmlib.hook.economy.EconomyBridge;
import com.uxplima.uxmskyblock.bukkit.config.BankCurrencySpec;
import com.uxplima.uxmskyblock.core.application.bank.IslandBankService;
import com.uxplima.uxmskyblock.core.application.economy.EconomySagaCoordinator;
import com.uxplima.uxmskyblock.core.application.economy.ExternalWalletPort;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.bank.BankTransactionOutcome;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * Every currency the operator lists reaches the bank through the wallet its type names, and one that lives in the
 * player is read and moved on the thread that owns them.
 */
class TheBankKeepsTheOperatorsCurrenciesTest {

    private ServerMock server;
    private PlayerMock player;
    private ExternalWalletPort money;
    private SchedulerPort scheduler;
    private final AtomicReference<Boolean> hopped = new AtomicReference<>(false);
    private boolean playerRetired;

    private static BankCurrencySpec spec(
            String id, BankCurrencySpec.Type type, boolean on, Map<String, String> options) {
        return new BankCurrencySpec(id, type, 10, on, id, "CHEST", options);
    }

    private static BankCurrencySpec diamonds() {
        return spec("diamonds", BankCurrencySpec.Type.ITEM, true, Map.of("material", "minecraft:diamond"));
    }

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        player = server.addPlayer();
        money = mock(ExternalWalletPort.class);
        scheduler = mock(SchedulerPort.class);
        doAnswer(invocation -> {
                    hopped.set(true);
                    if (playerRetired) {
                        invocation.getArgument(2, Runnable.class).run();
                    } else {
                        invocation.getArgument(1, Runnable.class).run();
                    }
                    return null;
                })
                .when(scheduler)
                .onEntity(any(PlayerUuid.class), any(Runnable.class), any(Runnable.class));
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private BankWallets wallets(BankCurrencySpec... specs) {
        return BankWallets.ofServer(money, scheduler, List.of(specs), "uxmSkyblock");
    }

    private int diamondsCarried() {
        return java.util.Arrays.stream(player.getInventory().getStorageContents())
                .filter(stack -> stack != null && stack.getType() == Material.DIAMOND)
                .mapToInt(ItemStack::getAmount)
                .sum();
    }

    @Test
    @DisplayName("The island's money goes through the server's economy, a listed currency through its own wallet")
    void eachCurrencyHasItsWallet() {
        BankWallets wallets = wallets(diamonds());

        assertThat(wallets.walletFor("VAULT")).containsSame(money);
        assertThat(wallets.walletFor("PRIMARY")).containsSame(money);
        assertThat(wallets.walletFor("diamonds")).isPresent().get().isNotSameAs(money);
        assertThat(wallets.walletFor("Diamonds")).isPresent();
        assertThat(wallets.walletFor("gems")).isEmpty();
    }

    @Test
    @DisplayName("A currency turned off, or described wrongly, stays out of the bank and the rest stay in")
    void aWrongCurrencyClosesNothing() {
        BankWallets wallets = wallets(
                diamonds(),
                spec("emeralds", BankCurrencySpec.Type.ITEM, false, Map.of("material", "minecraft:emerald")),
                spec("nothing", BankCurrencySpec.Type.ITEM, true, Map.of()),
                spec("unknown", BankCurrencySpec.Type.ITEM, true, Map.of("material", "minecraft:no_such_thing")),
                spec("gems", BankCurrencySpec.Type.ECOBITS, true, Map.of()),
                spec("xp", BankCurrencySpec.Type.EXPERIENCE, true, Map.of("unit", "levels")));

        assertThat(wallets.currencies()).extracting(kept -> kept.spec().id()).containsExactly("diamonds", "xp");
    }

    @Test
    @DisplayName("A currency whose plugin is not here stays out of the bank, and comes back once the plugin is")
    void aCurrencyNobodyAnswersStaysOut() {
        com.uxplima.uxmlib.condition.wallet.BridgedWallet points =
                mock(com.uxplima.uxmlib.condition.wallet.BridgedWallet.class);
        when(points.reaches("")).thenReturn(false);
        BankWallets wallets = new BankWallets(
                money,
                scheduler,
                Map.of(
                        "points",
                        new BankWallets.Kept(
                                spec("points", BankCurrencySpec.Type.PLAYERPOINTS, true, Map.of()), points, ""),
                        "tokens",
                        new BankWallets.Kept(
                                spec("tokens", BankCurrencySpec.Type.PLACEHOLDER, true, Map.of()),
                                mock(com.uxplima.uxmlib.condition.Wallet.class),
                                ""),
                        "xp",
                        new BankWallets.Kept(
                                spec("xp", BankCurrencySpec.Type.EXPERIENCE, true, Map.of()),
                                mock(com.uxplima.uxmlib.condition.Wallet.class),
                                "")));

        assertThat(wallets.currencies())
                .describedAs("PlayerPoints and PlaceholderAPI are not on this server")
                .extracting(kept -> kept.spec().id())
                .containsExactly("xp");
        assertThat(wallets.currency("points")).isEmpty();
        assertThat(wallets.walletFor("points"))
                .describedAs("a move a crash left half done still finds its wallet")
                .isPresent();

        when(points.reaches("")).thenReturn(true);

        assertThat(wallets.currencies()).extracting(kept -> kept.spec().id()).containsExactly("points", "xp");
        assertThat(wallets.currency("points")).isPresent();
    }

    @Test
    @DisplayName("A description that names no material, or no placeholder, is refused with the reason")
    void aDescriptionSaysWhatItLacks() {
        System.Logger log = System.getLogger("test");

        assertThatThrownBy(() -> BankWallets.built(
                        spec("nothing", BankCurrencySpec.Type.ITEM, true, Map.of()), log, "uxmSkyblock", scheduler))
                .hasMessageContaining("material");
        assertThatThrownBy(() -> BankWallets.built(
                        spec("tokens", BankCurrencySpec.Type.PLACEHOLDER, true, Map.of("take", "x")),
                        log,
                        "uxmSkyblock",
                        scheduler))
                .hasMessageContaining("placeholder");
        assertThatThrownBy(() -> BankWallets.built(
                        spec("coins", BankCurrencySpec.Type.BRIDGE, true, Map.of("plugin", "Coins")),
                        log,
                        "uxmSkyblock",
                        scheduler))
                .hasMessageContaining("class");
    }

    @Test
    @DisplayName("Diamonds are taken from the player on the thread that owns them, and paid back the same way")
    void anItemMovesOnThePlayersThread() {
        player.getInventory().addItem(new ItemStack(Material.DIAMOND, 20));
        ExternalWalletPort wallet = wallets(diamonds()).walletFor("diamonds").orElseThrow();
        PlayerUuid uuid = new PlayerUuid(player.getUniqueId());

        assertThat(wallet.hasFunds(uuid, 20)).isTrue();
        assertThat(wallet.hasFunds(uuid, 21)).isFalse();
        assertThat(wallet.withdraw(uuid, 15)).isTrue();
        assertThat(diamondsCarried()).isEqualTo(5);
        assertThat(wallet.withdraw(uuid, 6))
                .describedAs("a part payment is no payment")
                .isFalse();
        assertThat(diamondsCarried()).isEqualTo(5);
        assertThat(wallet.deposit(uuid, 100)).isTrue();
        assertThat(diamondsCarried()).isEqualTo(105);
        assertThat(hopped.get())
                .describedAs("every read and move hopped to the player's thread")
                .isTrue();
    }

    @Test
    @DisplayName("A player who left before their thread answered holds nothing and pays nothing")
    void aPlayerWhoLeftHoldsNothing() {
        player.getInventory().addItem(new ItemStack(Material.DIAMOND, 20));
        BankWallets wallets = wallets(diamonds());
        playerRetired = true;

        assertThat(wallets.carried(player.getUniqueId(), "diamonds")).isEmpty();
        assertThat(wallets.walletFor("diamonds").orElseThrow().withdraw(new PlayerUuid(player.getUniqueId()), 1))
                .isFalse();
        assertThat(diamondsCarried()).isEqualTo(20);
    }

    @Test
    @DisplayName("What a player carries is read where it lives, and nothing is read of a currency the bank lacks")
    void whatAPlayerCarries() {
        player.getInventory().addItem(new ItemStack(Material.DIAMOND, 7));
        BankWallets wallets = wallets(diamonds());

        assertThat(wallets.carried(player.getUniqueId(), "diamonds")).hasValue(7L);
        assertThat(wallets.carried(player.getUniqueId(), "gems")).isEmpty();
        assertThat(wallets.carried(UUID.randomUUID(), "diamonds")).isEmpty();
    }

    @Test
    @DisplayName("Only a plain item is money: a renamed diamond is neither counted nor taken")
    void aRenamedItemIsNotMoney() {
        ItemStack named = new ItemStack(Material.DIAMOND, 10);
        ItemMeta meta = named.getItemMeta();
        meta.displayName(Component.text("Keepsake"));
        named.setItemMeta(meta);
        player.getInventory().addItem(named, new ItemStack(Material.DIAMOND, 3));
        ItemWallet wallet = new ItemWallet(Material.DIAMOND);

        assertThat(wallet.balance(player, "")).isEqualTo(3);
        assertThat(wallet.withdraw(player, "", 4)).isFalse();
        assertThat(wallet.withdraw(player, "", 3)).isTrue();
        assertThat(player.getInventory().containsAtLeast(named, 10)).isTrue();
    }

    @Test
    @DisplayName("When the inventory gives up fewer items than it counted, what it did give goes back")
    void aPartPaymentIsGivenBack() {
        org.bukkit.entity.Player holder = mock(org.bukkit.entity.Player.class);
        org.bukkit.inventory.PlayerInventory inventory = mock(org.bukkit.inventory.PlayerInventory.class);
        when(holder.getInventory()).thenReturn(inventory);
        when(inventory.getStorageContents()).thenReturn(new ItemStack[] {new ItemStack(Material.DIAMOND, 10)});
        when(inventory.removeItem(any(ItemStack[].class)))
                .thenReturn(new java.util.HashMap<>(Map.of(0, new ItemStack(Material.DIAMOND, 4))));

        assertThat(new ItemWallet(Material.DIAMOND).withdraw(holder, "", 10)).isFalse();

        verify(inventory).addItem(new ItemStack(Material.DIAMOND, 6));
    }

    @Test
    @DisplayName("An item is moved in whole numbers, and only an item a player can carry is a currency")
    void wholeItems() {
        player.getInventory().addItem(new ItemStack(Material.DIAMOND, 3));
        ItemWallet wallet = new ItemWallet(Material.DIAMOND);

        assertThat(wallet.withdraw(player, "", 1.5)).isFalse();
        assertThat(wallet.withdraw(player, "", -1)).isFalse();
        assertThat(wallet.deposit(player, "", 0.5)).isFalse();
        assertThat(wallet.balance(null, "")).isZero();
        assertThat(diamondsCarried()).isEqualTo(3);
        assertThatThrownBy(() -> new ItemWallet(Material.AIR)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("More items than the inventory holds are paid all the same, the rest at the player's feet")
    void whatDoesNotFitIsDropped() {
        // Every slot, the armour and the off hand too, which the test server fills before it drops anything.
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            player.getInventory().setItem(slot, new ItemStack(Material.DIRT, 64));
        }

        assertThat(new ItemWallet(Material.DIAMOND).deposit(player, "", 70)).isTrue();

        assertThat(player.getWorld().getEntities())
                .filteredOn(entity -> entity instanceof org.bukkit.entity.Item)
                .extracting(entity ->
                        ((org.bukkit.entity.Item) entity).getItemStack().getAmount())
                .containsExactlyInAnyOrder(64, 6);
    }

    @Test
    @DisplayName("A deposit of more than the player carries never starts, and a withdrawal goes to the island's saga")
    void theBridgeMovesAListedCurrency() {
        player.getInventory().addItem(new ItemStack(Material.DIAMOND, 4));
        IslandBankService bank = mock(IslandBankService.class);
        EconomySagaCoordinator saga = mock(EconomySagaCoordinator.class);
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
        ProfileId profile = new ProfileId(player.getUniqueId());
        IslandId island = IslandId.of(UUID.randomUUID());
        when(bank.findIslandIdByProfileId(profile)).thenReturn(Optional.of(island));
        BankTransactionOutcome refused = new BankTransactionOutcome.AuthorityRejected(
                BankTransactionOutcome.AuthorityRejected.Kind.WALLET_REFUSED, "no");
        when(saga.executeWithdraw(any(), any(), eq(profile), eq(island), eq(9L), eq("diamonds"), any(), any()))
                .thenReturn(refused);
        SkyblockEconomyBridge bridge =
                new SkyblockEconomyBridge(mock(EconomyBridge.class), bank, scheduler, saga, wallets(diamonds()));
        ServerNodeId node = ServerNodeId.of("node-1");
        AtomicReference<BankTransactionOutcome> answer = new AtomicReference<>();

        bridge.moveHeld(player, profile, "diamonds", 5, true, node, answer::set);
        assertThat(answer.get()).isEqualTo(new BankTransactionOutcome.InsufficientFunds(4, 5));
        verify(saga, never()).executeDeposit(any(), any(), any(), any(), anyLong(), any(), any(), any());

        bridge.moveHeld(player, profile, "diamonds", 9, false, node, answer::set);
        assertThat(answer.get()).isSameAs(refused);

        bridge.moveHeld(player, profile, "gems", 1, true, node, answer::set);
        assertThat(answer.get())
                .isInstanceOfSatisfying(
                        BankTransactionOutcome.AuthorityRejected.class,
                        rejected -> assertThat(rejected.kind())
                                .isEqualTo(BankTransactionOutcome.AuthorityRejected.Kind.WALLET_REFUSED));

        bridge.moveHeld(player, profile, "diamonds", 0, true, node, answer::set);
        assertThat(answer.get())
                .isInstanceOfSatisfying(
                        BankTransactionOutcome.AuthorityRejected.class,
                        rejected -> assertThat(rejected.kind())
                                .isEqualTo(BankTransactionOutcome.AuthorityRejected.Kind.INVALID_AMOUNT));
    }
}
