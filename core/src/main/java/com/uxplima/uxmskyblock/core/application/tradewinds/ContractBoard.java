package com.uxplima.uxmskyblock.core.application.tradewinds;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import com.uxplima.uxmskyblock.core.application.tradewinds.ContractsPort.Held;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.result.Result;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;

/**
 * The deliveries the ports offer, as the crews take them, carry them and deliver them, and as they fail when late.
 *
 * <p>A contract is taken at the port that offers it, while the vessel lies there, and delivered at the port it names,
 * where the goods leave the hold in the same transaction that marks it delivered. One the crew did not deliver in
 * time fails, when it is next looked at or when {@link #failOverdue} finds it, and only one call fails it.
 *
 * <p>Everything here reads and writes durable storage: call it off the main thread.
 */
public final class ContractBoard {

    /** How many times a delivery tries to write the hold while it moves under it. */
    private static final int DELIVER_TRIES = 3;

    private final ContractsPort port;
    private final PortMarket market;
    private final VesselsPort vessels;
    private final PortMarket.Goods goods;
    private final VesselLease lease;
    private final ServerNodeId node;
    private final Clock clock;
    private final List<Contract> contracts;
    private final int atOnce;
    private final Map<IslandId, ReentrantLock> locks = new ConcurrentHashMap<>();

    /**
     * @param contracts every contract the ports offer, in the order the file writes them
     * @param atOnce how many contracts one vessel holds at a time
     */
    @SuppressWarnings("TooManyParameters")
    public ContractBoard(
            ContractsPort port,
            PortMarket market,
            VesselsPort vessels,
            PortMarket.Goods goods,
            VesselLease lease,
            ServerNodeId node,
            Clock clock,
            List<Contract> contracts,
            int atOnce) {
        this.port = Objects.requireNonNull(port, "port");
        this.market = Objects.requireNonNull(market, "market");
        this.vessels = Objects.requireNonNull(vessels, "vessels");
        this.goods = Objects.requireNonNull(goods, "goods");
        this.lease = Objects.requireNonNull(lease, "lease");
        this.node = Objects.requireNonNull(node, "node");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.contracts = List.copyOf(contracts);
        if (atOnce < 1) {
            throw new IllegalArgumentException("a vessel holds at least one contract at a time");
        }
        this.atOnce = atOnce;
    }

    /** The contracts {@code portId} offers, in the order the file writes them. */
    public List<Contract> offeredAt(String portId) {
        return contracts.stream().filter(one -> one.from().equals(portId)).toList();
    }

    /** The contract the file writes under {@code id}, if it writes one. */
    public Optional<Contract> contract(String id) {
        return contracts.stream().filter(one -> one.id().equals(id)).findFirst();
    }

    /** Every contract {@code vessel} holds now, the soonest due first. */
    public List<Held> held(IslandId vessel) {
        return port.of(vessel).stream()
                .filter(one -> one.state() == ContractsPort.State.TAKEN)
                .sorted(java.util.Comparator.comparing(Held::due))
                .toList();
    }

    /** Takes {@code contractId} for {@code vessel}, while it lies in the port that offers it. */
    public Result<Held, String> take(IslandId vessel, PlayerUuid taker, String contractId) {
        Optional<Contract> found = contract(contractId);
        if (found.isEmpty()) {
            return Result.err("tradewinds.contracts.unknown");
        }
        Contract contract = found.get();
        if (!(market.where(vessel) instanceof PortMarket.Where.Docked docked)
                || !docked.portId().equals(contract.from())) {
            return Result.err("tradewinds.contracts.not_offered_here");
        }
        ReentrantLock held = lockOf(vessel);
        held.lock();
        try {
            if (lease.hold(vessel).isEmpty()) {
                return Result.err("tradewinds.hold.elsewhere");
            }
            List<Held> holding = held(vessel);
            if (holding.stream().anyMatch(one -> one.contractId().equals(contractId))) {
                return Result.err("tradewinds.contracts.already_taken");
            }
            if (holding.size() >= atOnce) {
                return Result.err("tradewinds.contracts.too_many");
            }
            Held taken = new Held(
                    vessel,
                    contract.id(),
                    contract.to(),
                    contract.item(),
                    contract.count(),
                    taker,
                    clock.instant().plus(contract.within()),
                    ContractsPort.State.TAKEN);
            return port.take(taken) ? Result.ok(taken) : Result.err("tradewinds.contracts.already_taken");
        } finally {
            held.unlock();
        }
    }

    /** Delivers {@code contractId} out of the hold of {@code vessel}, while it lies in the port it names. */
    public Result<Held, String> deliver(IslandId vessel, String contractId) {
        ReentrantLock locked = lockOf(vessel);
        locked.lock();
        try {
            for (int tried = 0; tried < DELIVER_TRIES; tried++) {
                Optional<Held> found = held(vessel).stream()
                        .filter(one -> one.contractId().equals(contractId))
                        .findFirst();
                if (found.isEmpty()) {
                    return Result.err("tradewinds.contracts.not_taken");
                }
                Held held = found.get();
                Instant now = clock.instant();
                if (!held.due().isAfter(now)) {
                    return Result.err("tradewinds.contracts.late");
                }
                if (!(market.where(vessel) instanceof PortMarket.Where.Docked docked)
                        || !docked.portId().equals(held.to())) {
                    return Result.err("tradewinds.contracts.not_there");
                }
                OptionalLong epoch = lease.hold(vessel);
                Optional<VesselsPort.Cargo> cargo = vessels.cargo(vessel);
                if (epoch.isEmpty() || cargo.isEmpty()) {
                    return Result.err("tradewinds.hold.elsewhere");
                }
                Optional<byte[]> after = goods.take(cargo.get().items(), held.item(), held.count());
                if (after.isEmpty()) {
                    return Result.err("tradewinds.contracts.too_few");
                }
                if (port.deliver(
                        vessel,
                        contractId,
                        now,
                        new MarketOrdersPort.HoldWrite(
                                epoch.getAsLong(), node, cargo.get().version(), after.get()))) {
                    return Result.ok(new Held(
                            held.vessel(),
                            held.contractId(),
                            held.to(),
                            held.item(),
                            held.count(),
                            held.taker(),
                            held.due(),
                            ContractsPort.State.DELIVERED));
                }
                // The hold moved under the delivery, or the contract did: look again.
            }
            return Result.err("tradewinds.market.busy");
        } finally {
            locked.unlock();
        }
    }

    /** Fails every contract of every vessel that was not delivered in time. Answers those this call failed. */
    public List<Held> failOverdue() {
        List<Held> failed = new ArrayList<>();
        for (Held overdue : port.overdue(clock.instant())) {
            if (port.fail(overdue.vessel(), overdue.contractId())) {
                failed.add(overdue);
            }
        }
        return List.copyOf(failed);
    }

    /** Forgets what is kept for {@code vessel}, an island that is gone: its lock, unless a delivery holds it. */
    public void forget(IslandId islandId) {
        ReentrantLock lock = locks.get(islandId);
        if (lock == null || !lock.isLocked()) {
            locks.remove(islandId);
        }
    }

    private ReentrantLock lockOf(IslandId vessel) {
        return locks.computeIfAbsent(vessel, key -> new ReentrantLock());
    }
}
