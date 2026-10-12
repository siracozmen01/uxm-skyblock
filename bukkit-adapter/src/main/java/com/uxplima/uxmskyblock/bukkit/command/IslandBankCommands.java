package com.uxplima.uxmskyblock.bukkit.command;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import org.bukkit.entity.Player;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.uxplima.uxmlib.command.Cmd;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.integration.economy.BankWallets;
import com.uxplima.uxmskyblock.bukkit.integration.economy.SkyblockEconomyBridge;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.core.application.bank.IslandBankService;
import com.uxplima.uxmskyblock.core.application.bank.IslandBankruptcyService;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.bank.BankTransactionOutcome;
import com.uxplima.uxmskyblock.core.domain.bank.BankruptcyRemediationResult;
import com.uxplima.uxmskyblock.core.domain.bank.BankruptcyStatus;
import com.uxplima.uxmskyblock.core.domain.bank.IslandBankruptcyRecord;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandPermission;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import org.jspecify.annotations.Nullable;

/**
 * Handles island bank and upkeep commands:
 * /is bank [balance|status|upkeep|paydebt|deposit|withdraw]
 */
public final class IslandBankCommands {

    /** Where a command named after a window opens it. */
    private CommandWindows windows = CommandWindows.none();

    void useWindows(CommandWindows windows) {
        this.windows = java.util.Objects.requireNonNull(windows, "windows must not be null");
    }

    /** What runs once a move lands, with the player and the currency it moved, {@code primary} for the money. */
    private java.util.function.BiConsumer<Player, String> moved = (player, currency) -> {};

    /** Hands the bank what to do once a move lands, which is drawing its windows again. */
    public void useMoved(java.util.function.BiConsumer<Player, String> moved) {
        this.moved = Objects.requireNonNull(moved, "moved must not be null");
    }

    /** The id the money goes by when a move of it lands. */
    static final String MONEY = "primary";

    /**
     * An amount as a player wrote it: a number, or all of what there is, which is read once the move knows where
     * from.
     */
    private record Amount(long units, boolean all) {}

    /**
     * {@code written} as an amount: {@code all}, or the reader's own word for it, or a number the bank reads.
     * Nothing for anything else.
     */
    private Optional<Amount> amountOf(Player player, String written) {
        String word = written.strip();
        if (word.equalsIgnoreCase("all") || word.equalsIgnoreCase(messages.words(player, "@bank.all_word"))) {
            return Optional.of(new Amount(0L, true));
        }
        java.util.OptionalLong read = BankAmount.parse(word);
        return read.isPresent() ? Optional.of(new Amount(read.getAsLong(), false)) : Optional.empty();
    }

    private final IslandBankService islandBankService;
    private final IslandLocationService islandLocationService;
    private final SkyblockEconomyBridge economyBridge;
    private final SchedulerPort schedulerPort;
    private final ServerNodeId serverNodeId;
    private final Supplier<@Nullable IslandBankruptcyService> bankruptcyServiceProvider;
    private final Messages messages;

    private final @Nullable PlayerSessionCoordinator sessionCoordinator;

    public IslandBankCommands(
            IslandBankService islandBankService,
            IslandLocationService islandLocationService,
            SkyblockEconomyBridge economyBridge,
            SchedulerPort schedulerPort,
            ServerNodeId serverNodeId,
            Supplier<@Nullable IslandBankruptcyService> bankruptcyServiceProvider,
            Messages messages,
            @Nullable PlayerSessionCoordinator sessionCoordinator) {
        this.islandBankService = Objects.requireNonNull(islandBankService, "islandBankService must not be null");
        this.islandLocationService =
                Objects.requireNonNull(islandLocationService, "islandLocationService must not be null");
        this.economyBridge = Objects.requireNonNull(economyBridge, "economyBridge must not be null");
        this.schedulerPort = Objects.requireNonNull(schedulerPort, "schedulerPort must not be null");
        this.serverNodeId = Objects.requireNonNull(serverNodeId, "serverNodeId must not be null");
        this.bankruptcyServiceProvider =
                Objects.requireNonNull(bankruptcyServiceProvider, "bankruptcyServiceProvider must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
        this.sessionCoordinator = sessionCoordinator;
    }

    public LiteralArgumentBuilder<CommandSourceStack> build() {
        return Cmd.literal("bank")
                .executes(ctx -> windows.openOr(ctx, "island-bank", this::executeBankBalance))
                .then(Cmd.literal("balance").executes(this::executeBankBalance))
                .then(Cmd.literal("status").executes(this::executeBankStatus))
                .then(Cmd.literal("upkeep").executes(this::executeBankStatus))
                .then(Cmd.literal("paydebt").executes(this::executeBankPayDebt))
                .then(Cmd.literal("deposit")
                        .then(Cmd.argument("amount", StringArgumentType.greedyString())
                                .executes(this::executeBankDeposit)))
                .then(Cmd.literal("withdraw")
                        .then(Cmd.argument("amount", StringArgumentType.greedyString())
                                .executes(this::executeBankWithdraw)));
    }

    private int executeBankBalance(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            return Cmd.OK;
        }

        PlayerUuid playerUuid = new PlayerUuid(player.getUniqueId());
        Optional<ProfileId> optProfile = activeProfile(player);
        if (optProfile.isEmpty()) {
            send(player, "error.session_not_active");
            return Cmd.OK;
        }
        ProfileId profileId = optProfile.get();

        schedulerPort.async(() -> {
            Optional<Long> optBalance = islandBankService.getBalanceMinorUnits(profileId);
            // Every other currency the bank keeps, under the money, so one line answers what the island has.
            Optional<com.uxplima.uxmskyblock.core.domain.bank.IslandBank> bank =
                    economyBridge.wallets().isPresent() ? islandBankService.findBank(profileId) : Optional.empty();
            schedulerPort.onEntity(playerUuid, () -> {
                if (optBalance.isEmpty()) {
                    send(player, "error.no_island");
                } else {
                    send(player, "bank.balance", Placeholder.unparsed("balance", money(optBalance.get())));
                    bank.ifPresent(held -> economyBridge.wallets().ifPresent(known -> {
                        for (BankWallets.Kept currency : known.currencies()) {
                            send(
                                    player,
                                    "bank.held_balance",
                                    Placeholder.unparsed(
                                            "currency",
                                            messages.words(
                                                    player, currency.spec().displayName())),
                                    Placeholder.unparsed(
                                            "amount",
                                            Long.toString(
                                                    held.heldOf(currency.spec().id()))));
                        }
                    }));
                }
            });
        });

        return Cmd.OK;
    }

    private int executeBankStatus(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            return Cmd.OK;
        }

        PlayerUuid playerUuid = new PlayerUuid(player.getUniqueId());
        Optional<ProfileId> optProfile = activeProfile(player);
        if (optProfile.isEmpty()) {
            send(player, "error.session_not_active");
            return Cmd.OK;
        }
        ProfileId profileId = optProfile.get();

        schedulerPort.async(() -> {
            Optional<IslandId> optIslandId = islandLocationService.findIslandId(profileId);
            if (optIslandId.isEmpty()) {
                schedulerPort.onEntity(playerUuid, () -> send(player, "error.no_island"));
                return;
            }
            IslandId islandId = optIslandId.get();
            IslandBankruptcyService bankruptcyService = bankruptcyServiceProvider.get();
            if (bankruptcyService == null) {
                schedulerPort.onEntity(playerUuid, () -> send(player, "bank.upkeep_disabled"));
                return;
            }

            // The record is read here, not inside the hop below. It was read on the entity thread,
            // and a bankruptcy record that is not already cached is a query: one under the cursor of
            // every player who ever typed /is upkeep.
            Instant now = Instant.now();
            IslandBankruptcyRecord record = bankruptcyService.getBankruptcyRecord(islandId, now);

            schedulerPort.onEntity(playerUuid, () -> {
                Component status = messages.renderPlain(
                        player, "bank.status_" + record.status().name().toLowerCase(java.util.Locale.ROOT));

                send(player, "bank.status_header");
                send(player, "bank.status_state", Placeholder.component("status", status));
                send(player, "bank.status_debt", Placeholder.unparsed("debt", money(record.debtMinorUnits())));

                if (record.status() == BankruptcyStatus.GRACE && record.graceUntil() != null) {
                    java.time.Duration remaining = java.time.Duration.between(now, record.graceUntil());
                    long hours = Math.max(0, remaining.toHours());
                    long minutes = Math.max(0, remaining.toMinutesPart());
                    send(
                            player,
                            "bank.status_grace_remaining",
                            Placeholder.unparsed("hours", Long.toString(hours)),
                            Placeholder.unparsed("minutes", Long.toString(minutes)));
                } else if (record.status() == BankruptcyStatus.LOCKED) {
                    send(player, "bank.status_locked_notice");
                    send(player, "bank.status_locked_hint");
                }
            });
        });

        return Cmd.OK;
    }

    private int executeBankPayDebt(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            return Cmd.OK;
        }

        PlayerUuid playerUuid = new PlayerUuid(player.getUniqueId());
        Optional<ProfileId> optProfile = activeProfile(player);
        if (optProfile.isEmpty()) {
            send(player, "error.session_not_active");
            return Cmd.OK;
        }
        ProfileId profileId = optProfile.get();

        schedulerPort.async(() -> {
            Optional<IslandId> optIslandId = islandLocationService.findIslandId(profileId);
            schedulerPort.onEntity(playerUuid, () -> {
                if (optIslandId.isEmpty()) {
                    send(player, "error.no_island");
                    return;
                }
                IslandBankruptcyService bankruptcyService = bankruptcyServiceProvider.get();
                if (bankruptcyService == null) {
                    send(player, "bank.upkeep_disabled");
                    return;
                }

                IslandId islandId = optIslandId.get();
                Instant now = Instant.now();
                BankruptcyRemediationResult result = bankruptcyService.settleArrears(islandId, now, serverNodeId);

                if (result instanceof BankruptcyRemediationResult.Settled settled) {
                    send(
                            player,
                            "bank.settled",
                            Placeholder.unparsed("amount", money(settled.amountPaid())),
                            Placeholder.unparsed("balance", money(settled.remainingBalance())));
                } else if (result instanceof BankruptcyRemediationResult.InsufficientFunds ins) {
                    send(
                            player,
                            "bank.settle_insufficient",
                            Placeholder.unparsed("debt", money(ins.debtAmount())),
                            Placeholder.unparsed("balance", money(ins.currentBalance())));
                } else if (result instanceof BankruptcyRemediationResult.PaymentRefused refused) {
                    send(player, "bank.settle_refused", Placeholder.unparsed("debt", money(refused.debtAmount())));
                } else if (result instanceof BankruptcyRemediationResult.NotInArrears) {
                    send(player, "bank.not_in_arrears");
                }
            });
        });

        return Cmd.OK;
    }

    private int executeBankDeposit(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            return Cmd.OK;
        }
        String written = StringArgumentType.getString(ctx, "amount");
        Optional<HeldMove> held = heldMove(written);
        if (held.isPresent()) {
            moveHeld(player, held.get(), true, IslandPermission.BANK_DEPOSIT);
            return Cmd.OK;
        }
        Optional<Amount> read = amountOf(player, written);
        if (read.isEmpty()) {
            send(player, "bank.amount_unreadable", Placeholder.unparsed("written", written));
            return Cmd.OK;
        }
        Optional<ProfileId> optProfile = activeProfile(player);
        if (optProfile.isEmpty()) {
            send(player, "error.session_not_active");
            return Cmd.OK;
        }
        ProfileId profileId = optProfile.get();

        ifTheRoleAllowsIt(player, profileId, IslandPermission.BANK_DEPOSIT, () -> {
            // All of it is what the wallet holds in whole money, read where the move runs.
            long amount = read.get().all()
                    ? (long) Math.floor(economyBridge.balance(player))
                    : read.get().units();
            if (amount <= 0) {
                send(player, "bank.nothing_to_move");
                return;
            }
            economyBridge.depositToIslandBank(player, profileId, amount, serverNodeId, outcome -> {
                if (outcome instanceof BankTransactionOutcome.Success) {
                    send(
                            player,
                            "bank.deposit_success",
                            Placeholder.unparsed(
                                    "amount", com.uxplima.uxmskyblock.bukkit.i18n.MoneyText.ofUnits(amount)));
                    moved.accept(player, MONEY);
                    IslandBankruptcyService bService = bankruptcyServiceProvider.get();
                    if (bService != null && bService.policy().autoRemediateOnDeposit()) {
                        // The bridge answers a refused deposit on the caller's own thread and a settled
                        // one off it, so which thread this outcome arrives on depends on the branch
                        // taken inside the bridge. Reading and settling arrears is two more round trips
                        // to the database either way, so it asks for the scheduler itself rather than
                        // trusting the thread it was handed.
                        schedulerPort.async(() -> islandLocationService
                                .findIslandId(profileId)
                                .ifPresent(islandId -> {
                                    BankruptcyRemediationResult rem =
                                            bService.settleArrears(islandId, Instant.now(), serverNodeId);
                                    if (rem instanceof BankruptcyRemediationResult.Settled settled) {
                                        send(
                                                player,
                                                "bank.auto_settled",
                                                Placeholder.unparsed("amount", money(settled.amountPaid())));
                                    }
                                }));
                    }
                } else if (outcome instanceof BankTransactionOutcome.InsufficientFunds) {
                    send(player, "bank.wallet_insufficient");
                } else {
                    send(player, BankRefusalLines.keyFor(outcome, "bank.deposit_wallet_refused"));
                }
            });
        });

        return Cmd.OK;
    }

    /**
     * Runs a money move only when the caller's role allows it.
     *
     * <p>The role editor has published a deposit permission and a withdraw permission since the
     * permission work and the bank read neither, so a member could empty the island bank whatever
     * their role said. The shipped member role does not hold the withdraw permission and has been
     * able to withdraw all along.
     *
     * <p>The role lives in a row, so it is read off the thread the command arrived on. The move runs
     * there too, because the move is the bridge and the bridge does its own storage work off the
     * thread as well: hopping back onto the player only to hop straight off again buys nothing and
     * costs a tick. The refusal is a message, and a message finds the player's thread by itself.
     *
     * <p>A caller with no island is not refused here: the move itself answers that, and it answers
     * it better.
     */
    private void ifTheRoleAllowsIt(Player player, ProfileId profileId, IslandPermission permission, Runnable move) {
        schedulerPort.async(() -> {
            Optional<Island> island =
                    islandLocationService.findIslandId(profileId).flatMap(islandLocationService::findIsland);
            if (island.isPresent()
                    && !island.get().isOwner(profileId)
                    && !island.get().hasPermission(profileId, permission)) {
                send(player, "bank.permission_denied");
                return;
            }
            move.run();
        });
    }

    private int executeBankWithdraw(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            return Cmd.OK;
        }
        String written = StringArgumentType.getString(ctx, "amount");
        Optional<HeldMove> held = heldMove(written);
        if (held.isPresent()) {
            moveHeld(player, held.get(), false, IslandPermission.BANK_WITHDRAW);
            return Cmd.OK;
        }
        Optional<Amount> read = amountOf(player, written);
        if (read.isEmpty()) {
            send(player, "bank.amount_unreadable", Placeholder.unparsed("written", written));
            return Cmd.OK;
        }
        Optional<ProfileId> optProfile = activeProfile(player);
        if (optProfile.isEmpty()) {
            send(player, "error.session_not_active");
            return Cmd.OK;
        }
        ProfileId profileId = optProfile.get();

        ifTheRoleAllowsIt(player, profileId, IslandPermission.BANK_WITHDRAW, () -> {
            // All of it is what the island holds in whole money.
            long amount = read.get().all()
                    ? islandBankService
                            .findBank(profileId)
                            .map(bank -> bank.primaryBalanceMinorUnits() / 100L)
                            .orElse(0L)
                    : read.get().units();
            if (amount <= 0) {
                send(player, "bank.nothing_to_move");
                return;
            }
            economyBridge.withdrawFromIslandBank(player, profileId, amount, serverNodeId, outcome -> {
                if (outcome instanceof BankTransactionOutcome.Success) {
                    send(
                            player,
                            "bank.withdraw_success",
                            Placeholder.unparsed(
                                    "amount", com.uxplima.uxmskyblock.bukkit.i18n.MoneyText.ofUnits(amount)));
                    moved.accept(player, MONEY);
                } else if (outcome instanceof BankTransactionOutcome.InsufficientFunds) {
                    send(player, "bank.withdraw_insufficient");
                } else {
                    send(player, BankRefusalLines.keyFor(outcome, "bank.withdraw_wallet_refused"));
                }
            });
        });

        return Cmd.OK;
    }

    /** An amount of one of the operator's currencies, as {@code 50 experience} names it. */
    private record HeldMove(BankWallets.Kept currency, String amount) {}

    /**
     * The currency a line ends with, and the amount before it, when it ends with one the bank keeps. A line that
     * names none is the island's own money, as it always was.
     */
    private Optional<HeldMove> heldMove(String written) {
        String line = written.strip();
        int space = line.lastIndexOf(' ');
        if (space < 0) {
            return Optional.empty();
        }
        Optional<BankWallets> wallets = economyBridge.wallets();
        String named = line.substring(space + 1);
        return wallets.flatMap(known -> known.currency(named))
                .map(currency -> new HeldMove(currency, line.substring(0, space).strip()));
    }

    /** Moves an amount of one of the operator's currencies in or out, when the caller's role allows it. */
    private void moveHeld(Player player, HeldMove move, boolean intoTheBank, IslandPermission permission) {
        String name = messages.words(player, move.currency().spec().displayName());
        Optional<Amount> read = amountOf(player, move.amount());
        if (read.isEmpty()) {
            send(player, "bank.amount_unreadable", Placeholder.unparsed("written", move.amount()));
            return;
        }
        Optional<ProfileId> optProfile = activeProfile(player);
        if (optProfile.isEmpty()) {
            send(player, "error.session_not_active");
            return;
        }
        ProfileId profileId = optProfile.get();
        String id = move.currency().spec().id();
        TagResolver currency = Placeholder.unparsed("currency", name);
        ifTheRoleAllowsIt(player, profileId, permission, () -> {
            // All of it is what the player carries going in, and what the island holds coming out.
            long units = !read.get().all()
                    ? read.get().units()
                    : intoTheBank
                            ? economyBridge
                                    .wallets()
                                    .flatMap(wallets -> wallets.carried(player.getUniqueId(), id))
                                    .orElse(0L)
                            : islandBankService
                                    .findBank(profileId)
                                    .map(bank -> bank.heldOf(id))
                                    .orElse(0L);
            if (units <= 0) {
                send(player, "bank.nothing_to_move");
                return;
            }
            TagResolver amount = Placeholder.unparsed("amount", Long.toString(units));
            economyBridge.moveHeld(player, profileId, id, units, intoTheBank, serverNodeId, outcome -> {
                if (outcome instanceof BankTransactionOutcome.Success) {
                    send(
                            player,
                            intoTheBank ? "bank.held_deposit_success" : "bank.held_withdraw_success",
                            amount,
                            currency);
                    moved.accept(player, id);
                } else if (outcome instanceof BankTransactionOutcome.InsufficientFunds poor) {
                    send(
                            player,
                            intoTheBank ? "bank.held_wallet_insufficient" : "bank.held_bank_insufficient",
                            Placeholder.unparsed("have", Long.toString(poor.currentBalance())),
                            currency);
                } else {
                    send(player, "bank.held_refused", currency);
                }
            });
        });
    }

    private Optional<ProfileId> activeProfile(Player player) {
        if (sessionCoordinator == null) {
            return Optional.empty();
        }
        return sessionCoordinator.activeProfile(player.getUniqueId());
    }

    private void send(Audience audience, String key, TagResolver... resolvers) {
        send(audience, messages.render(audience, key, resolvers));
    }

    /** Minor units are stored as an integer; a player reads them as money. */
    private static String money(long minorUnits) {
        return com.uxplima.uxmskyblock.bukkit.i18n.MoneyText.of(minorUnits);
    }

    private void send(Audience audience, Component component) {
        if (audience instanceof Player player) {
            schedulerPort.onEntity(new PlayerUuid(player.getUniqueId()), () -> {
                if (player.isOnline()) {
                    player.sendMessage(component);
                }
            });
        } else {
            audience.sendMessage(component);
        }
    }
}
