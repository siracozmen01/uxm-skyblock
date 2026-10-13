package com.uxplima.uxmskyblock.core.application.tradewinds;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

import com.uxplima.uxmskyblock.core.application.tradewinds.PlunderPort.Plunder;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.result.Result;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;

/**
 * A crew boarding another vessel at sea and taking part of its hold into their own.
 *
 * <p>Both vessels have to be at sea: a vessel in port is under the port's protection. A vessel that plundered, or was
 * plundered, waits out the cooldown before it does either again. The goods leave one hold and enter the other in
 * one transaction, under both vessels' leases, written down with the plunder.
 *
 * <p>Everything here reads and writes durable storage: call it off the main thread.
 */
public final class Piracy {

    /** How many times a plunder tries while a hold moves under it. */
    private static final int TRIES = 3;

    /** What changes holds: the target's hold and the raider's after, and how many items moved. */
    @SuppressWarnings("ArrayRecordComponent")
    public record Haul(byte[] target, byte[] raider, int items) {
        public Haul {
            target = target.clone();
            raider = raider.clone();
        }

        @Override
        public byte[] target() {
            return target.clone();
        }

        @Override
        public byte[] raider() {
            return raider.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Haul that
                    && java.util.Arrays.equals(target, that.target)
                    && java.util.Arrays.equals(raider, that.raider)
                    && items == that.items;
        }

        @Override
        public int hashCode() {
            return 31 * (31 * java.util.Arrays.hashCode(target) + java.util.Arrays.hashCode(raider)) + items;
        }

        @Override
        public String toString() {
            return "Haul[" + items + " items]";
        }
    }

    /** What part of a target's hold a raider of {@code slots} slots takes, or nothing when nothing fits. */
    @FunctionalInterface
    public interface Split {
        Optional<Haul> split(byte[] target, byte[] raider, int slots);
    }

    private final PlunderPort port;
    private final PortMarket market;
    private final VesselsPort vessels;
    private final VesselLease lease;
    private final ServerNodeId node;
    private final Clock clock;
    private final Duration cooldown;

    @SuppressWarnings("TooManyParameters")
    public Piracy(
            PlunderPort port,
            PortMarket market,
            VesselsPort vessels,
            VesselLease lease,
            ServerNodeId node,
            Clock clock,
            Duration cooldown) {
        this.port = Objects.requireNonNull(port, "port");
        this.market = Objects.requireNonNull(market, "market");
        this.vessels = Objects.requireNonNull(vessels, "vessels");
        this.lease = Objects.requireNonNull(lease, "lease");
        this.node = Objects.requireNonNull(node, "node");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.cooldown = Objects.requireNonNull(cooldown, "cooldown");
        if (cooldown.isNegative()) {
            throw new IllegalArgumentException("a cooldown is not below nothing: " + cooldown);
        }
    }

    /** Takes from {@code target}'s hold into {@code raider}'s what {@code split} says. Says why not when it cannot. */
    public Result<Plunder, String> plunder(IslandId raider, IslandId target, Split split) {
        if (raider.equals(target)) {
            return Result.err("tradewinds.piracy.own");
        }
        if (!(market.where(raider) instanceof PortMarket.Where.Sailing)) {
            return Result.err("tradewinds.piracy.raider_in_port");
        }
        if (!(market.where(target) instanceof PortMarket.Where.Sailing)) {
            return Result.err("tradewinds.piracy.target_in_port");
        }
        Instant now = clock.instant();
        Instant since = now.minus(cooldown);
        if (port.recent(raider, target, since)) {
            return Result.err("tradewinds.piracy.too_soon");
        }
        for (int tried = 0; tried < TRIES; tried++) {
            OptionalLong raiderEpoch = lease.hold(raider);
            OptionalLong targetEpoch = lease.hold(target);
            Optional<VesselsPort.Cargo> raiderCargo = vessels.cargo(raider);
            Optional<VesselsPort.Cargo> targetCargo = vessels.cargo(target);
            if (raiderEpoch.isEmpty() || targetEpoch.isEmpty() || raiderCargo.isEmpty() || targetCargo.isEmpty()) {
                return Result.err("tradewinds.hold.elsewhere");
            }
            Optional<Haul> haul = split.split(
                    targetCargo.get().items(),
                    raiderCargo.get().items(),
                    market.rank(raider).holdSlots());
            if (haul.isEmpty() || haul.get().items() < 1) {
                return Result.err("tradewinds.piracy.no_haul");
            }
            Plunder plunder = new Plunder(raider, target, now, haul.get().items());
            PlunderPort.Outcome outcome = port.take(
                    plunder,
                    new MarketOrdersPort.HoldWrite(
                            raiderEpoch.getAsLong(),
                            node,
                            raiderCargo.get().version(),
                            haul.get().raider()),
                    new MarketOrdersPort.HoldWrite(
                            targetEpoch.getAsLong(),
                            node,
                            targetCargo.get().version(),
                            haul.get().target()),
                    since);
            switch (outcome) {
                case TAKEN -> {
                    return Result.ok(plunder);
                }
                case TOO_SOON -> {
                    return Result.err("tradewinds.piracy.too_soon");
                }
                case MOVED -> {
                    // A hold moved under the plunder: look again.
                }
            }
        }
        return Result.err("tradewinds.market.busy");
    }
}
