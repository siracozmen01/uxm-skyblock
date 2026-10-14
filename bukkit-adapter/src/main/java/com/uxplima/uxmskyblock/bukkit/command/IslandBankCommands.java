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

    /** {@code /is bank deposit} and {@code withdraw}: money or held currency moved between a player and the island's bank. */
    private final BankMoveVerbs moves = new BankMoveVerbs(this);

    /** Where a command named after a window opens it. */
    private CommandWindows windows = CommandWindows.none();

    void useWindows(CommandWindows windows) {
        this.windows = java.util.Objects.requireNonNull(windows, "windows must not be null");
    }

    /** What runs once a move lands, with the player and the currency it moved, {@code primary} for the money. */
    java.util.function.BiConsumer<Player, String> moved = (player, currency) -> {};

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
    record Amount(long units, boolean all) {}

    /**
     * {@code written} as an amount: {@code all}, or the reader's own word for it, or a number the bank reads.
     * Nothing for anything else.
     */
    Optional<Amount> amountOf(Player player, String written) {
        String word = written.strip();
        if (word.equalsIgnoreCase("all") || word.equalsIgnoreCase(messages.words(player, "@bank.all_word"))) {
            return Optional.of(new Amount(0L, true));
        }
        java.util.OptionalLong read = BankAmount.parse(word);
        return read.isPresent() ? Optional.of(new Amount(read.getAsLong(), false)) : Optional.empty();
    }

    final IslandBankService islandBankService;
    final IslandLocationService islandLocationService;
    final SkyblockEconomyBridge economyBridge;
    final SchedulerPort schedulerPort;
    final ServerNodeId serverNodeId;
    final Supplier<@Nullable IslandBankruptcyService> bankruptcyServiceProvider;
    final Messages messages;

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
                                .executes(moves::executeBankDeposit)))
                .then(Cmd.literal("withdraw")
                        .then(Cmd.argument("amount", StringArgumentType.greedyString())
                                .executes(moves::executeBankWithdraw)));
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
    void ifTheRoleAllowsIt(Player player, ProfileId profileId, IslandPermission permission, Runnable move) {
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

    /** An amount of one of the operator's currencies, as {@code 50 experience} names it. */
    record HeldMove(BankWallets.Kept currency, String amount) {}

    /**
     * The currency a line ends with, and the amount before it, when it ends with one the bank keeps. A line that
     * names none is the island's own money, as it always was.
     */
    Optional<HeldMove> heldMove(String written) {
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

    Optional<ProfileId> activeProfile(Player player) {
        if (sessionCoordinator == null) {
            return Optional.empty();
        }
        return sessionCoordinator.activeProfile(player.getUniqueId());
    }

    void send(Audience audience, String key, TagResolver... resolvers) {
        send(audience, messages.render(audience, key, resolvers));
    }

    /** Minor units are stored as an integer; a player reads them as money. */
    static String money(long minorUnits) {
        return com.uxplima.uxmskyblock.bukkit.i18n.MoneyText.of(minorUnits);
    }

    void send(Audience audience, Component component) {
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
