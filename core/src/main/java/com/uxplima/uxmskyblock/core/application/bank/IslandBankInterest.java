package com.uxplima.uxmskyblock.core.application.bank;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.function.ToLongFunction;

import com.uxplima.uxmskyblock.core.application.inactivity.PlayerActivityProvider;
import com.uxplima.uxmskyblock.core.domain.bank.BankTransactionOutcome;
import com.uxplima.uxmskyblock.core.domain.bank.IslandBank;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandMember;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;

/**
 * Pays an island bank interest on its money, once each period, to an island one of whose members played lately.
 *
 * <p>Each payment is keyed by the island and the period, as the upkeep charge is, so a restart or a second node pays
 * nothing a second time. It never fills a bank past the limit its bank limit tier sets.
 */
public final class IslandBankInterest {

    /** The actor every interest payment is recorded under. */
    public static final UUID SYSTEM_INTEREST_ACTOR =
            UUID.nameUUIDFromBytes("island-bank-interest".getBytes(StandardCharsets.UTF_8));

    /**
     * How interest is paid.
     *
     * @param enabled whether any is paid
     * @param interval how long a period is; each island is paid once a period
     * @param percent the share of the money in the bank paid, in percent
     * @param activeWithin how lately a member must have played for the island to be paid
     * @param maxPerPaymentMinorUnits the most one payment gives, in minor units, or zero for no most
     */
    public record Policy(
            boolean enabled, Duration interval, double percent, Duration activeWithin, long maxPerPaymentMinorUnits) {

        public Policy {
            Objects.requireNonNull(interval, "interval");
            Objects.requireNonNull(activeWithin, "activeWithin");
            if (interval.isZero() || interval.isNegative()) {
                throw new IllegalArgumentException("interval must be positive");
            }
        }

        /** The period {@code now} falls in. */
        public long periodOf(Instant now) {
            return Math.floorDiv(now.toEpochMilli(), interval.toMillis());
        }
    }

    /** What one island was paid this period. */
    public sealed interface Payment {
        /** Paid, or found paid by an earlier ask. */
        record Paid(long amountMinorUnits) implements Payment {}

        /** Owed nothing: interest is off, nobody played lately, the bank is empty or full. */
        record Nothing(String why) implements Payment {}

        /** The bank would not take it now; the next poll asks again. */
        record Refused(BankTransactionOutcome outcome) implements Payment {}
    }

    private final IslandBankPort banks;
    private final IslandBankService bank;
    private final PlayerActivityProvider activity;
    private final ToLongFunction<com.uxplima.uxmskyblock.core.domain.identity.IslandId> cap;

    /** @param cap the most an island bank may hold, in minor units, or {@link Long#MAX_VALUE} */
    public IslandBankInterest(
            IslandBankPort banks,
            IslandBankService bank,
            PlayerActivityProvider activity,
            ToLongFunction<com.uxplima.uxmskyblock.core.domain.identity.IslandId> cap) {
        this.banks = Objects.requireNonNull(banks, "banks");
        this.bank = Objects.requireNonNull(bank, "bank");
        this.activity = Objects.requireNonNull(activity, "activity");
        this.cap = Objects.requireNonNull(cap, "cap");
    }

    /** Pays {@code island} its interest for the period {@code now} falls in, if it is owed any. */
    public Payment pay(Island island, Policy policy, Instant now, ServerNodeId node) {
        Objects.requireNonNull(island, "island");
        Objects.requireNonNull(policy, "policy");
        if (!policy.enabled()) {
            return new Payment.Nothing("interest is off");
        }
        if (!playedLately(island, policy.activeWithin(), now)) {
            return new Payment.Nothing("nobody played lately");
        }
        long held = banks.findBankByIslandId(island.id())
                .map(IslandBank::primaryBalanceMinorUnits)
                .orElse(0L);
        long amount = (long) Math.floor(held * policy.percent() / 100.0);
        if (policy.maxPerPaymentMinorUnits() > 0) {
            amount = Math.min(amount, policy.maxPerPaymentMinorUnits());
        }
        long limit = cap.applyAsLong(island.id());
        if (limit != Long.MAX_VALUE) {
            amount = Math.min(amount, Math.max(0L, limit - held));
        }
        if (amount <= 0) {
            return new Payment.Nothing("nothing to pay");
        }
        String key = "interest-" + island.id().value() + "-" + policy.periodOf(now);
        BankTransactionOutcome outcome = bank.depositToIsland(
                island.id(),
                new PlayerUuid(SYSTEM_INTEREST_ACTOR),
                amount,
                "Bank interest",
                node,
                UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)),
                key,
                "BANK_INTEREST");
        return IslandBankService.landed(outcome) ? new Payment.Paid(amount) : new Payment.Refused(outcome);
    }

    /** Whether a member of {@code island}, its owner one of them, played within {@code within} of {@code now}. */
    private boolean playedLately(Island island, Duration within, Instant now) {
        Instant since = now.minus(within);
        for (IslandMember member : island.members().values()) {
            Instant last = activity.getLastActive(member.playerUuid(), member.profileId())
                    .orElse(Instant.MIN);
            if (!last.isBefore(since)) {
                return true;
            }
        }
        return false;
    }
}
