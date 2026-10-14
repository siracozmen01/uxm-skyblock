package com.uxplima.uxmskyblock.bukkit.integration.economy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.bukkit.entity.Player;

import com.uxplima.uxmlib.hook.economy.EconomyBridge;
import com.uxplima.uxmskyblock.core.application.bank.IslandBankService;
import com.uxplima.uxmskyblock.core.application.economy.EconomySagaCoordinator;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.bank.BankTransaction;
import com.uxplima.uxmskyblock.core.domain.bank.BankTransactionOutcome;
import com.uxplima.uxmskyblock.core.domain.bank.IslandBank;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SkyblockEconomyBridgeTest {

    private EconomyBridge mockEconomy;
    private IslandBankService mockBankService;
    private SchedulerPort directScheduler;
    private SkyblockEconomyBridge bridge;

    private Player mockPlayer;
    private UUID playerUuid;
    private ProfileId profileId;
    private ServerNodeId nodeId;
    private IslandId islandId;
    private IslandBank sampleBank;
    private BankTransaction sampleTx;

    @BeforeEach
    void setUp() {
        mockEconomy = mock(EconomyBridge.class);
        mockBankService = mock(IslandBankService.class);
        directScheduler = new DirectSchedulerPort();
        bridge = new SkyblockEconomyBridge(mockEconomy, mockBankService, directScheduler);

        mockPlayer = mock(Player.class);
        playerUuid = UUID.randomUUID();
        when(mockPlayer.getUniqueId()).thenReturn(playerUuid);
        profileId = new ProfileId(playerUuid);
        nodeId = ServerNodeId.of("node-1");
        islandId = new IslandId(UUID.randomUUID());
        sampleBank = new IslandBank(islandId, 10000L, 0L, 0L, 2L, Instant.now());
        sampleTx = new BankTransaction(
                UUID.randomUUID(),
                UUID.randomUUID(),
                islandId,
                playerUuid,
                "PRIMARY",
                2,
                5000L,
                10000L,
                "Test",
                Instant.now());
        when(mockBankService.findIslandIdByProfileId(profileId)).thenReturn(Optional.of(islandId));
    }

    @Test
    @DisplayName("A rejected deposit refunds the wallet and says so when the refund lands")
    void aRejectedDepositRefundsTheWallet() {
        when(mockEconomy.isPresent()).thenReturn(true);
        when(mockEconomy.has(eq(mockPlayer), anyDouble())).thenReturn(true);
        when(mockEconomy.withdraw(eq(mockPlayer), anyDouble())).thenReturn(true);
        when(mockEconomy.deposit(eq(mockPlayer), anyDouble())).thenReturn(true);
        when(mockBankService.findIslandIdByProfileId(profileId)).thenReturn(Optional.of(islandId));
        when(mockBankService.deposit(any(), any(), anyLong(), any()))
                .thenReturn(new BankTransactionOutcome.AuthorityRejected("bank said no"));

        AtomicReference<BankTransactionOutcome> seen = new AtomicReference<>();
        bridge.depositToIslandBank(mockPlayer, profileId, 50L, nodeId, seen::set);

        verify(mockEconomy).deposit(eq(mockPlayer), eq(50.0));
        assertThat(seen.get()).isInstanceOf(BankTransactionOutcome.AuthorityRejected.class);
        BankTransactionOutcome.AuthorityRejected rejected =
                (BankTransactionOutcome.AuthorityRejected) Objects.requireNonNull(seen.get());
        assertThat(rejected.reason()).isEqualTo("bank said no");
    }

    @Test
    @DisplayName("A refund that does not land is reported rather than leaving the player paid up for nothing")
    void aFailedRefundIsReported() {
        when(mockEconomy.isPresent()).thenReturn(true);
        when(mockEconomy.has(eq(mockPlayer), anyDouble())).thenReturn(true);
        when(mockEconomy.withdraw(eq(mockPlayer), anyDouble())).thenReturn(true);
        when(mockEconomy.deposit(eq(mockPlayer), anyDouble())).thenReturn(false);
        when(mockBankService.findIslandIdByProfileId(profileId)).thenReturn(Optional.of(islandId));
        when(mockBankService.deposit(any(), any(), anyLong(), any()))
                .thenReturn(new BankTransactionOutcome.AuthorityRejected("bank said no"));

        AtomicReference<BankTransactionOutcome> seen = new AtomicReference<>();
        bridge.depositToIslandBank(mockPlayer, profileId, 50L, nodeId, seen::set);

        assertThat(seen.get()).isInstanceOf(BankTransactionOutcome.AuthorityRejected.class);
        BankTransactionOutcome.AuthorityRejected rejected =
                (BankTransactionOutcome.AuthorityRejected) Objects.requireNonNull(seen.get());
        assertThat(rejected.reason()).contains("could not be").contains("refunded");
    }

    @Test
    @DisplayName("rejects zero or negative deposit and withdraw amounts")
    void rejectsZeroOrNegativeAmounts() {
        AtomicReference<BankTransactionOutcome> outcomeRef = new AtomicReference<>();
        bridge.depositToIslandBank(mockPlayer, profileId, 0, nodeId, outcomeRef::set);
        assertThat(outcomeRef.get()).isInstanceOf(BankTransactionOutcome.AuthorityRejected.class);

        bridge.withdrawFromIslandBank(mockPlayer, profileId, -5, nodeId, outcomeRef::set);
        assertThat(outcomeRef.get()).isInstanceOf(BankTransactionOutcome.AuthorityRejected.class);

        verify(mockEconomy, never()).withdraw(any(), anyDouble());
        verify(mockBankService, never()).deposit(any(), any(), anyLong(), any());
    }

    @Test
    @DisplayName("deposit with economy withdraws from wallet and deposits to island bank")
    void depositWithEconomySuccess() {
        when(mockEconomy.isPresent()).thenReturn(true);
        when(mockEconomy.has(mockPlayer, 50.0)).thenReturn(true);
        when(mockEconomy.withdraw(mockPlayer, 50.0)).thenReturn(true);
        when(mockBankService.deposit(eq(profileId), any(PlayerUuid.class), eq(5000L), eq(nodeId)))
                .thenReturn(new BankTransactionOutcome.Success(sampleBank, sampleTx));

        AtomicReference<BankTransactionOutcome> outcomeRef = new AtomicReference<>();
        bridge.depositToIslandBank(mockPlayer, profileId, 50, nodeId, outcomeRef::set);

        assertThat(outcomeRef.get()).isInstanceOf(BankTransactionOutcome.Success.class);
        verify(mockEconomy).withdraw(mockPlayer, 50.0);
        verify(mockBankService).deposit(eq(profileId), any(PlayerUuid.class), eq(5000L), eq(nodeId));
        verify(mockEconomy, never()).deposit(any(), anyDouble());
    }

    @Test
    @DisplayName("deposit refunds wallet when island bank transaction fails")
    void depositRefundsOnBankFailure() {
        when(mockEconomy.isPresent()).thenReturn(true);
        when(mockEconomy.has(mockPlayer, 100.0)).thenReturn(true);
        when(mockEconomy.withdraw(mockPlayer, 100.0)).thenReturn(true);
        when(mockBankService.deposit(eq(profileId), any(PlayerUuid.class), eq(10000L), eq(nodeId)))
                .thenReturn(new BankTransactionOutcome.AuthorityRejected("Authority lock failed"));

        AtomicReference<BankTransactionOutcome> outcomeRef = new AtomicReference<>();
        bridge.depositToIslandBank(mockPlayer, profileId, 100, nodeId, outcomeRef::set);

        assertThat(outcomeRef.get()).isInstanceOf(BankTransactionOutcome.AuthorityRejected.class);
        // Assert compensation refund was issued to player wallet
        verify(mockEconomy).deposit(mockPlayer, 100.0);
    }

    @Test
    @DisplayName("deposit fails immediately without deducting wallet if player has insufficient funds")
    void depositFailsOnInsufficientWalletFunds() {
        when(mockEconomy.isPresent()).thenReturn(true);
        when(mockEconomy.has(mockPlayer, 500.0)).thenReturn(false);
        when(mockEconomy.format(500.0)).thenReturn("$500.00");

        AtomicReference<BankTransactionOutcome> outcomeRef = new AtomicReference<>();
        bridge.depositToIslandBank(mockPlayer, profileId, 500, nodeId, outcomeRef::set);

        assertThat(outcomeRef.get()).isInstanceOf(BankTransactionOutcome.InsufficientFunds.class);
        verify(mockEconomy, never()).withdraw(any(), anyDouble());
        verify(mockBankService, never()).deposit(any(), any(), anyLong(), any());
    }

    @Test
    @DisplayName("withdraw gives money to wallet when island bank transaction succeeds")
    void withdrawGivesFundsToWallet() {
        when(mockEconomy.isPresent()).thenReturn(true);
        when(mockBankService.withdraw(eq(profileId), any(PlayerUuid.class), eq(2500L), eq(nodeId)))
                .thenReturn(new BankTransactionOutcome.Success(sampleBank, sampleTx));
        when(mockEconomy.deposit(mockPlayer, 25.0)).thenReturn(true);

        AtomicReference<BankTransactionOutcome> outcomeRef = new AtomicReference<>();
        bridge.withdrawFromIslandBank(mockPlayer, profileId, 25, nodeId, outcomeRef::set);

        assertThat(outcomeRef.get()).isInstanceOf(BankTransactionOutcome.Success.class);
        verify(mockEconomy).deposit(mockPlayer, 25.0);
    }

    @Test
    @DisplayName("withdraw refunds island bank if wallet deposit fails")
    void withdrawRefundsBankOnWalletDepositFailure() {
        when(mockEconomy.isPresent()).thenReturn(true);
        when(mockBankService.withdraw(eq(profileId), any(PlayerUuid.class), eq(2500L), eq(nodeId)))
                .thenReturn(new BankTransactionOutcome.Success(sampleBank, sampleTx));
        when(mockEconomy.deposit(mockPlayer, 25.0)).thenReturn(false);

        AtomicReference<BankTransactionOutcome> outcomeRef = new AtomicReference<>();
        bridge.withdrawFromIslandBank(mockPlayer, profileId, 25, nodeId, outcomeRef::set);

        assertThat(outcomeRef.get()).isInstanceOf(BankTransactionOutcome.AuthorityRejected.class);
        // Bank deposit called to refund the bank
        verify(mockBankService).deposit(eq(profileId), any(PlayerUuid.class), eq(2500L), eq(nodeId));
    }

    @Test
    @DisplayName("deposit with saga coordinator routes through executeDeposit")
    void depositWithSagaCoordinatorRoutesCorrectly() {
        EconomySagaCoordinator mockCoordinator = mock(EconomySagaCoordinator.class);
        SkyblockEconomyBridge sagaBridge =
                new SkyblockEconomyBridge(mockEconomy, mockBankService, directScheduler, mockCoordinator);

        when(mockEconomy.isPresent()).thenReturn(true);
        when(mockEconomy.has(any(), org.mockito.ArgumentMatchers.anyDouble())).thenReturn(true);
        when(mockCoordinator.executeDeposit(
                        any(),
                        any(PlayerUuid.class),
                        eq(profileId),
                        eq(islandId),
                        eq(5000L),
                        eq("VAULT"),
                        eq(nodeId),
                        any(Instant.class)))
                .thenReturn(new BankTransactionOutcome.Success(sampleBank, sampleTx));

        AtomicReference<BankTransactionOutcome> outcomeRef = new AtomicReference<>();
        sagaBridge.depositToIslandBank(mockPlayer, profileId, 50, nodeId, outcomeRef::set);

        assertThat(outcomeRef.get()).isInstanceOf(BankTransactionOutcome.Success.class);
        verify(mockCoordinator)
                .executeDeposit(
                        any(),
                        any(PlayerUuid.class),
                        eq(profileId),
                        eq(islandId),
                        eq(5000L),
                        eq("VAULT"),
                        eq(nodeId),
                        any(Instant.class));
    }

    @Test
    @DisplayName("recoverPendingSagas delegates to saga coordinator")
    void recoverPendingSagasDelegates() {
        EconomySagaCoordinator mockCoordinator = mock(EconomySagaCoordinator.class);
        SkyblockEconomyBridge sagaBridge =
                new SkyblockEconomyBridge(mockEconomy, mockBankService, directScheduler, mockCoordinator);

        when(mockCoordinator.recoverIncompleteSagas(any(Instant.class), eq(nodeId)))
                .thenReturn(3);

        int recovered = sagaBridge.recoverPendingSagas(nodeId);
        assertThat(recovered).isEqualTo(3);
        verify(mockCoordinator).recoverIncompleteSagas(any(Instant.class), eq(nodeId));
    }

    @Test
    @DisplayName("Abandoned sagas are settled every saga timeout while the server runs, not only at startup")
    void sagasAreSettledWhileTheServerRuns() throws Exception {
        EconomySagaCoordinator mockCoordinator = mock(EconomySagaCoordinator.class);
        when(mockCoordinator.sagaTimeout()).thenReturn(Duration.ofSeconds(30));
        java.util.List<Runnable> repeating = new java.util.ArrayList<>();
        java.util.List<Duration> periods = new java.util.ArrayList<>();
        SchedulerPort recording = new DirectSchedulerPort() {
            @Override
            public AutoCloseable repeatAsync(Runnable task, Duration initialDelay, Duration period) {
                repeating.add(task);
                periods.add(period);
                return () -> {};
            }
        };
        SkyblockEconomyBridge sagaBridge =
                new SkyblockEconomyBridge(mockEconomy, mockBankService, recording, mockCoordinator);

        sagaBridge.keepRecoveringSagas(nodeId).close();

        assertThat(periods).containsExactly(Duration.ofSeconds(30));
        repeating.getFirst().run();
        repeating.getFirst().run();
        verify(mockCoordinator, org.mockito.Mockito.times(2)).recoverIncompleteSagas(any(Instant.class), eq(nodeId));
        assertThat(java.nio.file.Files.readString(java.nio.file.Path.of(
                        "src/main/java/com/uxplima/uxmskyblock/bukkit/bootstrap/IntegrationWiring.java")))
                .describedAs("the server keeps the recovery running once it is up")
                .contains("this.sagaRecovery = economyBridge.keepRecoveringSagas(serverNodeId);");
    }

    private static class DirectSchedulerPort implements SchedulerPort {
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
    }

    @Test
    @DisplayName("With no economy plugin a deposit is refused and the island bank gains nothing")
    void noEconomyNoDeposit() {
        EconomySagaCoordinator coordinator = mock(EconomySagaCoordinator.class);
        when(mockEconomy.isPresent()).thenReturn(false);
        AtomicReference<BankTransactionOutcome> plain = new AtomicReference<>();
        AtomicReference<BankTransactionOutcome> saga = new AtomicReference<>();

        bridge.depositToIslandBank(mockPlayer, profileId, 1_000_000_000L, nodeId, plain::set);
        new SkyblockEconomyBridge(mockEconomy, mockBankService, directScheduler, coordinator)
                .depositToIslandBank(mockPlayer, profileId, 1_000_000_000L, nodeId, saga::set);

        assertThat(List.of(plain.get(), saga.get()))
                .allSatisfy(outcome -> assertThat(outcome)
                        .isEqualTo(new BankTransactionOutcome.AuthorityRejected(
                                BankTransactionOutcome.AuthorityRejected.Kind.NO_ECONOMY,
                                "Deposit refused: no economy plugin is installed.")));
        verify(mockBankService, never()).deposit(any(), any(), anyLong(), any());
        verify(coordinator, never()).executeDeposit(any(), any(), any(), any(), anyLong(), any(), any(), any());
    }

    @Test
    @DisplayName("With no economy plugin a withdrawal is refused and the island bank loses nothing")
    void noEconomyNoWithdrawal() {
        EconomySagaCoordinator coordinator = mock(EconomySagaCoordinator.class);
        when(mockEconomy.isPresent()).thenReturn(false);
        AtomicReference<BankTransactionOutcome> plain = new AtomicReference<>();
        AtomicReference<BankTransactionOutcome> saga = new AtomicReference<>();

        bridge.withdrawFromIslandBank(mockPlayer, profileId, 50L, nodeId, plain::set);
        new SkyblockEconomyBridge(mockEconomy, mockBankService, directScheduler, coordinator)
                .withdrawFromIslandBank(mockPlayer, profileId, 50L, nodeId, saga::set);

        assertThat(List.of(plain.get(), saga.get()))
                .allSatisfy(outcome -> assertThat(outcome)
                        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(
                                BankTransactionOutcome.AuthorityRejected.class))
                        .extracting(BankTransactionOutcome.AuthorityRejected::kind)
                        .isEqualTo(BankTransactionOutcome.AuthorityRejected.Kind.NO_ECONOMY));
        verify(mockBankService, never()).withdraw(any(), any(), anyLong(), any());
        verify(coordinator, never()).executeWithdraw(any(), any(), any(), any(), anyLong(), any(), any(), any());
    }

    @Test
    @DisplayName("A wallet that does not exist has no funds, pays nothing and takes nothing")
    void aMissingWalletAnswersNo() {
        when(mockEconomy.isPresent()).thenReturn(false);
        BukkitVaultWalletAdapter wallet = new BukkitVaultWalletAdapter(mockEconomy);
        PlayerUuid who = new PlayerUuid(playerUuid);

        assertThat(wallet.hasFunds(who, 100L)).isFalse();
        assertThat(wallet.withdraw(who, 100L)).isFalse();
        assertThat(wallet.deposit(who, 100L)).isFalse();
    }

    @Test
    @DisplayName(
            "A deposit that would fill the bank past its limit is refused before any money moves, and one that fits goes in")
    void aFullBankRefusesTheDeposit() {
        when(mockEconomy.isPresent()).thenReturn(true);
        when(mockEconomy.has(eq(mockPlayer), anyDouble())).thenReturn(true);
        when(mockEconomy.withdraw(eq(mockPlayer), anyDouble())).thenReturn(true);
        when(mockBankService.getBalanceMinorUnits(profileId)).thenReturn(Optional.of(9_000L));
        when(mockBankService.deposit(any(), any(), anyLong(), any()))
                .thenReturn(new BankTransactionOutcome.Success(sampleBank, sampleTx));
        bridge.useBalanceCap(island -> island.equals(islandId) ? 14_000L : Long.MAX_VALUE);

        AtomicReference<BankTransactionOutcome> full = new AtomicReference<>();
        bridge.depositToIslandBank(mockPlayer, profileId, 51L, nodeId, full::set);
        assertThat(full.get()).isInstanceOf(BankTransactionOutcome.AuthorityRejected.class);
        assertThat(((BankTransactionOutcome.AuthorityRejected) Objects.requireNonNull(full.get())).kind())
                .isEqualTo(BankTransactionOutcome.AuthorityRejected.Kind.BANK_FULL);
        verify(mockEconomy, never()).withdraw(eq(mockPlayer), anyDouble());

        AtomicReference<BankTransactionOutcome> fits = new AtomicReference<>();
        bridge.depositToIslandBank(mockPlayer, profileId, 50L, nodeId, fits::set);
        assertThat(fits.get()).describedAs("to the limit exactly").isInstanceOf(BankTransactionOutcome.Success.class);
        assertThat(com.uxplima.uxmskyblock.bukkit.command.BankRefusalLines.keyFor(
                        Objects.requireNonNull(full.get()), "x"))
                .isEqualTo("bank.full");
    }
}
