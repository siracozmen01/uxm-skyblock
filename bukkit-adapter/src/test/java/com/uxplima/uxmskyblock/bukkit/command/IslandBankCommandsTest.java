package com.uxplima.uxmskyblock.bukkit.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.command.CommandSender;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import com.mojang.brigadier.CommandDispatcher;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.integration.economy.SkyblockEconomyBridge;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.core.application.bank.IslandBankService;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
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
 * {@code /is bank} runs, end to end, through Brigadier.
 *
 * <p>This is the command that moves a player's money, and it shipped without a test. The amount is
 * the part worth pinning: an argument type that accepts zero or a negative number is a withdrawal
 * that adds, and the type is the only thing standing in the way.
 */
class IslandBankCommandsTest {

    private static final IslandId ISLAND = IslandId.of(UUID.randomUUID());
    private static final ProfileId PROFILE = new ProfileId(UUID.randomUUID());

    private ServerMock server;
    private PlayerMock player;
    private SkyblockEconomyBridge bridge;
    private IslandLocationService locations;
    private PlayerSessionCoordinator sessions;
    private IslandBankService bank;
    private CommandDispatcher<CommandSourceStack> dispatcher;

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
        return scheduler;
    }

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        player = server.addPlayer();

        bridge = mock(SkyblockEconomyBridge.class);
        bank = mock(IslandBankService.class);
        when(bank.getBalanceMinorUnits(PROFILE)).thenReturn(Optional.of(12_345L));

        IslandLocationService locations = mock(IslandLocationService.class);
        when(locations.findIslandId(PROFILE)).thenReturn(Optional.of(ISLAND));

        PlayerSessionCoordinator sessions = mock(PlayerSessionCoordinator.class);
        when(sessions.activeProfile(player.getUniqueId())).thenReturn(Optional.of(PROFILE));

        this.locations = locations;
        this.sessions = sessions;
        registerWith(null);
    }

    @org.junit.jupiter.api.Test
    @DisplayName("Upkeep status reads the bankruptcy record once, off the command thread")
    void upkeepStatusReadsTheRecordOnce() throws Exception {
        com.uxplima.uxmskyblock.core.application.bank.IslandBankruptcyService upkeep =
                mock(com.uxplima.uxmskyblock.core.application.bank.IslandBankruptcyService.class);
        when(upkeep.getBankruptcyRecord(org.mockito.ArgumentMatchers.eq(ISLAND), any()))
                .thenReturn(new com.uxplima.uxmskyblock.core.domain.bank.IslandBankruptcyRecord(
                        ISLAND,
                        com.uxplima.uxmskyblock.core.domain.bank.BankruptcyStatus.SOLVENT,
                        0L,
                        null,
                        java.time.Instant.now()));
        registerWith(upkeep);

        run("bank status", player);

        verify(upkeep, org.mockito.Mockito.times(1))
                .getBankruptcyRecord(org.mockito.ArgumentMatchers.eq(ISLAND), any());
        assertThat(player.nextMessage()).describedAs("the header").isNotNull();
        assertThat(player.nextMessage()).describedAs("the state").isNotNull();
        assertThat(player.nextMessage()).describedAs("the debt").isNotNull();
    }

    @org.junit.jupiter.api.Test
    @DisplayName("Upkeep status on a locked island says what the player has to do about it")
    void alockedIslandSaysWhatToDo() throws Exception {
        com.uxplima.uxmskyblock.core.application.bank.IslandBankruptcyService upkeep =
                mock(com.uxplima.uxmskyblock.core.application.bank.IslandBankruptcyService.class);
        when(upkeep.getBankruptcyRecord(org.mockito.ArgumentMatchers.eq(ISLAND), any()))
                .thenReturn(new com.uxplima.uxmskyblock.core.domain.bank.IslandBankruptcyRecord(
                        ISLAND,
                        com.uxplima.uxmskyblock.core.domain.bank.BankruptcyStatus.LOCKED,
                        4200L,
                        null,
                        java.time.Instant.now()));
        registerWith(upkeep);

        run("bank status", player);

        assertThat(player.nextMessage()).isNotNull();
        assertThat(player.nextMessage()).isNotNull();
        assertThat(player.nextMessage()).describedAs("what is owed").contains("42");
        assertThat(player.nextMessage()).describedAs("the locked notice").isNotNull();
        assertThat(player.nextMessage()).describedAs("the hint").isNotNull();
    }

    @org.junit.jupiter.api.Test
    @DisplayName("A node with no upkeep service says so rather than saying nothing")
    void noUpkeepServiceIsAnAnswer() throws Exception {
        run("bank status", player);

        assertThat(player.nextMessage()).describedAs("told upkeep is off").isNotNull();
    }

    @org.junit.jupiter.api.Test
    @DisplayName("Paying the debt settles the arrears and says what it cost")
    void payingTheDebtSettles() throws Exception {
        com.uxplima.uxmskyblock.core.application.bank.IslandBankruptcyService upkeep =
                mock(com.uxplima.uxmskyblock.core.application.bank.IslandBankruptcyService.class);
        when(upkeep.settleArrears(org.mockito.ArgumentMatchers.eq(ISLAND), any(), any()))
                .thenReturn(
                        new com.uxplima.uxmskyblock.core.domain.bank.BankruptcyRemediationResult.Settled(4200L, 1000L));
        registerWith(upkeep);

        run("bank paydebt", player);

        verify(upkeep).settleArrears(org.mockito.ArgumentMatchers.eq(ISLAND), any(), any());
        assertThat(player.nextMessage())
                .describedAs("what was paid and what is left")
                .contains("42");
    }

    @org.junit.jupiter.api.Test
    @DisplayName("Paying a debt that is not there says there is nothing to pay")
    void payingNothingSaysSo() throws Exception {
        com.uxplima.uxmskyblock.core.application.bank.IslandBankruptcyService upkeep =
                mock(com.uxplima.uxmskyblock.core.application.bank.IslandBankruptcyService.class);
        when(upkeep.settleArrears(org.mockito.ArgumentMatchers.eq(ISLAND), any(), any()))
                .thenReturn(new com.uxplima.uxmskyblock.core.domain.bank.BankruptcyRemediationResult.NotInArrears());
        registerWith(upkeep);

        run("bank paydebt", player);

        assertThat(player.nextMessage()).isNotNull();
        assertThat(player.nextMessage()).describedAs("nothing else").isNull();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    /** Builds the command group again, this time with an upkeep service behind it. */
    private void registerWith(
            com.uxplima.uxmskyblock.core.application.bank.@org.jspecify.annotations.Nullable IslandBankruptcyService
                    upkeep) {
        IslandBankCommands commands = new IslandBankCommands(
                bank,
                locations,
                bridge,
                inlineScheduler(),
                ServerNodeId.of("node-1"),
                () -> upkeep,
                Messages.bundled(),
                sessions);
        dispatcher = new CommandDispatcher<>();
        dispatcher.register(commands.build());
    }

    private void run(String line, CommandSender sender) throws Exception {
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn(sender);
        dispatcher.execute(line, source);
    }

    /** Puts the caller on the island as a member holding exactly these permissions. */
    private void callerHolds(com.uxplima.uxmskyblock.core.domain.island.IslandPermission... permissions) {
        com.uxplima.uxmskyblock.core.domain.island.Island island =
                com.uxplima.uxmskyblock.core.domain.island.Island.create(
                        ISLAND,
                        com.uxplima.uxmskyblock.core.domain.island.IslandBounds.fromCenterAndRadius(0, 0, 64),
                        new PlayerUuid(UUID.randomUUID()),
                        new ProfileId(UUID.randomUUID()),
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
                .thenReturn(Optional.of(island.addMember(new com.uxplima.uxmskyblock.core.domain.island.IslandMember(
                        new PlayerUuid(player.getUniqueId()), PROFILE, role, java.time.Instant.now()))));
    }

    @Test
    @DisplayName("A member whose role holds no withdraw permission cannot empty the island bank")
    void arolewithoutWithdrawIsRefused() throws Exception {
        callerHolds(com.uxplima.uxmskyblock.core.domain.island.IslandPermission.BANK_DEPOSIT);

        run("bank withdraw 250", player);

        verify(bridge, never()).withdrawFromIslandBank(any(), any(), anyLong(), any(), any());
        assertThat(player.nextMessage())
                .describedAs("the role editor said no and the bank never read it")
                .isNotNull();
    }

    @Test
    @DisplayName("A member whose role holds the withdraw permission withdraws")
    void arolewithWithdrawGoesThrough() throws Exception {
        callerHolds(com.uxplima.uxmskyblock.core.domain.island.IslandPermission.BANK_WITHDRAW);

        run("bank withdraw 250", player);

        verify(bridge).withdrawFromIslandBank(any(), eq(PROFILE), eq(250L), any(ServerNodeId.class), any());
    }

    @Test
    @DisplayName("A member whose role holds no deposit permission cannot put money in either")
    void arolewithoutDepositIsRefused() throws Exception {
        callerHolds(com.uxplima.uxmskyblock.core.domain.island.IslandPermission.BANK_WITHDRAW);

        run("bank deposit 250", player);

        verify(bridge, never()).depositToIslandBank(any(), any(), anyLong(), any(), any());
    }

    @Test
    @DisplayName("The owner moves money whatever the roles say")
    void theOwnerIsNeverRefused() throws Exception {
        com.uxplima.uxmskyblock.core.domain.island.Island island =
                com.uxplima.uxmskyblock.core.domain.island.Island.create(
                        ISLAND,
                        com.uxplima.uxmskyblock.core.domain.island.IslandBounds.fromCenterAndRadius(0, 0, 64),
                        new PlayerUuid(player.getUniqueId()),
                        PROFILE,
                        java.time.Instant.now());
        when(locations.findIsland(ISLAND)).thenReturn(Optional.of(island));

        run("bank withdraw 250", player);

        verify(bridge).withdrawFromIslandBank(any(), eq(PROFILE), eq(250L), any(ServerNodeId.class), any());
    }

    @Test
    @DisplayName("Depositing names the amount the player typed")
    void depositingNamesTheAmount() throws Exception {
        run("bank deposit 250", player);

        verify(bridge).depositToIslandBank(any(), eq(PROFILE), eq(250L), any(ServerNodeId.class), any());
    }

    @Test
    @DisplayName("Withdrawing names the amount the player typed")
    void withdrawingNamesTheAmount() throws Exception {
        run("bank withdraw 40", player);

        verify(bridge).withdrawFromIslandBank(any(), eq(PROFILE), eq(40L), any(ServerNodeId.class), any());
    }

    @Test
    @DisplayName("Zero and negative amounts never reach the bank, and the player is told why")
    void zeroAndNegativeNeverReachTheBank() throws Exception {
        for (String amount : new String[] {"0", "-1", "-1000"}) {
            run("bank deposit " + amount, player);
            assertThat(said()).describedAs("a deposit of %s", amount).contains(amount + " is not an amount");
        }
        verify(bridge, never()).depositToIslandBank(any(), any(), anyLong(), any(), any());
        verify(bridge, never()).withdrawFromIslandBank(any(), any(), anyLong(), any(), any());
    }

    @Test
    @DisplayName("A word where an amount belongs never reaches the bank, and the player is told how to write one")
    void aWordWhereAnAmountBelongsIsRefused() throws Exception {
        run("bank deposit lots", player);

        assertThat(said()).contains("lots is not an amount").contains("2.5k");
        verify(bridge, never()).depositToIslandBank(any(), any(), anyLong(), any(), any());
    }

    @Test
    @DisplayName("An amount written with a k, an m or a b, or with its thousands grouped, moves that many")
    void anAmountIsReadAsAPlayerWritesIt() throws Exception {
        run("bank deposit 2.5k", player);
        run("bank withdraw 1,500", player);
        run("bank withdraw 2m", player);

        verify(bridge).depositToIslandBank(any(), eq(PROFILE), eq(2_500L), any(ServerNodeId.class), any());
        verify(bridge).withdrawFromIslandBank(any(), eq(PROFILE), eq(1_500L), any(ServerNodeId.class), any());
        verify(bridge).withdrawFromIslandBank(any(), eq(PROFILE), eq(2_000_000L), any(ServerNodeId.class), any());
    }

    /** The next line the player was told, as they read it. */
    private com.uxplima.uxmskyblock.core.domain.bank.IslandBank bankHolding(long minor, Map<String, Long> held) {
        return new com.uxplima.uxmskyblock.core.domain.bank.IslandBank(
                ISLAND, minor, 0L, 0L, 1L, java.time.Instant.now(), held);
    }

    /** The bank keeps tokens, a currency in a plugin that is here, which the player carries nine of. */
    private void tokensKept() {
        com.uxplima.uxmlib.condition.wallet.BridgedWallet wallet =
                mock(com.uxplima.uxmlib.condition.wallet.BridgedWallet.class);
        when(wallet.reaches("")).thenReturn(true);
        when(wallet.balance(any(), any())).thenReturn(9.0);
        var spec = new com.uxplima.uxmskyblock.bukkit.config.BankCurrencySpec(
                "tokens",
                com.uxplima.uxmskyblock.bukkit.config.BankCurrencySpec.Type.PLAYERPOINTS,
                10,
                true,
                "Tokens",
                "SUNFLOWER",
                Map.of());
        var wallets = new com.uxplima.uxmskyblock.bukkit.integration.economy.BankWallets(
                mock(com.uxplima.uxmskyblock.core.application.economy.ExternalWalletPort.class),
                inlineScheduler(),
                Map.of(
                        "tokens",
                        new com.uxplima.uxmskyblock.bukkit.integration.economy.BankWallets.Kept(spec, wallet, "")));
        when(bridge.wallets()).thenReturn(Optional.of(wallets));
    }

    @Test
    @DisplayName("All of the money is what the wallet holds going in, and what the island holds coming out, whole")
    void allOfTheMoney() throws Exception {
        when(bridge.balance(any())).thenReturn(2_500.75);
        when(bank.findBank(PROFILE)).thenReturn(Optional.of(bankHolding(12_345L, Map.of())));

        run("bank deposit all", player);
        run("bank withdraw all", player);

        verify(bridge).depositToIslandBank(any(), eq(PROFILE), eq(2_500L), any(ServerNodeId.class), any());
        verify(bridge).withdrawFromIslandBank(any(), eq(PROFILE), eq(123L), any(ServerNodeId.class), any());
    }

    @Test
    @DisplayName("All of a currency is what the player carries going in, and what the island holds coming out")
    void allOfACurrency() throws Exception {
        tokensKept();
        when(bank.findBank(PROFILE)).thenReturn(Optional.of(bankHolding(0L, Map.of("tokens", 40L))));

        run("bank deposit all tokens", player);
        run("bank withdraw all tokens", player);

        verify(bridge).moveHeld(any(), eq(PROFILE), eq("tokens"), eq(9L), eq(true), any(ServerNodeId.class), any());
        verify(bridge).moveHeld(any(), eq(PROFILE), eq("tokens"), eq(40L), eq(false), any(ServerNodeId.class), any());
    }

    @Test
    @DisplayName("All of nothing moves nothing, and the player is told there is nothing to move")
    void allOfNothing() throws Exception {
        when(bridge.balance(any())).thenReturn(0.5);
        when(bank.findBank(PROFILE)).thenReturn(Optional.empty());

        run("bank deposit all", player);
        assertThat(said()).contains("nothing to move");
        run("bank withdraw all", player);
        assertThat(said()).contains("nothing to move");

        verify(bridge, never()).depositToIslandBank(any(), any(), anyLong(), any(), any());
        verify(bridge, never()).withdrawFromIslandBank(any(), any(), anyLong(), any(), any());
    }

    @Test
    @DisplayName("A Turkish reader writes all in their own word")
    void allInTheReadersWord() throws Exception {
        player.setLocale(java.util.Locale.forLanguageTag("tr"));
        when(bridge.balance(any())).thenReturn(70.0);

        run("bank deposit hepsi", player);

        verify(bridge).depositToIslandBank(any(), eq(PROFILE), eq(70L), any(ServerNodeId.class), any());
    }

    @Test
    @DisplayName("Once a move lands the bank says which currency moved, the money as primary")
    void aLandedMoveIsSaid() throws Exception {
        IslandBankCommands commands = new IslandBankCommands(
                bank,
                locations,
                bridge,
                inlineScheduler(),
                ServerNodeId.of("node-1"),
                () -> null,
                Messages.bundled(),
                sessions);
        java.util.List<String> moved = new java.util.ArrayList<>();
        commands.useMoved((who, currency) -> moved.add(currency));
        dispatcher = new CommandDispatcher<>();
        dispatcher.register(commands.build());
        tokensKept();
        var landed = mock(com.uxplima.uxmskyblock.core.domain.bank.BankTransactionOutcome.Success.class);
        doAnswer(invocation -> {
                    invocation
                            .<java.util.function.Consumer<Object>>getArgument(4)
                            .accept(landed);
                    return null;
                })
                .when(bridge)
                .depositToIslandBank(any(), any(), anyLong(), any(), any());
        doAnswer(invocation -> {
                    invocation
                            .<java.util.function.Consumer<Object>>getArgument(6)
                            .accept(landed);
                    return null;
                })
                .when(bridge)
                .moveHeld(any(), any(), any(), anyLong(), org.mockito.ArgumentMatchers.anyBoolean(), any(), any());
        var refused = mock(com.uxplima.uxmskyblock.core.domain.bank.BankTransactionOutcome.InsufficientFunds.class);
        doAnswer(invocation -> {
                    invocation
                            .<java.util.function.Consumer<Object>>getArgument(4)
                            .accept(refused);
                    return null;
                })
                .when(bridge)
                .withdrawFromIslandBank(any(), any(), anyLong(), any(), any());

        run("bank deposit 5", player);
        run("bank deposit 3 tokens", player);
        run("bank withdraw 5", player);

        assertThat(moved).describedAs("a refused move draws nothing again").containsExactly("primary", "tokens");
    }

    private String said() {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(java.util.Objects.requireNonNull(player.nextComponentMessage()));
    }

    @Test
    @DisplayName("The bare command reads the balance and moves nothing")
    void theBareCommandReadsTheBalance() throws Exception {
        run("bank", player);

        verify(bank).getBalanceMinorUnits(PROFILE);
        verify(bridge, never()).depositToIslandBank(any(), any(), anyLong(), any(), any());
    }

    @Test
    @DisplayName("Balance is the same branch as the bare command")
    void balanceIsTheSameBranch() throws Exception {
        run("bank balance", player);

        verify(bank).getBalanceMinorUnits(PROFILE);
    }

    @Test
    @DisplayName("Upkeep with no bankruptcy service tells the player rather than throwing")
    void upkeepWithoutTheServiceIsExplained() throws Exception {
        player.nextMessage();

        run("bank upkeep", player);

        assertThat(player.nextMessage())
                .describedAs("a switched off subsystem must answer, not go quiet")
                .isNotNull();
    }

    @Test
    @DisplayName("The console moves no money")
    void theConsoleMovesNoMoney() throws Exception {
        run("bank deposit 100", server.getConsoleSender());

        verify(bridge, never()).depositToIslandBank(any(), any(), anyLong(), any(), any());
    }

    /** The bridge keeps experience beside the island's money, as the shipped file does. */
    private void experienceIsKept() {
        com.uxplima.uxmskyblock.bukkit.config.BankCurrencySpec experience =
                new com.uxplima.uxmskyblock.bukkit.config.BankCurrencySpec(
                        "experience",
                        com.uxplima.uxmskyblock.bukkit.config.BankCurrencySpec.Type.EXPERIENCE,
                        10,
                        true,
                        "@bank.currencies.experience",
                        "EXPERIENCE_BOTTLE",
                        java.util.Map.of());
        com.uxplima.uxmskyblock.bukkit.integration.economy.BankWallets wallets =
                new com.uxplima.uxmskyblock.bukkit.integration.economy.BankWallets(
                        mock(com.uxplima.uxmskyblock.core.application.economy.ExternalWalletPort.class),
                        inlineScheduler(),
                        java.util.Map.of(
                                "experience",
                                new com.uxplima.uxmskyblock.bukkit.integration.economy.BankWallets.Kept(
                                        experience,
                                        com.uxplima.uxmlib.condition.wallet.ExperienceWallet.ofPoints(),
                                        "")));
        when(bridge.wallets()).thenReturn(Optional.of(wallets));
    }

    /** Answers every move of a listed currency with {@code outcome}. */
    private void everyHeldMoveEnds(com.uxplima.uxmskyblock.core.domain.bank.BankTransactionOutcome outcome) {
        doAnswer(invocation -> {
                    invocation
                            .<java.util.function.Consumer<
                                            com.uxplima.uxmskyblock.core.domain.bank.BankTransactionOutcome>>
                                    getArgument(6)
                            .accept(outcome);
                    return null;
                })
                .when(bridge)
                .moveHeld(any(), any(), any(), anyLong(), org.mockito.ArgumentMatchers.anyBoolean(), any(), any());
    }

    @Test
    @DisplayName("An amount followed by a listed currency moves that currency, and the island's money stays put")
    void aListedCurrencyIsNamedAfterTheAmount() throws Exception {
        experienceIsKept();
        everyHeldMoveEnds(
                new com.uxplima.uxmskyblock.core.domain.bank.BankTransactionOutcome.InsufficientFunds(30, 50));

        run("bank deposit 50 experience", player);
        assertThat(said()).endsWith("You have only 30 Experience.");
        run("bank withdraw 2k Experience", player);

        verify(bridge).moveHeld(any(), eq(PROFILE), eq("experience"), eq(50L), eq(true), any(), any());
        verify(bridge).moveHeld(any(), eq(PROFILE), eq("experience"), eq(2_000L), eq(false), any(), any());
        assertThat(said()).endsWith("The island bank holds only 30 Experience.");
        verify(bridge, never()).depositToIslandBank(any(), any(), anyLong(), any(), any());
        verify(bridge, never()).withdrawFromIslandBank(any(), any(), anyLong(), any(), any());
    }

    @Test
    @DisplayName("A move of a listed currency that lands names the amount and the currency")
    void aListedMoveThatLands() throws Exception {
        experienceIsKept();
        everyHeldMoveEnds(new com.uxplima.uxmskyblock.core.domain.bank.BankTransactionOutcome.Success(
                new com.uxplima.uxmskyblock.core.domain.bank.IslandBank(
                        ISLAND, 0L, 0L, 0L, 1L, java.time.Instant.now()),
                new com.uxplima.uxmskyblock.core.domain.bank.BankTransaction(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        ISLAND,
                        player.getUniqueId(),
                        "experience",
                        0,
                        50L,
                        50L,
                        "In",
                        java.time.Instant.now())));

        run("bank deposit 50 experience", player);
        assertThat(said()).endsWith("You put 50 Experience into the island bank.");
        run("bank withdraw 50 experience", player);
        assertThat(said()).endsWith("You took 50 Experience from the island bank.");
    }

    @Test
    @DisplayName("A word after the amount that names no listed currency is no amount, and nothing moves")
    void aCurrencyTheBankLacks() throws Exception {
        experienceIsKept();

        run("bank deposit 50 gems", player);

        assertThat(said()).contains("50 gems is not an amount");
        verify(bridge, never())
                .moveHeld(any(), any(), any(), anyLong(), org.mockito.ArgumentMatchers.anyBoolean(), any(), any());
        verify(bridge, never()).depositToIslandBank(any(), any(), anyLong(), any(), any());
    }

    @Test
    @DisplayName("A listed currency asks the role for the same permission the money does")
    void aListedCurrencyAsksTheRole() throws Exception {
        experienceIsKept();
        callerHolds(com.uxplima.uxmskyblock.core.domain.island.IslandPermission.BANK_WITHDRAW);

        run("bank deposit 50 experience", player);
        run("bank withdraw 50 experience", player);

        verify(bridge, never()).moveHeld(any(), any(), any(), anyLong(), eq(true), any(), any());
        verify(bridge).moveHeld(any(), eq(PROFILE), eq("experience"), eq(50L), eq(false), any(), any());
    }

    @Test
    @DisplayName("A refused move of a listed currency says so, and an unreadable amount of one is named")
    void aListedMoveThatIsRefused() throws Exception {
        experienceIsKept();
        everyHeldMoveEnds(new com.uxplima.uxmskyblock.core.domain.bank.BankTransactionOutcome.AuthorityRejected(
                com.uxplima.uxmskyblock.core.domain.bank.BankTransactionOutcome.AuthorityRejected.Kind.WALLET_REFUSED,
                "no"));

        run("bank deposit 5 experience", player);
        assertThat(said()).endsWith("The Experience could not be moved, so nothing changed.");
        run("bank deposit lots experience", player);
        assertThat(said()).contains("lots is not an amount");
    }

    @Test
    @DisplayName("The balance lists what the island holds of every listed currency under its money")
    void theBalanceListsEveryCurrency() throws Exception {
        experienceIsKept();
        when(bank.findBank(PROFILE))
                .thenReturn(Optional.of(new com.uxplima.uxmskyblock.core.domain.bank.IslandBank(
                        ISLAND, 1_234_500L, 0L, 0L, 1L, java.time.Instant.now(), java.util.Map.of("experience", 42L))));

        run("bank", player);

        assertThat(said()).contains("123.45");
        assertThat(said()).isEqualTo(" • Experience 42");
    }

    @Test
    @DisplayName("A bank that keeps no other currency reads only its money")
    void aBankOfMoneyAlone() throws Exception {
        run("bank", player);

        said();
        assertThat(player.nextComponentMessage()).isNull();
        verify(bank, never()).findBank(any());
    }
}
