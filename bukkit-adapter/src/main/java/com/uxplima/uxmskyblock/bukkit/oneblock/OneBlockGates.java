package com.uxplima.uxmskyblock.bukkit.oneblock;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.entity.Player;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import com.uxplima.uxmskyblock.bukkit.chunkblock.ChunkPurse;
import com.uxplima.uxmskyblock.bukkit.config.OneBlockConfiguration;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.core.application.oneblock.OneBlockService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkRequirement;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.requirement.Requirement;
import org.jspecify.annotations.Nullable;

/**
 * The lines a OneBlock phase asks before it begins. An island at the end of the phase before waits there, and each
 * break of its block asks the lines again: the island's level and bank off the region threads, then the breaker's
 * permission, money, currency and items on their own thread. When every line holds, what is to be taken is taken,
 * from the breaker first and then from the island's bank, given back whole if any part cannot be, and the phase
 * begins. The wait is stored, so the island's standing reads it after a restart.
 */
public final class OneBlockGates {

    private static final Logger LOGGER = Logger.getLogger(OneBlockGates.class.getName());

    /** The island's bank, as a phase's lines reach into it. Every call runs off the region threads. */
    public interface IslandFunds {
        /** How much of {@code line} the island's bank holds, in the line's own units. */
        long held(IslandId islandId, Requirement line);

        /** Takes {@code line} out of the island's bank, and answers whether it was. */
        boolean take(IslandId islandId, Player payer, Requirement line);

        /** Puts {@code line} back into the island's bank. */
        void giveBack(IslandId islandId, Player payer, Requirement line);

        /** Whether {@code payer}'s role lets them spend out of the island's bank. */
        boolean mayTake(Island island, Player payer);
    }

    private final Supplier<OneBlockConfiguration> config;
    private final OneBlockService service;
    private final SchedulerPort scheduler;
    private final Messages messages;
    private final ChunkPurse purse;
    private final IslandFunds funds;
    private final BiFunction<Island, Player, Long> levelOf;
    private final Set<IslandId> asking = ConcurrentHashMap.newKeySet();

    /** @param levelOf the island's level as the level command works it out, read off the region threads */
    public OneBlockGates(
            Supplier<OneBlockConfiguration> config,
            OneBlockService service,
            SchedulerPort scheduler,
            Messages messages,
            ChunkPurse purse,
            IslandFunds funds,
            BiFunction<Island, Player, Long> levelOf) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.service = Objects.requireNonNull(service, "service must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
        this.purse = Objects.requireNonNull(purse, "purse must not be null");
        this.funds = Objects.requireNonNull(funds, "funds must not be null");
        this.levelOf = Objects.requireNonNull(levelOf, "levelOf must not be null");
    }

    /** Whether the phase asks any line before it begins. */
    public boolean asks(String phase) {
        return !config.get().gateOf(phase).requires().isEmpty();
    }

    /**
     * Asks the lines of {@code phase} for the island, the breaker paying what is taken, and runs {@code opened} once
     * every line held and was paid. An island already being asked is not asked twice at once.
     */
    public void ask(Island island, Player player, String phase, Runnable opened) {
        IslandId islandId = island.id();
        if (!asking.add(islandId)) {
            return;
        }
        List<Requirement> lines = config.get().gateOf(phase).requires();
        if (service.waiting(islandId).isEmpty() && service.waitBefore(islandId, phase, null)) {
            scheduler.async(() -> service.storeWait(islandId));
        }
        scheduler.async(() -> {
            try {
                List<Requirement> missing = ofTheIsland(island, player, lines);
                scheduler.onEntity(
                        player.getUniqueId(),
                        () -> ofThePlayer(island, player, phase, lines, missing, opened),
                        () -> asking.remove(islandId));
            } catch (RuntimeException failed) {
                asking.remove(islandId);
                LOGGER.log(Level.WARNING, failed, () -> "Asking the lines of OneBlock phase " + phase + " failed.");
            }
        });
    }

    /** The island's lines that do not hold: its level, and what its bank holds. Off the region threads. */
    private List<Requirement> ofTheIsland(Island island, Player player, List<Requirement> lines) {
        List<Requirement> missing = new ArrayList<>();
        Long level = null;
        for (Requirement line : lines) {
            switch (line) {
                case Requirement.Level needed -> {
                    if (level == null) {
                        level = levelOf.apply(island, player);
                    }
                    if (level < needed.level()) {
                        missing.add(line);
                    }
                }
                case Requirement.Money money
                when money.from() == Requirement.Source.ISLAND -> {
                    if (!fromTheBank(island, player, line, money.minor(), money.mode())) {
                        missing.add(line);
                    }
                }
                case Requirement.Currency currency
                when currency.from() == Requirement.Source.ISLAND -> {
                    if (!fromTheBank(island, player, line, currency.amount(), currency.mode())) {
                        missing.add(line);
                    }
                }
                default -> {}
            }
        }
        return missing;
    }

    private boolean fromTheBank(Island island, Player player, Requirement line, long amount, Requirement.Mode mode) {
        return funds.held(island.id(), line) >= amount
                && (mode == Requirement.Mode.HAVE || funds.mayTake(island, player));
    }

    /** Asks the breaker's own lines on their thread, then takes what is to be taken, or says what is missing. */
    private void ofThePlayer(
            Island island,
            Player player,
            String phase,
            List<Requirement> lines,
            List<Requirement> missing,
            Runnable opened) {
        IslandId islandId = island.id();
        List<Requirement> wanting = new ArrayList<>(missing);
        for (Requirement line : lines) {
            ChunkRequirement carried = carried(line);
            if (line instanceof Requirement.Permission permission && !player.hasPermission(permission.node())) {
                wanting.add(line);
            } else if (carried != null && purse.carried(player, carried) < carried.amount()) {
                wanting.add(line);
            }
        }
        if (!wanting.isEmpty()) {
            asking.remove(islandId);
            tell(player, phase, wanting);
            return;
        }
        List<ChunkRequirement> taken = new ArrayList<>();
        for (Requirement line : lines) {
            ChunkRequirement carried = carried(line);
            if (carried == null || line.mode() != Requirement.Mode.TAKE) {
                continue;
            }
            long got = purse.take(player, carried, carried.amount());
            if (got < carried.amount()) {
                purse.giveBack(player, carried, got);
                taken.forEach(back -> purse.giveBack(player, back, back.amount()));
                asking.remove(islandId);
                tell(player, phase, List.of(line));
                return;
            }
            taken.add(carried);
        }
        scheduler.async(() -> fromTheIsland(island, player, phase, lines, taken, opened));
    }

    /** Takes the island's lines out of its bank, off the region threads, and opens the phase once all is paid. */
    private void fromTheIsland(
            Island island,
            Player player,
            String phase,
            List<Requirement> lines,
            List<ChunkRequirement> fromThePlayer,
            Runnable opened) {
        IslandId islandId = island.id();
        List<Requirement> taken = new ArrayList<>();
        try {
            for (Requirement line : lines) {
                if (line.mode() != Requirement.Mode.TAKE || !fromTheIsland(line)) {
                    continue;
                }
                if (!funds.take(islandId, player, line)) {
                    taken.forEach(back -> funds.giveBack(islandId, player, back));
                    scheduler.onEntity(player.getUniqueId(), () -> {
                        fromThePlayer.forEach(back -> purse.giveBack(player, back, back.amount()));
                        tell(player, phase, List.of(line));
                    });
                    return;
                }
                taken.add(line);
            }
            service.waiting(islandId).ifPresent(waiting -> service.endWait(islandId, waiting));
            service.storeWait(islandId);
            // Said before the phase begins, so the breaker hears why it does.
            scheduler.onEntity(player.getUniqueId(), () -> {
                messages.send(player, "oneblock.requires.met", Placeholder.unparsed("phase", title(player, phase)));
                opened.run();
            });
        } finally {
            asking.remove(islandId);
        }
    }

    private static boolean fromTheIsland(Requirement line) {
        return switch (line) {
            case Requirement.Money money -> money.from() == Requirement.Source.ISLAND;
            case Requirement.Currency currency -> currency.from() == Requirement.Source.ISLAND;
            default -> false;
        };
    }

    /** The line as the breaker's purse reads it, or nothing for a line their purse does not hold. */
    private static @Nullable ChunkRequirement carried(Requirement line) {
        return switch (line) {
            case Requirement.Money money
            when money.from() == Requirement.Source.PLAYER ->
                new ChunkRequirement.Money(money.minor(), ChunkRequirement.Source.PLAYER);
            case Requirement.Currency currency
            when currency.from() == Requirement.Source.PLAYER ->
                new ChunkRequirement.Currency(currency.id(), currency.amount(), ChunkRequirement.Source.PLAYER);
            case Requirement.Items items -> new ChunkRequirement.Items(items.material(), items.amount());
            default -> null;
        };
    }

    /** Tells the breaker the phase waits, and for which lines. */
    private void tell(Player player, String phase, List<Requirement> missing) {
        messages.send(player, "oneblock.requires.missing", Placeholder.unparsed("phase", title(player, phase)));
        for (Requirement line : missing) {
            said(player, line);
        }
    }

    private void said(Player player, Requirement line) {
        switch (line) {
            case Requirement.Money money ->
                messages.sendPlain(
                        player,
                        money.from() == Requirement.Source.ISLAND
                                ? "oneblock.requires.money_island"
                                : "oneblock.requires.money",
                        Placeholder.unparsed(
                                "amount",
                                BigDecimal.valueOf(money.minor())
                                        .movePointLeft(2)
                                        .stripTrailingZeros()
                                        .toPlainString()));
            case Requirement.Currency currency ->
                messages.sendPlain(
                        player,
                        currency.from() == Requirement.Source.ISLAND
                                ? "oneblock.requires.currency_island"
                                : "oneblock.requires.currency",
                        Placeholder.unparsed("amount", Long.toString(currency.amount())),
                        Placeholder.unparsed("currency", currency.id()));
            case Requirement.Items items ->
                messages.sendPlain(
                        player,
                        "oneblock.requires.items",
                        Placeholder.unparsed("amount", Long.toString(items.amount())),
                        Placeholder.unparsed("item", itemName(items.material())));
            case Requirement.Level level ->
                messages.sendPlain(
                        player,
                        "oneblock.requires.level",
                        Placeholder.unparsed("needed", Long.toString(level.level())));
            case Requirement.Permission permission ->
                messages.sendPlain(
                        player, "oneblock.requires.permission", Placeholder.unparsed("permission", permission.node()));
            default -> {}
        }
    }

    /** {@code minecraft:oak_log} as {@code oak log}. */
    static String itemName(String material) {
        int colon = material.indexOf(':');
        return material.substring(colon + 1).replace('_', ' ').toLowerCase(Locale.ROOT);
    }

    private String title(Player player, String phase) {
        return messages.named(player, "oneblock.phases", phase, phase);
    }
}
