package com.uxplima.uxmskyblock.bukkit.visitor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.entity.Player;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.visit.IslandVisitFeeService;
import com.uxplima.uxmskyblock.core.application.visit.VisitFeeOutOfRangeException;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import org.jspecify.annotations.Nullable;

/**
 * The visit fee: {@code /is visitfee} sets and shows it, and {@code /is visit} asks a visitor to agree to it and takes
 * it from their wallet into the island's bank before they go.
 */
public final class VisitFees implements VisitFeeGate {

    private static final Logger LOGGER = Logger.getLogger(VisitFees.class.getName());

    /** How long a fee shown to a visitor stays agreed to by their confirming. */
    static final Duration ASKED_FOR = Duration.ofSeconds(60);

    /** The wallet the fee is taken from, in the economy's own amounts. */
    public interface Wallet {

        /** Whether an economy is there to charge at all. */
        boolean available();

        /** Takes {@code amount} from {@code player}, or nothing and false. */
        boolean take(Player player, double amount);

        /** Gives {@code amount} back to {@code player}. */
        boolean give(Player player, double amount);

        /** {@code amount} as the economy writes money. */
        String format(double amount);
    }

    /** Puts {@code minorUnits} paid by {@code payer} into {@code island}'s bank, true when it landed. */
    @FunctionalInterface
    public interface Bank {
        boolean credit(IslandId island, PlayerUuid payer, long minorUnits);
    }

    private record Asked(IslandId island, long owed, Instant at) {}

    private final IslandVisitFeeService fees;
    private final Function<IslandId, Optional<Island>> islands;
    private final Function<ProfileId, Optional<IslandId>> ownIsland;
    private final Wallet wallet;
    private final Bank bank;
    private final LongSupplier most;
    private final SchedulerPort scheduler;
    private final Messages messages;
    private final Clock clock;
    private final Map<UUID, Asked> asked = new ConcurrentHashMap<>();

    /**
     * @param most the most an island may charge, in minor units, read as the operator wrote it; zero turns fees off
     */
    public VisitFees(
            IslandVisitFeeService fees,
            Function<IslandId, Optional<Island>> islands,
            Function<ProfileId, Optional<IslandId>> ownIsland,
            Wallet wallet,
            Bank bank,
            LongSupplier most,
            SchedulerPort scheduler,
            Messages messages,
            Clock clock) {
        this.fees = Objects.requireNonNull(fees, "fees must not be null");
        this.islands = Objects.requireNonNull(islands, "islands must not be null");
        this.ownIsland = Objects.requireNonNull(ownIsland, "ownIsland must not be null");
        this.wallet = Objects.requireNonNull(wallet, "wallet must not be null");
        this.bank = Objects.requireNonNull(bank, "bank must not be null");
        this.most = Objects.requireNonNull(most, "most must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public long owed(IslandId island, Player visitor, @Nullable ProfileId profile) {
        if (most.getAsLong() <= 0 || !wallet.available() || visitor.hasPermission("uxmskyblock.admin.bypass")) {
            return 0;
        }
        if (profile != null
                && islands.apply(island).map(it -> it.isMember(profile)).orElse(false)) {
            return 0;
        }
        return Math.max(0, fees.feeOf(island));
    }

    @Override
    public boolean agreed(Player visitor, IslandId island, long owed) {
        Asked shown = asked.get(visitor.getUniqueId());
        return shown != null
                && shown.island().equals(island)
                && owed <= shown.owed()
                && !clock.instant().isAfter(shown.at().plus(ASKED_FOR));
    }

    @Override
    public void ask(Player visitor, IslandId island, long owed, String confirmLine) {
        asked.put(visitor.getUniqueId(), new Asked(island, owed, clock.instant()));
        tell(
                visitor,
                "visit_fee.asks",
                Placeholder.unparsed("amount", money(owed)),
                Placeholder.unparsed("command", confirmLine));
    }

    @Override
    public void pay(Player visitor, IslandId island, long owed, Runnable then) {
        PlayerUuid payer = new PlayerUuid(visitor.getUniqueId());
        scheduler.async(() -> {
            double amount = owed / 100.0;
            if (!wallet.take(visitor, amount)) {
                tell(visitor, "visit_fee.unaffordable", Placeholder.unparsed("amount", money(owed)));
                return;
            }
            boolean landed;
            try {
                landed = bank.credit(island, payer, owed);
            } catch (RuntimeException failed) {
                LOGGER.log(Level.WARNING, "A visit fee could not be put into the island bank", failed);
                landed = false;
            }
            if (!landed) {
                if (!wallet.give(visitor, amount)) {
                    LOGGER.log(
                            Level.SEVERE,
                            "A visit fee the island bank refused could not be given back. player={0} amount={1}",
                            new Object[] {payer, owed});
                }
                tell(visitor, "visit_fee.failed");
                return;
            }
            asked.remove(visitor.getUniqueId());
            scheduler.onEntity(payer, () -> {
                messages.send(visitor, "visit_fee.paid", Placeholder.unparsed("amount", money(owed)));
                then.run();
            });
        });
    }

    /** {@code /is visitfee}: what the player's own island charges. */
    public void show(Player player, @Nullable ProfileId profile) {
        scheduler.async(() -> {
            Optional<IslandId> island = profile == null ? Optional.empty() : ownIsland.apply(profile);
            if (island.isEmpty()) {
                tell(player, "visit_fee.no_island");
                return;
            }
            long fee = fees.feeOf(island.get());
            if (fee <= 0) {
                tell(player, "visit_fee.none", Placeholder.unparsed("most", money(most.getAsLong())));
            } else {
                tell(player, "visit_fee.shown", Placeholder.unparsed("amount", money(fee)));
            }
        });
    }

    /** {@code /is visitfee <amount>}: sets what the player's own island charges, zero for nothing. */
    public void set(Player player, @Nullable ProfileId profile, double amount) {
        long minor;
        try {
            minor = BigDecimal.valueOf(amount)
                    .setScale(2, RoundingMode.DOWN)
                    .movePointRight(2)
                    .longValueExact();
        } catch (ArithmeticException tooLarge) {
            minor = Long.MAX_VALUE;
        }
        long wanted = minor;
        scheduler.async(() -> {
            if (most.getAsLong() <= 0 || !wallet.available()) {
                tell(player, "visit_fee.disabled");
                return;
            }
            Optional<Island> island = profile == null
                    ? Optional.empty()
                    : ownIsland.apply(profile).flatMap(islands);
            if (island.isEmpty() || profile == null) {
                tell(player, "visit_fee.no_island");
                return;
            }
            try {
                fees.setFee(island.get(), profile, wanted, most.getAsLong());
            } catch (SecurityException denied) {
                tell(player, "visit_fee.no_permission");
                return;
            } catch (VisitFeeOutOfRangeException outside) {
                tell(player, "visit_fee.out_of_range", Placeholder.unparsed("most", money(outside.most())));
                return;
            }
            if (wanted == 0) {
                tell(player, "visit_fee.cleared");
            } else {
                tell(player, "visit_fee.set", Placeholder.unparsed("amount", money(wanted)));
            }
        });
    }

    private String money(long minorUnits) {
        return wallet.format(minorUnits / 100.0);
    }

    private void tell(
            Player player, String key, net.kyori.adventure.text.minimessage.tag.resolver.TagResolver... with) {
        scheduler.onEntity(new PlayerUuid(player.getUniqueId()), () -> messages.send(player, key, with));
    }
}
