package com.uxplima.uxmskyblock.bukkit.requirement;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.logging.Logger;

import org.bukkit.entity.Player;

import com.uxplima.uxmskyblock.core.application.bank.BankCurrencies;
import com.uxplima.uxmskyblock.core.application.bank.IslandBankPort;
import com.uxplima.uxmskyblock.core.application.bank.IslandBankService;
import com.uxplima.uxmskyblock.core.application.island.IslandAccessService;
import com.uxplima.uxmskyblock.core.domain.bank.BankTransactionOutcome;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.requirement.Requirement;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;

/**
 * The island's bank as requirement lines reach into it: its money and the currencies it keeps, spent only by a member
 * whose role may withdraw from it, each movement written down under the reason the game mode gives.
 */
public final class BankFunds implements LinePayment.IslandFunds {

    private static final Logger LOGGER = Logger.getLogger(BankFunds.class.getName());

    private final IslandBankPort bankPort;
    private final IslandBankService bank;
    private final IslandAccessService access;
    private final Function<UUID, Optional<ProfileId>> activeProfile;
    private final ServerNodeId node;
    private final String reason;

    /** @param reason what the bank's ledger says each movement was for, as {@code OneBlock phase} */
    public BankFunds(
            IslandBankPort bankPort,
            IslandBankService bank,
            IslandAccessService access,
            Function<UUID, Optional<ProfileId>> activeProfile,
            ServerNodeId node,
            String reason) {
        this.bankPort = Objects.requireNonNull(bankPort, "bankPort must not be null");
        this.bank = Objects.requireNonNull(bank, "bank must not be null");
        this.access = Objects.requireNonNull(access, "access must not be null");
        this.activeProfile = Objects.requireNonNull(activeProfile, "activeProfile must not be null");
        this.node = Objects.requireNonNull(node, "node must not be null");
        this.reason = Objects.requireNonNull(reason, "reason must not be null");
    }

    @Override
    public long held(IslandId islandId, Requirement line) {
        return bankPort.findBankByIslandId(islandId)
                .map(held -> line instanceof Requirement.Currency currency
                        ? held.heldOf(BankCurrencies.columnOf(currency.id()))
                        : held.primaryBalanceMinorUnits())
                .orElse(0L);
    }

    @Override
    public boolean take(IslandId islandId, Player payer, Requirement line) {
        return moved(islandId, payer, line, -1);
    }

    @Override
    public void giveBack(IslandId islandId, Player payer, Requirement line) {
        if (!moved(islandId, payer, line, 1)) {
            LOGGER.warning(() ->
                    "Giving back what " + reason + " took from the bank of island " + islandId + " failed: " + line);
        }
    }

    private boolean moved(IslandId islandId, Player payer, Requirement line, long sign) {
        String column;
        long amount;
        if (line instanceof Requirement.Currency currency) {
            column = BankCurrencies.columnOf(currency.id());
            amount = currency.amount();
        } else if (line instanceof Requirement.Money money) {
            column = BankCurrencies.PRIMARY_COLUMN;
            amount = money.minor();
        } else {
            return false;
        }
        return bank.move(islandId, new PlayerUuid(payer.getUniqueId()), column, sign * amount, reason, node)
                instanceof BankTransactionOutcome.Success;
    }

    @Override
    public boolean mayTake(Island island, Player payer) {
        return activeProfile
                .apply(payer.getUniqueId())
                .map(profile -> access.canWithdrawBank(island, profile))
                .orElse(false);
    }
}
