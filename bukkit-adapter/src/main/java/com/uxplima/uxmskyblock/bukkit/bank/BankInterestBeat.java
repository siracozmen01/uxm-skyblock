package com.uxplima.uxmskyblock.bukkit.bank;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.logging.Logger;

import com.uxplima.uxmskyblock.core.application.bank.IslandBankInterest;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import org.jspecify.annotations.Nullable;

/**
 * Looks at the clock every minute and pays each island its bank interest for the period, once.
 *
 * <p>A period every island was settled for costs nothing more on this node. An island the bank would not take the
 * payment for is asked again on the next look, alone; the payment's own key keeps a second node or a restart from
 * paying it twice.
 */
public final class BankInterestBeat implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(BankInterestBeat.class.getName());
    private static final Duration POLL = Duration.ofMinutes(1);

    private final IslandBankInterest interest;
    private final Supplier<IslandBankInterest.Policy> policy;
    private final Supplier<List<Island>> islands;
    private final ServerNodeId node;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile long settledPeriod = Long.MIN_VALUE;
    private volatile long pendingPeriod = Long.MIN_VALUE;
    private volatile List<Island> pending = List.of();
    private volatile @Nullable AutoCloseable task;

    public BankInterestBeat(
            IslandBankInterest interest,
            Supplier<IslandBankInterest.Policy> policy,
            Supplier<List<Island>> islands,
            ServerNodeId node) {
        this.interest = Objects.requireNonNull(interest, "interest must not be null");
        this.policy = Objects.requireNonNull(policy, "policy must not be null");
        this.islands = Objects.requireNonNull(islands, "islands must not be null");
        this.node = Objects.requireNonNull(node, "node must not be null");
    }

    /** Starts looking, if interest is on. */
    public void start(SchedulerPort scheduler) {
        if (policy.get().enabled() && task == null) {
            task = scheduler.repeatAsync(() -> payFor(Instant.now()), POLL, POLL);
        }
    }

    /** Pays every island owed interest for the period {@code now} falls in, once. */
    public void payFor(Instant now) {
        IslandBankInterest.Policy rules = policy.get();
        if (!rules.enabled()) {
            return;
        }
        long period = rules.periodOf(now);
        if (period == settledPeriod || !running.compareAndSet(false, true)) {
            return;
        }
        try {
            List<Island> due = period == pendingPeriod ? pending : islands.get();
            List<Island> again = new ArrayList<>();
            for (Island island : due) {
                try {
                    if (interest.pay(island, rules, now, node) instanceof IslandBankInterest.Payment.Refused refused) {
                        LOGGER.fine(() -> "Interest for island " + island.id().value() + " waits: " + refused);
                        again.add(island);
                    }
                } catch (RuntimeException failed) {
                    again.add(island);
                }
            }
            if (again.isEmpty()) {
                settledPeriod = period;
            } else {
                pending = List.copyOf(again);
                pendingPeriod = period;
            }
        } finally {
            running.set(false);
        }
    }

    @Override
    public void close() {
        AutoCloseable started = task;
        task = null;
        if (started != null) {
            try {
                started.close();
            } catch (Exception ignored) {
                // Stopping is best effort, and a stopped beat pays nothing more.
            }
        }
    }
}
