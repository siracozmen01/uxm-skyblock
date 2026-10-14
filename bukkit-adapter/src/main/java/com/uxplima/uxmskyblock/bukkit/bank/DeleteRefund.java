package com.uxplima.uxmskyblock.bukkit.bank;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;
import java.util.logging.Logger;

import com.uxplima.uxmskyblock.core.application.bank.IslandBankPort;
import com.uxplima.uxmskyblock.core.application.bank.IslandBankService;
import com.uxplima.uxmskyblock.core.domain.bank.BankTransactionOutcome;
import com.uxplima.uxmskyblock.core.domain.bank.IslandBank;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;

/**
 * Pays an island's owner back a share of the money in its bank when the island is deleted.
 *
 * <p>The share comes out of the bank first, keyed by the island so a retried delete takes it once, and then goes
 * into the owner's wallet. A wallet that will not take it puts it back into the bank, which the delete then clears
 * as it always did. With a share of zero nothing moves.
 */
public final class DeleteRefund implements Consumer<Island> {

    private static final Logger LOGGER = Logger.getLogger(DeleteRefund.class.getName());

    private final IslandBankPort banks;
    private final IslandBankService bank;
    private final DoubleSupplier share;
    private final BiPredicate<PlayerUuid, Long> wallet;
    private final ServerNodeId node;

    /**
     * @param share the share of the bank's money paid back, zero to one
     * @param wallet puts minor units into a player's own wallet, and says whether it took them
     */
    public DeleteRefund(
            IslandBankPort banks,
            IslandBankService bank,
            DoubleSupplier share,
            BiPredicate<PlayerUuid, Long> wallet,
            ServerNodeId node) {
        this.banks = Objects.requireNonNull(banks, "banks must not be null");
        this.bank = Objects.requireNonNull(bank, "bank must not be null");
        this.share = Objects.requireNonNull(share, "share must not be null");
        this.wallet = Objects.requireNonNull(wallet, "wallet must not be null");
        this.node = Objects.requireNonNull(node, "node must not be null");
    }

    @Override
    public void accept(Island island) {
        double part = Math.max(0.0, Math.min(1.0, share.getAsDouble()));
        long held = banks.findBankByIslandId(island.id())
                .map(IslandBank::primaryBalanceMinorUnits)
                .orElse(0L);
        long amount = (long) Math.floor(held * part);
        if (amount <= 0) {
            return;
        }
        PlayerUuid owner = island.ownerPlayerUuid();
        String key = "delete-refund-" + island.id().value();
        BankTransactionOutcome taken = move(island, owner, -amount, "Island deleted: refund to the owner", key);
        if (!IslandBankService.landed(taken)) {
            LOGGER.warning(() ->
                    "The delete refund for island " + island.id().value() + " could not leave the bank: " + taken);
            return;
        }
        if (!wallet.test(owner, amount)) {
            BankTransactionOutcome back =
                    move(island, owner, amount, "Island deleted: refund not taken", key + "-back");
            LOGGER.warning(() -> "The owner's wallet of island " + island.id().value() + " did not take the refund of "
                    + amount + " minor units; put back into the bank: " + back);
        }
    }

    private BankTransactionOutcome move(Island island, PlayerUuid owner, long delta, String reason, String key) {
        return bank.depositToIsland(
                island.id(),
                owner,
                delta,
                reason,
                node,
                UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)),
                key,
                "DELETE_REFUND");
    }
}
