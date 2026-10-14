package com.uxplima.uxmskyblock.bukkit.integration.economy;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;

import com.uxplima.uxmlib.hook.economy.EconomyBridge;
import com.uxplima.uxmlib.hook.economy.EconomyServiceListener;
import com.uxplima.uxmlib.hook.economy.RebindingEconomyBridge;
import com.uxplima.uxmskyblock.core.application.bank.IslandBankService;
import com.uxplima.uxmskyblock.core.application.economy.EconomySagaCoordinator;
import com.uxplima.uxmskyblock.core.application.economy.EconomySagaPort;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.bank.BankTransactionOutcome;
import com.uxplima.uxmskyblock.core.domain.economy.SagaId;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import org.jspecify.annotations.Nullable;

/**
 * High-reliability economy bridge orchestrating two-phase compensating transfers between
 * player personal wallets (via Vault/VaultUnlocked through {@link EconomyBridge}) and
 * distributed {@link IslandBankService} backed by durable write-ahead sagas.
 */
public final class SkyblockEconomyBridge {

    private static final Logger LOGGER = Logger.getLogger(SkyblockEconomyBridge.class.getName());

    private final EconomyBridge economyBridge;
    private final IslandBankService bankService;

    /** The most an island bank may hold, in minor units; no limit until the wiring hands one over. */
    private volatile java.util.function.ToLongFunction<IslandId> balanceCap = island -> Long.MAX_VALUE;

    private final SchedulerPort schedulerPort;
    private final @Nullable EconomySagaCoordinator sagaCoordinator;
    private final @Nullable BankWallets wallets;

    public SkyblockEconomyBridge(
            EconomyBridge economyBridge,
            IslandBankService bankService,
            SchedulerPort schedulerPort,
            @Nullable EconomySagaCoordinator sagaCoordinator) {
        this(economyBridge, bankService, schedulerPort, sagaCoordinator, null);
    }

    /** A bridge that also moves the currencies the operator lists, through {@code wallets}. */
    public SkyblockEconomyBridge(
            EconomyBridge economyBridge,
            IslandBankService bankService,
            SchedulerPort schedulerPort,
            @Nullable EconomySagaCoordinator sagaCoordinator,
            @Nullable BankWallets wallets) {
        this.economyBridge = Objects.requireNonNull(economyBridge, "economyBridge must not be null");
        this.bankService = Objects.requireNonNull(bankService, "bankService must not be null");
        this.schedulerPort = Objects.requireNonNull(schedulerPort, "schedulerPort must not be null");
        this.sagaCoordinator = sagaCoordinator;
        this.wallets = wallets;
    }

    public SkyblockEconomyBridge(
            EconomyBridge economyBridge, IslandBankService bankService, SchedulerPort schedulerPort) {
        this(economyBridge, bankService, schedulerPort, null);
    }

    public static SkyblockEconomyBridge createDefault(
            IslandBankService bankService, SchedulerPort schedulerPort, @Nullable EconomySagaPort sagaPort) {
        return createDefault(bankService, schedulerPort, sagaPort, java.util.List.of());
    }

    /**
     * The bridge a server runs: the island's own money through its economy, and every currency of
     * {@code currencies} through the wallet its type names, each move a saga that a crash can finish.
     */
    public static SkyblockEconomyBridge createDefault(
            IslandBankService bankService,
            SchedulerPort schedulerPort,
            @Nullable EconomySagaPort sagaPort,
            java.util.List<com.uxplima.uxmskyblock.bukkit.config.BankCurrencySpec> currencies) {
        // Rebinding rather than resolved once. An economy plugin that registers its service after this
        // one enables, which is every economy plugin once this one loads at startup, used to leave the
        // bridge on the dummy for as long as the server ran and the island bank refusing every move.
        EconomyBridge bridge = new RebindingEconomyBridge();
        EconomySagaCoordinator coordinator = null;
        BankWallets wallets = null;
        if (sagaPort != null) {
            BukkitVaultWalletAdapter walletAdapter = new BukkitVaultWalletAdapter(bridge);
            wallets = BankWallets.ofServer(walletAdapter, schedulerPort, currencies, "uxmSkyblock");
            coordinator = new EconomySagaCoordinator(sagaPort, wallets, bankService, Duration.ofSeconds(30));
        }
        return new SkyblockEconomyBridge(bridge, bankService, schedulerPort, coordinator, wallets);
    }

    /** The currencies the bank keeps beside the island's own money, or nothing on a bridge without them. */
    public Optional<BankWallets> wallets() {
        return Optional.ofNullable(wallets);
    }

    /**
     * Moves {@code units} of the operator's currency {@code currency} between the player and the island bank: in
     * when {@code intoTheBank}, out otherwise. Each move is a saga, so a crash between the wallet and the bank is
     * finished or undone on the next start. The answer comes back on the player's thread.
     */
    public void moveHeld(
            Player player,
            ProfileId profileId,
            String currency,
            long units,
            boolean intoTheBank,
            ServerNodeId nodeId,
            Consumer<BankTransactionOutcome> callback) {
        Objects.requireNonNull(player, "player must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        Objects.requireNonNull(callback, "callback must not be null");
        PlayerUuid playerUuid = new PlayerUuid(player.getUniqueId());
        EconomySagaCoordinator coordinator = this.sagaCoordinator;
        BankWallets known = this.wallets;
        if (units <= 0) {
            callback.accept(new BankTransactionOutcome.AuthorityRejected(
                    BankTransactionOutcome.AuthorityRejected.Kind.INVALID_AMOUNT, "The amount must be positive."));
            return;
        }
        if (coordinator == null || known == null || known.currency(currency).isEmpty()) {
            callback.accept(new BankTransactionOutcome.AuthorityRejected(
                    BankTransactionOutcome.AuthorityRejected.Kind.WALLET_REFUSED,
                    "The bank keeps no currency called " + currency + "."));
            return;
        }
        schedulerPort.async(() -> {
            BankTransactionOutcome outcome;
            Optional<IslandId> island = bankService.findIslandIdByProfileId(profileId);
            if (island.isEmpty()) {
                outcome = new BankTransactionOutcome.AuthorityRejected(
                        BankTransactionOutcome.AuthorityRejected.Kind.NO_ISLAND,
                        "No island associated with profile " + profileId);
            } else if (intoTheBank) {
                long carried = known.carried(player.getUniqueId(), currency).orElse(0L);
                outcome = carried < units
                        ? new BankTransactionOutcome.InsufficientFunds(carried, units)
                        : coordinator.executeDeposit(
                                SagaId.random(),
                                playerUuid,
                                profileId,
                                island.get(),
                                units,
                                currency,
                                nodeId,
                                Instant.now());
            } else {
                outcome = coordinator.executeWithdraw(
                        SagaId.random(), playerUuid, profileId, island.get(), units, currency, nodeId, Instant.now());
            }
            BankTransactionOutcome answered = outcome;
            schedulerPort.onEntity(playerUuid, () -> callback.accept(answered));
        });
    }

    public static SkyblockEconomyBridge createDefault(IslandBankService bankService, SchedulerPort schedulerPort) {
        return createDefault(bankService, schedulerPort, null);
    }

    /**
     * The listener that keeps the economy current as economy plugins register and unregister, or
     * nothing when this bridge was handed a fixed economy.
     */
    public Optional<Listener> economyRebinder() {
        return economyBridge instanceof RebindingEconomyBridge rebinding
                ? Optional.of(new EconomyServiceListener(rebinding))
                : Optional.empty();
    }

    public boolean isEconomyAvailable() {
        return economyBridge.isPresent();
    }

    public double balance(OfflinePlayer player) {
        return economyBridge.balance(player);
    }

    public String format(double amount) {
        return economyBridge.format(amount);
    }

    public boolean depositWallet(OfflinePlayer player, double amount) {
        if (!economyBridge.isPresent()) {
            return false;
        }
        return economyBridge.deposit(player, amount);
    }

    /** Takes {@code amount} from the player's own money, false when the economy is away or they hold less. */
    public boolean withdrawWallet(OfflinePlayer player, double amount) {
        if (!economyBridge.isPresent()) {
            return false;
        }
        return economyBridge.withdraw(player, amount);
    }

    public int recoverPendingSagas(ServerNodeId nodeId) {
        if (sagaCoordinator != null) {
            return sagaCoordinator.recoverIncompleteSagas(Instant.now(), nodeId);
        }
        return 0;
    }

    /**
     * Settles abandoned sagas once every saga timeout, for as long as the server runs.
     *
     * <p>Recovery ran once, at startup. A move the database never answered left its saga waiting for
     * the next restart, with the player's money in neither place they could see. A saga is abandoned
     * once its timeout has passed, so looking that often finds each one within two timeouts.
     */
    public AutoCloseable keepRecoveringSagas(ServerNodeId nodeId) {
        EconomySagaCoordinator coordinator = this.sagaCoordinator;
        if (coordinator == null) {
            return () -> {};
        }
        return schedulerPort.repeatAsync(
                () -> {
                    try {
                        coordinator.recoverIncompleteSagas(Instant.now(), nodeId);
                    } catch (RuntimeException e) {
                        LOGGER.log(
                                Level.WARNING, "Settling abandoned economy sagas failed; the next turn tries again", e);
                    }
                },
                coordinator.sagaTimeout(),
                coordinator.sagaTimeout());
    }

    /** Holds every island bank to at most {@code cap} of money, in minor units, against a player's deposit. */
    public void useBalanceCap(java.util.function.ToLongFunction<IslandId> cap) {
        this.balanceCap = Objects.requireNonNull(cap, "cap must not be null");
    }

    /**
     * Deposits money from player's wallet into the island bank via durable two-phase saga.
     * If bank mutation fails, wallet deduction is compensated and refunded immediately.
     */
    public void depositToIslandBank(
            Player player,
            ProfileId profileId,
            long dollars,
            ServerNodeId nodeId,
            Consumer<BankTransactionOutcome> callback) {
        Objects.requireNonNull(player, "player must not be null");
        Objects.requireNonNull(profileId, "profileId must not be null");
        Objects.requireNonNull(nodeId, "nodeId must not be null");
        Objects.requireNonNull(callback, "callback must not be null");

        if (dollars <= 0) {
            callback.accept(new BankTransactionOutcome.AuthorityRejected(
                    BankTransactionOutcome.AuthorityRejected.Kind.INVALID_AMOUNT,
                    "Deposit amount must be greater than zero."));
            return;
        }

        // With no economy plugin there is no wallet. The wallet adapter used to answer every
        // question about it with yes, so a deposit charged nothing and filled the island bank
        // without limit, and a withdrawal emptied it into nothing and called that a success.
        if (!economyBridge.isPresent()) {
            callback.accept(new BankTransactionOutcome.AuthorityRejected(
                    BankTransactionOutcome.AuthorityRejected.Kind.NO_ECONOMY,
                    "Deposit refused: no economy plugin is installed."));
            return;
        }

        PlayerUuid playerUuid = new PlayerUuid(player.getUniqueId());
        long minorUnits = dollars * 100L;

        if (economyBridge.isPresent() && !economyBridge.has(player, (double) dollars)) {
            callback.accept(new BankTransactionOutcome.InsufficientFunds(
                    (long) (economyBridge.balance(player) * 100L), minorUnits));
            return;
        }

        schedulerPort.async(() -> {
            Optional<IslandId> optIsland = bankService.findIslandIdByProfileId(profileId);
            if (optIsland.isEmpty()) {
                BankTransactionOutcome outcome = new BankTransactionOutcome.AuthorityRejected(
                        BankTransactionOutcome.AuthorityRejected.Kind.NO_ISLAND,
                        "No island associated with profile " + profileId);
                schedulerPort.onEntity(playerUuid, () -> callback.accept(outcome));
                return;
            }

            long cap = balanceCap.applyAsLong(optIsland.get());
            if (cap != Long.MAX_VALUE
                    && bankService.getBalanceMinorUnits(profileId).orElse(0L) + minorUnits > cap) {
                BankTransactionOutcome full = new BankTransactionOutcome.AuthorityRejected(
                        BankTransactionOutcome.AuthorityRejected.Kind.BANK_FULL,
                        "Deposit refused: the island bank holds at most " + cap + " minor units.");
                schedulerPort.onEntity(playerUuid, () -> callback.accept(full));
                return;
            }

            BankTransactionOutcome outcome;
            if (sagaCoordinator != null) {
                SagaId sagaId = SagaId.random();
                outcome = sagaCoordinator.executeDeposit(
                        sagaId, playerUuid, profileId, optIsland.get(), minorUnits, "VAULT", nodeId, Instant.now());
            } else {
                if (economyBridge.isPresent()) {
                    boolean withdrawn = economyBridge.withdraw(player, (double) dollars);
                    if (!withdrawn) {
                        outcome = new BankTransactionOutcome.AuthorityRejected(
                                BankTransactionOutcome.AuthorityRejected.Kind.WALLET_REFUSED,
                                "Failed to withdraw funds from your wallet.");
                    } else {
                        outcome = bankService.deposit(profileId, playerUuid, minorUnits, nodeId);
                        if (!(outcome instanceof BankTransactionOutcome.Success)) {
                            // The wallet has already been charged. If the refund does not land the
                            // player has paid for nothing, so the answer cannot be the bank's
                            // refusal: it has to say where the money went.
                            boolean refunded = economyBridge.deposit(player, (double) dollars);
                            if (!refunded) {
                                LOGGER.log(
                                        Level.SEVERE,
                                        "Wallet refund failed after a rejected island bank deposit."
                                                + " player={0} amountMinorUnits={1} bankOutcome={2}",
                                        new Object[] {playerUuid, minorUnits, outcome});
                                outcome = new BankTransactionOutcome.AuthorityRejected(
                                        BankTransactionOutcome.AuthorityRejected.Kind.REFUND_FAILED,
                                        "The island bank refused the deposit and your wallet could not be"
                                                + " refunded. Contact an administrator with the time of this"
                                                + " message.");
                            }
                        }
                    }
                } else {
                    outcome = bankService.deposit(profileId, playerUuid, minorUnits, nodeId);
                }
            }

            final BankTransactionOutcome finalOutcome = outcome;
            schedulerPort.onEntity(playerUuid, () -> callback.accept(finalOutcome));
        });
    }

    /**
     * Withdraws money from the island bank into the player's personal wallet via durable two-phase saga.
     * If wallet deposit fails, island bank deduction is compensated and refunded immediately.
     */
    public void withdrawFromIslandBank(
            Player player,
            ProfileId profileId,
            long dollars,
            ServerNodeId nodeId,
            Consumer<BankTransactionOutcome> callback) {
        Objects.requireNonNull(player, "player must not be null");
        Objects.requireNonNull(profileId, "profileId must not be null");
        Objects.requireNonNull(nodeId, "nodeId must not be null");
        Objects.requireNonNull(callback, "callback must not be null");

        if (dollars <= 0) {
            callback.accept(new BankTransactionOutcome.AuthorityRejected(
                    BankTransactionOutcome.AuthorityRejected.Kind.INVALID_AMOUNT,
                    "Withdraw amount must be greater than zero."));
            return;
        }

        // With no economy plugin there is no wallet. The wallet adapter used to answer every
        // question about it with yes, so a deposit charged nothing and filled the island bank
        // without limit, and a withdrawal emptied it into nothing and called that a success.
        if (!economyBridge.isPresent()) {
            callback.accept(new BankTransactionOutcome.AuthorityRejected(
                    BankTransactionOutcome.AuthorityRejected.Kind.NO_ECONOMY,
                    "Withdraw refused: no economy plugin is installed."));
            return;
        }

        PlayerUuid playerUuid = new PlayerUuid(player.getUniqueId());
        long minorUnits = dollars * 100L;

        schedulerPort.async(() -> {
            Optional<IslandId> optIsland = bankService.findIslandIdByProfileId(profileId);
            if (optIsland.isEmpty()) {
                BankTransactionOutcome outcome = new BankTransactionOutcome.AuthorityRejected(
                        BankTransactionOutcome.AuthorityRejected.Kind.NO_ISLAND,
                        "No island associated with profile " + profileId);
                schedulerPort.onEntity(playerUuid, () -> callback.accept(outcome));
                return;
            }

            BankTransactionOutcome outcome;
            if (sagaCoordinator != null) {
                SagaId sagaId = SagaId.random();
                outcome = sagaCoordinator.executeWithdraw(
                        sagaId, playerUuid, profileId, optIsland.get(), minorUnits, "VAULT", nodeId, Instant.now());
            } else {
                outcome = bankService.withdraw(profileId, playerUuid, minorUnits, nodeId);
                if (outcome instanceof BankTransactionOutcome.Success && economyBridge.isPresent()) {
                    boolean deposited = economyBridge.deposit(player, (double) dollars);
                    if (!deposited) {
                        // The island bank has already been debited. Putting it back can fail too, and
                        // this used to throw that answer away and tell the player it had worked: the
                        // money was gone from the bank, never in their wallet, and the line on screen
                        // said it had been refunded.
                        BankTransactionOutcome refund = bankService.deposit(profileId, playerUuid, minorUnits, nodeId);
                        if (refund instanceof BankTransactionOutcome.Success) {
                            outcome = new BankTransactionOutcome.AuthorityRejected(
                                    BankTransactionOutcome.AuthorityRejected.Kind.WALLET_REFUSED,
                                    "Failed to deposit funds into your wallet. Island bank funds refunded.");
                        } else {
                            LOGGER.log(
                                    Level.SEVERE,
                                    "Island bank refund failed after a wallet deposit that would not land."
                                            + " player={0} amountMinorUnits={1} refundOutcome={2}",
                                    new Object[] {playerUuid, minorUnits, refund});
                            outcome = new BankTransactionOutcome.AuthorityRejected(
                                    BankTransactionOutcome.AuthorityRejected.Kind.REFUND_FAILED,
                                    "Your wallet would not take the funds and the island bank could not be"
                                            + " refunded. Contact an administrator with the time of this"
                                            + " message.");
                        }
                    }
                }
            }

            final BankTransactionOutcome finalOutcome = outcome;
            schedulerPort.onEntity(playerUuid, () -> callback.accept(finalOutcome));
        });
    }
}
