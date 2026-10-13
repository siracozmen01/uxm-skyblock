package com.uxplima.uxmskyblock.bukkit.requirement;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.entity.Player;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import com.uxplima.uxmskyblock.bukkit.chunkblock.ChunkPurse;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkRequirement;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.requirement.Requirement;
import org.jspecify.annotations.Nullable;

/**
 * Asks a list of requirement lines of an island and one of its players, and takes what is to be taken.
 *
 * <p>The island's level and bank are read off the region threads, then the player's permission, money, currency and
 * items on their own thread. When every line holds, what is to be taken is taken, from the player first and then from
 * the island's bank, and given back whole if any part cannot be. A OneBlock phase asks its lines this way before it
 * begins, and a Boxed island pays for growth this way.
 */
public final class LinePayment {

    private static final Logger LOGGER = Logger.getLogger(LinePayment.class.getName());

    /** The island's bank, as lines reach into it. Every call runs off the region threads. */
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

    /** What became of lines asked. */
    public interface Outcome {
        /** Some lines do not hold, or could not be taken; nothing was taken. On the player's thread. */
        void missing(List<Requirement> lines);

        /** Every line held and what was to be taken was. Off the region threads. */
        void paid();
    }

    private final SchedulerPort scheduler;
    private final ChunkPurse purse;
    private final IslandFunds funds;
    private final BiFunction<Island, Player, Long> levelOf;

    /** @param levelOf the island's level as the level command works it out, read off the region threads */
    public LinePayment(
            SchedulerPort scheduler, ChunkPurse purse, IslandFunds funds, BiFunction<Island, Player, Long> levelOf) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.purse = Objects.requireNonNull(purse, "purse must not be null");
        this.funds = Objects.requireNonNull(funds, "funds must not be null");
        this.levelOf = Objects.requireNonNull(levelOf, "levelOf must not be null");
    }

    /**
     * Asks {@code lines} of the island, {@code player} paying what is taken, and tells {@code outcome}. {@code done}
     * runs once the asking is over, whatever became of it, on whichever thread it ended.
     */
    public void pay(Island island, Player player, List<Requirement> lines, Outcome outcome, Runnable done) {
        List<Requirement> asked = List.copyOf(lines);
        scheduler.async(() -> {
            try {
                List<Requirement> missing = ofTheIsland(island, player, asked);
                scheduler.onEntity(
                        player.getUniqueId(), () -> ofThePlayer(island, player, asked, missing, outcome, done), done);
            } catch (RuntimeException failed) {
                done.run();
                LOGGER.log(
                        Level.WARNING, failed, () -> "Asking requirement lines of island " + island.id() + " failed.");
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

    /** Asks the player's own lines on their thread, then takes what is to be taken, or says what is missing. */
    private void ofThePlayer(
            Island island,
            Player player,
            List<Requirement> lines,
            List<Requirement> missing,
            Outcome outcome,
            Runnable done) {
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
            done.run();
            outcome.missing(wanting);
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
                done.run();
                outcome.missing(List.of(line));
                return;
            }
            taken.add(carried);
        }
        scheduler.async(() -> fromTheIsland(island, player, lines, taken, outcome, done));
    }

    /** Takes the island's lines out of its bank, off the region threads, and tells the outcome once all is paid. */
    private void fromTheIsland(
            Island island,
            Player player,
            List<Requirement> lines,
            List<ChunkRequirement> fromThePlayer,
            Outcome outcome,
            Runnable done) {
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
                        outcome.missing(List.of(line));
                    });
                    return;
                }
                taken.add(line);
            }
            outcome.paid();
        } finally {
            done.run();
        }
    }

    private static boolean fromTheIsland(Requirement line) {
        return switch (line) {
            case Requirement.Money money -> money.from() == Requirement.Source.ISLAND;
            case Requirement.Currency currency -> currency.from() == Requirement.Source.ISLAND;
            default -> false;
        };
    }

    /** The line as the player's purse reads it, or nothing for a line their purse does not hold. */
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

    /**
     * One requirement line in the viewer's language, from the keys under {@code prefix}: {@code money},
     * {@code money_island}, {@code currency}, {@code currency_island}, {@code items}, {@code level} and
     * {@code permission}. Nothing for a line no key says.
     */
    public static @Nullable Component lineOf(Messages messages, Audience viewer, Requirement line, String prefix) {
        return switch (line) {
            case Requirement.Money money ->
                messages.renderPlain(
                        viewer,
                        prefix + (money.from() == Requirement.Source.ISLAND ? ".money_island" : ".money"),
                        Placeholder.unparsed(
                                "amount",
                                BigDecimal.valueOf(money.minor())
                                        .movePointLeft(2)
                                        .stripTrailingZeros()
                                        .toPlainString()));
            case Requirement.Currency currency ->
                messages.renderPlain(
                        viewer,
                        prefix + (currency.from() == Requirement.Source.ISLAND ? ".currency_island" : ".currency"),
                        Placeholder.unparsed("amount", Long.toString(currency.amount())),
                        Placeholder.unparsed("currency", currency.id()));
            case Requirement.Items items ->
                messages.renderPlain(
                        viewer,
                        prefix + ".items",
                        Placeholder.unparsed("amount", Long.toString(items.amount())),
                        Placeholder.unparsed("item", itemName(items.material())));
            case Requirement.Level level ->
                messages.renderPlain(
                        viewer, prefix + ".level", Placeholder.unparsed("needed", Long.toString(level.level())));
            case Requirement.Permission permission ->
                messages.renderPlain(
                        viewer, prefix + ".permission", Placeholder.unparsed("permission", permission.node()));
            default -> null;
        };
    }

    /** {@code minecraft:oak_log} as {@code oak log}. */
    public static String itemName(String material) {
        int colon = material.indexOf(':');
        return material.substring(colon + 1).replace('_', ' ').toLowerCase(Locale.ROOT);
    }
}
