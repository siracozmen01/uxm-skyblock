package com.uxplima.uxmskyblock.bukkit.command;

import java.time.Instant;
import java.util.Optional;

import org.bukkit.entity.Player;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.uxplima.uxmlib.command.Cmd;
import com.uxplima.uxmskyblock.core.application.bank.IslandBankruptcyService;
import com.uxplima.uxmskyblock.core.domain.bank.BankTransactionOutcome;
import com.uxplima.uxmskyblock.core.domain.bank.BankruptcyRemediationResult;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.IslandPermission;

/**
 * {@code /is bank deposit} and {@code withdraw}: money or held currency moved between a player and the island's bank. Split out of {@link IslandBankCommands}, whose services and words it uses, so neither file is over the size the
 * standards draw a line at.
 */
final class BankMoveVerbs {

    private final IslandBankCommands host;

    BankMoveVerbs(IslandBankCommands host) {
        this.host = host;
    }

    int executeBankDeposit(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            return Cmd.OK;
        }
        String written = StringArgumentType.getString(ctx, "amount");
        Optional<IslandBankCommands.HeldMove> held = host.heldMove(written);
        if (held.isPresent()) {
            moveHeld(player, held.get(), true, IslandPermission.BANK_DEPOSIT);
            return Cmd.OK;
        }
        Optional<IslandBankCommands.Amount> read = host.amountOf(player, written);
        if (read.isEmpty()) {
            host.send(player, "bank.amount_unreadable", Placeholder.unparsed("written", written));
            return Cmd.OK;
        }
        Optional<ProfileId> optProfile = host.activeProfile(player);
        if (optProfile.isEmpty()) {
            host.send(player, "error.session_not_active");
            return Cmd.OK;
        }
        ProfileId profileId = optProfile.get();

        host.ifTheRoleAllowsIt(player, profileId, IslandPermission.BANK_DEPOSIT, () -> {
            // All of it is what the wallet holds in whole money, read where the move runs.
            long amount = read.get().all()
                    ? (long) Math.floor(host.economyBridge.balance(player))
                    : read.get().units();
            if (amount <= 0) {
                host.send(player, "bank.nothing_to_move");
                return;
            }
            host.economyBridge.depositToIslandBank(player, profileId, amount, host.serverNodeId, outcome -> {
                if (outcome instanceof BankTransactionOutcome.Success) {
                    host.send(
                            player,
                            "bank.deposit_success",
                            Placeholder.unparsed(
                                    "amount", com.uxplima.uxmskyblock.bukkit.i18n.MoneyText.ofUnits(amount)));
                    host.moved.accept(player, IslandBankCommands.MONEY);
                    IslandBankruptcyService bService = host.bankruptcyServiceProvider.get();
                    if (bService != null && bService.policy().autoRemediateOnDeposit()) {
                        // The bridge answers a refused deposit on the caller's own thread and a settled
                        // one off it, so which thread this outcome arrives on depends on the branch
                        // taken inside the bridge. Reading and settling arrears is two more round trips
                        // to the database either way, so it asks for the scheduler itself rather than
                        // trusting the thread it was handed.
                        host.schedulerPort.async(() -> host.islandLocationService
                                .findIslandId(profileId)
                                .ifPresent(islandId -> {
                                    BankruptcyRemediationResult rem =
                                            bService.settleArrears(islandId, Instant.now(), host.serverNodeId);
                                    if (rem instanceof BankruptcyRemediationResult.Settled settled) {
                                        host.send(
                                                player,
                                                "bank.auto_settled",
                                                Placeholder.unparsed(
                                                        "amount", IslandBankCommands.money(settled.amountPaid())));
                                    }
                                }));
                    }
                } else if (outcome instanceof BankTransactionOutcome.InsufficientFunds) {
                    host.send(player, "bank.wallet_insufficient");
                } else {
                    host.send(player, BankRefusalLines.keyFor(outcome, "bank.deposit_wallet_refused"));
                }
            });
        });

        return Cmd.OK;
    }

    int executeBankWithdraw(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            return Cmd.OK;
        }
        String written = StringArgumentType.getString(ctx, "amount");
        Optional<IslandBankCommands.HeldMove> held = host.heldMove(written);
        if (held.isPresent()) {
            moveHeld(player, held.get(), false, IslandPermission.BANK_WITHDRAW);
            return Cmd.OK;
        }
        Optional<IslandBankCommands.Amount> read = host.amountOf(player, written);
        if (read.isEmpty()) {
            host.send(player, "bank.amount_unreadable", Placeholder.unparsed("written", written));
            return Cmd.OK;
        }
        Optional<ProfileId> optProfile = host.activeProfile(player);
        if (optProfile.isEmpty()) {
            host.send(player, "error.session_not_active");
            return Cmd.OK;
        }
        ProfileId profileId = optProfile.get();

        host.ifTheRoleAllowsIt(player, profileId, IslandPermission.BANK_WITHDRAW, () -> {
            // All of it is what the island holds in whole money.
            long amount = read.get().all()
                    ? host.islandBankService
                            .findBank(profileId)
                            .map(bank -> bank.primaryBalanceMinorUnits() / 100L)
                            .orElse(0L)
                    : read.get().units();
            if (amount <= 0) {
                host.send(player, "bank.nothing_to_move");
                return;
            }
            host.economyBridge.withdrawFromIslandBank(player, profileId, amount, host.serverNodeId, outcome -> {
                if (outcome instanceof BankTransactionOutcome.Success) {
                    host.send(
                            player,
                            "bank.withdraw_success",
                            Placeholder.unparsed(
                                    "amount", com.uxplima.uxmskyblock.bukkit.i18n.MoneyText.ofUnits(amount)));
                    host.moved.accept(player, IslandBankCommands.MONEY);
                } else if (outcome instanceof BankTransactionOutcome.InsufficientFunds) {
                    host.send(player, "bank.withdraw_insufficient");
                } else {
                    host.send(player, BankRefusalLines.keyFor(outcome, "bank.withdraw_wallet_refused"));
                }
            });
        });

        return Cmd.OK;
    }
    /** Moves an amount of one of the operator's currencies in or out, when the caller's role allows it. */
    void moveHeld(Player player, IslandBankCommands.HeldMove move, boolean intoTheBank, IslandPermission permission) {
        String name = host.messages.words(player, move.currency().spec().displayName());
        Optional<IslandBankCommands.Amount> read = host.amountOf(player, move.amount());
        if (read.isEmpty()) {
            host.send(player, "bank.amount_unreadable", Placeholder.unparsed("written", move.amount()));
            return;
        }
        Optional<ProfileId> optProfile = host.activeProfile(player);
        if (optProfile.isEmpty()) {
            host.send(player, "error.session_not_active");
            return;
        }
        ProfileId profileId = optProfile.get();
        String id = move.currency().spec().id();
        TagResolver currency = Placeholder.unparsed("currency", name);
        host.ifTheRoleAllowsIt(player, profileId, permission, () -> {
            // All of it is what the player carries going in, and what the island holds coming out.
            long units = !read.get().all()
                    ? read.get().units()
                    : intoTheBank
                            ? host.economyBridge
                                    .wallets()
                                    .flatMap(wallets -> wallets.carried(player.getUniqueId(), id))
                                    .orElse(0L)
                            : host.islandBankService
                                    .findBank(profileId)
                                    .map(bank -> bank.heldOf(id))
                                    .orElse(0L);
            if (units <= 0) {
                host.send(player, "bank.nothing_to_move");
                return;
            }
            TagResolver amount = Placeholder.unparsed("amount", Long.toString(units));
            host.economyBridge.moveHeld(player, profileId, id, units, intoTheBank, host.serverNodeId, outcome -> {
                if (outcome instanceof BankTransactionOutcome.Success) {
                    host.send(
                            player,
                            intoTheBank ? "bank.held_deposit_success" : "bank.held_withdraw_success",
                            amount,
                            currency);
                    host.moved.accept(player, id);
                } else if (outcome instanceof BankTransactionOutcome.InsufficientFunds poor) {
                    host.send(
                            player,
                            intoTheBank ? "bank.held_wallet_insufficient" : "bank.held_bank_insufficient",
                            Placeholder.unparsed("have", Long.toString(poor.currentBalance())),
                            currency);
                } else {
                    host.send(player, "bank.held_refused", currency);
                }
            });
        });
    }
}
