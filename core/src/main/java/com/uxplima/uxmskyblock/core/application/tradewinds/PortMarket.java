package com.uxplima.uxmskyblock.core.application.tradewinds;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.result.Result;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;

/**
 * Voyages between the ports and trade at their markets, priced through the island bank.
 *
 * <p>A vessel trades only at the port it lies in. A sale takes a lot out of the hold and records what the
 * bank owes in one transaction, and the bank then pays. A purchase records the order, the bank is paid,
 * and the lot goes into the hold. The bank answers a keyed move once, and an order asks it under a new
 * key only once every key it asked before was refused outright, so a move is never made twice: an order
 * a crash or a busy bank cut short is carried on by {@link #recover}, never repeated.
 *
 * <p>Everything here reads and writes durable storage: call it off the main thread.
 */
public final class PortMarket {

    /** How many new keys one call asks the bank under before it leaves the order to recovery. */
    private static final int NEW_KEYS_PER_CALL = 3;

    /** How many times a purchase tries to stow what it paid for while the hold moves under it. */
    private static final int STOW_TRIES = 3;

    /** The island bank, as the market asks it. */
    public interface Bank {

        /** How the bank answered a keyed move. */
        enum Answer {
            /** The move happened, now or under the same key before. */
            LANDED,
            /** The bank holds too little for it. Nothing moved, and the key is spent. */
            NO_FUNDS,
            /** Refused for anything else: the key is spent and nothing moved. */
            REFUSED,
            /**
             * Refused before the key was written down, such as by a node that does not write the bank now:
             * nothing moved and the key is not spent. It is asked again later, never passed over for a new
             * one, or a key passed over could still move the money after a new one already had.
             */
            NOT_NOW,
            /** No answer: the move may or may not have happened, and the key must be asked again. */
            UNKNOWN
        }

        /**
         * Moves {@code delta} minor units into the vessel's island bank, or out of it when below 0, once
         * for {@code key}.
         */
        Answer move(IslandId vessel, PlayerUuid actor, long delta, String reason, String key);

        /** Whether this node writes the island bank of {@code vessel} now. */
        boolean here(IslandId vessel);
    }

    /** The items of a serialised hold, as the server knows them. */
    public interface Goods {

        /** How many plain {@code item}s the hold holds. */
        int count(byte[] hold, String item);

        /** The hold with {@code count} plain {@code item}s taken out, or empty when it holds fewer. */
        Optional<byte[]> take(byte[] hold, String item, int count);

        /** The hold of {@code slots} slots with {@code count} {@code item}s stowed, or empty when they do not fit. */
        Optional<byte[]> stow(byte[] hold, int slots, String item, int count);
    }

    /** Where a vessel is. */
    public sealed interface Where {

        /** A vessel that never sailed. */
        record Adrift() implements Where {}

        /** A vessel on its way to {@code portId}, there at {@code arrivesAt}. */
        record Sailing(String portId, Instant arrivesAt) implements Where {}

        /** A vessel lying in {@code portId}. */
        record Docked(String portId) implements Where {}
    }

    /** What a lot sold or bought for. */
    public record Deal(String item, int count, long amount) {}

    private final VesselsPort vessels;
    private final VoyagesPort voyages;
    private final MarketOrdersPort orders;
    private final VesselLease lease;
    private final Bank bank;
    private final Goods goods;
    private final Standing standing;
    private final Ranks ranks;
    private final ServerNodeId node;
    private final Clock clock;
    private final Map<IslandId, ReentrantLock> locks = new ConcurrentHashMap<>();
    private volatile MarketPrices prices = MarketPrices.none();
    private volatile Set<String> contraband = Set.of();
    private volatile Set<String> smugglers = Set.of();
    private volatile DailyDemand demand = DailyDemand.NONE;

    @SuppressWarnings("TooManyParameters")
    public PortMarket(
            VesselsPort vessels,
            VoyagesPort voyages,
            MarketOrdersPort orders,
            VesselLease lease,
            Bank bank,
            Goods goods,
            Standing standing,
            Ranks ranks,
            ServerNodeId node,
            Clock clock) {
        this.vessels = Objects.requireNonNull(vessels, "vessels");
        this.voyages = Objects.requireNonNull(voyages, "voyages");
        this.orders = Objects.requireNonNull(orders, "orders");
        this.lease = Objects.requireNonNull(lease, "lease");
        this.bank = Objects.requireNonNull(bank, "bank");
        this.goods = Objects.requireNonNull(goods, "goods");
        this.standing = Objects.requireNonNull(standing, "standing");
        this.ranks = Objects.requireNonNull(ranks, "ranks");
        this.node = Objects.requireNonNull(node, "node");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** How each port's trade moves its prices; without it, prices stay as written. */
    public void usePrices(MarketPrices prices) {
        this.prices = Objects.requireNonNull(prices, "prices");
    }

    /**
     * The goods the operator marks as contraband, and the ports that take them: every other port refuses to buy
     * one. Without it, every port buys what its market lists.
     */
    public void useContraband(Set<String> items, Set<String> takers) {
        this.contraband = Set.copyOf(items);
        this.smugglers = Set.copyOf(takers);
    }

    /** The goods each port wants more of each day; without it, no day differs from another. */
    public void useDemand(DailyDemand demand) {
        this.demand = Objects.requireNonNull(demand, "demand");
    }

    /** Whether {@code port} wants {@code item} more today, and pays the more for it. */
    public boolean wanted(Port port, String item) {
        return demand.wanted(port, clock.instant()).contains(item);
    }

    /** Whether {@code port} buys {@code item} if its market lists it: anything, unless it is contraband. */
    public boolean takes(Port port, String item) {
        return !contraband.contains(item) || smugglers.contains(port.id());
    }

    /** Where {@code vessel} is now. */
    public Where where(IslandId vessel) {
        Optional<VoyagesPort.Voyage> voyage = voyages.voyage(vessel);
        if (voyage.isEmpty()) {
            return new Where.Adrift();
        }
        return voyage.get().arrivesAt().isAfter(clock.instant())
                ? new Where.Sailing(voyage.get().portId(), voyage.get().arrivesAt())
                : new Where.Docked(voyage.get().portId());
    }

    /** Sets {@code vessel} on its way to {@code port}, and answers when it arrives. */
    public Result<Instant, String> sail(IslandId vessel, Port port) {
        Objects.requireNonNull(port, "port");
        if (where(vessel) instanceof Where.Docked docked && docked.portId().equals(port.id())) {
            return Result.err("tradewinds.sail.already_there");
        }
        OptionalLong epoch = lease.hold(vessel);
        if (epoch.isEmpty()) {
            return Result.err("tradewinds.hold.elsewhere");
        }
        Instant arrives = clock.instant().plusSeconds(port.voyageSeconds());
        return voyages.setSail(vessel, epoch.getAsLong(), node, new VoyagesPort.Voyage(port.id(), arrives))
                ? Result.ok(arrives)
                : Result.err("tradewinds.hold.elsewhere");
    }

    /**
     * Puts {@code vessel} in {@code port} now, as an operator does, under the vessel's lease. Says why not when it
     * cannot.
     */
    public Result<Instant, String> moveTo(IslandId vessel, Port port) {
        Objects.requireNonNull(port, "port");
        OptionalLong epoch = lease.hold(vessel);
        if (epoch.isEmpty()) {
            return Result.err("tradewinds.hold.elsewhere");
        }
        Instant now = clock.instant();
        return voyages.setSail(vessel, epoch.getAsLong(), node, new VoyagesPort.Voyage(port.id(), now))
                ? Result.ok(now)
                : Result.err("tradewinds.hold.elsewhere");
    }

    /**
     * Gives {@code vessel} the rank the file calls {@code rankId}, as an operator does: its trade is set to what the
     * rank takes. Says which rank, or why not.
     */
    public Result<Ranks.Rank, String> setRank(IslandId vessel, String rankId) {
        Optional<Ranks.Rank> rank = ranks.named(rankId);
        if (rank.isEmpty()) {
            return Result.err("isadmin.tradewinds.unknown_rank");
        }
        return orders.setTradeVolume(vessel, rank.get().volume())
                ? Result.ok(rank.get())
                : Result.err("isadmin.tradewinds.not_vessel");
    }

    /** Sets the standing of {@code vessel} in {@code port}, as an operator does. */
    public void setStanding(IslandId vessel, Port port, long standing) {
        if (standing < 0) {
            throw new IllegalArgumentException("a standing is not below 0: " + standing);
        }
        orders.setStanding(vessel, port.id(), standing);
    }

    /** The rank {@code vessel} holds by the trade it did. */
    public Ranks.Rank rank(IslandId vessel) {
        return ranks.of(orders.tradeVolume(vessel));
    }

    /** What {@code port} pays the crew of {@code vessel} for one {@code good}. */
    public long pays(IslandId vessel, Port port, Port.Good good) {
        return paid(port, good, 0, orders.standing(vessel, port.id()));
    }

    /** What the port pays for one {@code good} once {@code after} more were sold to it, before any cap. */
    private long paid(Port port, Port.Good good, int after, long held) {
        return standing.pays(demand.priced(port, prices.pricedAfter(port, good, after), clock.instant()), held);
    }

    /** What the port asks for one {@code good} once {@code after} more were sold to it, before any floor. */
    private long asked(Port port, Port.Good good, int after, long held) {
        return standing.asks(prices.pricedAfter(port, good, after), held);
    }

    /** What {@code port} asks of the crew of {@code vessel} for one {@code good}. */
    public long asks(IslandId vessel, Port port, Port.Good good) {
        long held = orders.standing(vessel, port.id());
        long asks = asked(port, good, 0, held);
        if (!good.bought()) {
            return asks;
        }
        // Always more than the crew would be paid to sell the lot straight back, whatever the day, the drift and
        // the standing add up to. That holds both ways: a lot bought and sold back, or sold and bought back, never
        // comes out ahead, as what the port pays only falls as more of the good is sold to it.
        return Math.max(asks, paid(port, good, -good.lot(), held) + 1);
    }

    /** Sells one lot of {@code good} from the hold of {@code vessel} to {@code port}. */
    public Result<Deal, String> sell(IslandId vessel, PlayerUuid actor, Port port, Port.Good good) {
        if (!good.bought()) {
            return Result.err("tradewinds.market.not_bought");
        }
        if (!takes(port, good.item())) {
            return Result.err("tradewinds.market.contraband");
        }
        ReentrantLock held = lockOf(vessel);
        held.lock();
        try {
            Optional<String> away = away(vessel, port);
            if (away.isPresent()) {
                return Result.err(away.get());
            }
            OptionalLong epoch = lease.hold(vessel);
            if (epoch.isEmpty()) {
                return Result.err("tradewinds.hold.elsewhere");
            }
            Optional<VesselsPort.Cargo> cargo = vessels.cargo(vessel);
            if (cargo.isEmpty()) {
                return Result.err("tradewinds.hold.not_vessel");
            }
            Optional<byte[]> after = goods.take(cargo.get().items(), good.item(), good.lot());
            if (after.isEmpty()) {
                return Result.err("tradewinds.market.too_few");
            }
            long amount = Math.multiplyExact(pays(vessel, port, good), (long) good.lot());
            MarketOrdersPort.Order order = new MarketOrdersPort.Order(
                    UUID.randomUUID(),
                    vessel,
                    port.id(),
                    MarketOrdersPort.Kind.SALE,
                    good.item(),
                    good.lot(),
                    amount,
                    actor,
                    MarketOrdersPort.State.OWED,
                    0);
            if (!orders.recordSale(
                    order,
                    new MarketOrdersPort.HoldWrite(
                            epoch.getAsLong(), node, cargo.get().version(), after.get()))) {
                return Result.err("tradewinds.market.busy");
            }
            // The goods are sold, and the port pays less for the next. The bank pays now, or recovery pays it soon.
            prices.traded(port, good, good.lot());
            if (ask(order, amount, NEW_KEYS_PER_CALL) == Bank.Answer.LANDED) {
                orders.settle(order.id(), MarketOrdersPort.State.OWED, MarketOrdersPort.State.DONE);
            }
            return Result.ok(new Deal(good.item(), good.lot(), amount));
        } finally {
            held.unlock();
        }
    }

    /** Buys one lot of {@code good} from {@code port} into the hold of {@code vessel}. */
    public Result<Deal, String> buy(IslandId vessel, PlayerUuid actor, Port port, Port.Good good) {
        if (!good.sold()) {
            return Result.err("tradewinds.market.not_sold");
        }
        ReentrantLock held = lockOf(vessel);
        held.lock();
        try {
            Optional<String> away = away(vessel, port);
            if (away.isPresent()) {
                return Result.err(away.get());
            }
            if (lease.hold(vessel).isEmpty()) {
                return Result.err("tradewinds.hold.elsewhere");
            }
            Optional<VesselsPort.Cargo> cargo = vessels.cargo(vessel);
            if (cargo.isEmpty()) {
                return Result.err("tradewinds.hold.not_vessel");
            }
            if (goods.stow(cargo.get().items(), rank(vessel).holdSlots(), good.item(), good.lot())
                    .isEmpty()) {
                return Result.err("tradewinds.hold.full");
            }
            long amount = Math.multiplyExact(asks(vessel, port, good), (long) good.lot());
            MarketOrdersPort.Order order = new MarketOrdersPort.Order(
                    UUID.randomUUID(),
                    vessel,
                    port.id(),
                    MarketOrdersPort.Kind.PURCHASE,
                    good.item(),
                    good.lot(),
                    amount,
                    actor,
                    MarketOrdersPort.State.PAYING,
                    0);
            orders.recordPurchase(order);
            return switch (ask(order, -amount, NEW_KEYS_PER_CALL)) {
                case LANDED -> {
                    // Paid for: the port asks more for the next.
                    prices.traded(port, good, -good.lot());
                    yield stow(order);
                }
                case NO_FUNDS -> {
                    orders.settle(order.id(), MarketOrdersPort.State.PAYING, MarketOrdersPort.State.CANCELLED);
                    yield Result.err("tradewinds.market.no_funds");
                }
                case REFUSED, NOT_NOW, UNKNOWN -> Result.err("tradewinds.market.pending");
            };
        } finally {
            held.unlock();
        }
    }

    /**
     * Carries on every order of {@code vessel} a crash or a busy bank left open, while this node writes its
     * bank. Answers how many it settled.
     */
    public int recover(IslandId vessel) {
        if (!bank.here(vessel)) {
            return 0;
        }
        ReentrantLock held = lockOf(vessel);
        held.lock();
        try {
            int settled = 0;
            for (MarketOrdersPort.Order order : orders.open(vessel)) {
                if (carryOn(order)) {
                    settled++;
                }
            }
            return settled;
        } finally {
            held.unlock();
        }
    }

    /** Carries on every open order this node can, of every vessel. Answers how many it settled. */
    public int recoverAll() {
        int settled = 0;
        for (IslandId vessel : orders.vesselsWithOpenOrders()) {
            settled += recover(vessel);
        }
        return settled;
    }

    private boolean carryOn(MarketOrdersPort.Order order) {
        return switch (order.state()) {
            case OWED ->
                ask(order, order.amount(), 1) == Bank.Answer.LANDED
                        && orders.settle(order.id(), MarketOrdersPort.State.OWED, MarketOrdersPort.State.DONE);
            case PAYING ->
                switch (ask(order, -order.amount(), 1)) {
                    case LANDED -> stow(order).isOk();
                    case NO_FUNDS ->
                        orders.settle(order.id(), MarketOrdersPort.State.PAYING, MarketOrdersPort.State.CANCELLED);
                    case REFUSED, NOT_NOW, UNKNOWN -> false;
                };
            case REFUNDING -> refund(order);
            case DONE, CANCELLED, REFUNDED -> false;
        };
    }

    /** Stows what a paid purchase bought, or pays it back when the hold has no room for it. */
    private Result<Deal, String> stow(MarketOrdersPort.Order order) {
        for (int tried = 0; tried < STOW_TRIES; tried++) {
            OptionalLong epoch = lease.hold(order.vessel());
            Optional<VesselsPort.Cargo> cargo = vessels.cargo(order.vessel());
            if (epoch.isEmpty() || cargo.isEmpty()) {
                return Result.err("tradewinds.market.pending");
            }
            Optional<byte[]> after =
                    goods.stow(cargo.get().items(), rank(order.vessel()).holdSlots(), order.item(), order.count());
            if (after.isEmpty()) {
                if (orders.settle(order.id(), MarketOrdersPort.State.PAYING, MarketOrdersPort.State.REFUNDING)) {
                    refund(order);
                }
                return Result.err("tradewinds.market.refunded");
            }
            if (orders.deliver(
                    order.id(),
                    new MarketOrdersPort.HoldWrite(
                            epoch.getAsLong(), node, cargo.get().version(), after.get()))) {
                return Result.ok(new Deal(order.item(), order.count(), order.amount()));
            }
            // The hold moved under the purchase, or another hand delivered it: look again.
            if (orders.find(order.id())
                    .map(found -> found.state() == MarketOrdersPort.State.DONE)
                    .orElse(false)) {
                return Result.ok(new Deal(order.item(), order.count(), order.amount()));
            }
        }
        return Result.err("tradewinds.market.pending");
    }

    private boolean refund(MarketOrdersPort.Order order) {
        MarketOrdersPort.Order refunding = orders.find(order.id()).orElse(order);
        return askUnder(refunding, order.amount(), 1, "refund") == Bank.Answer.LANDED
                && orders.settle(order.id(), MarketOrdersPort.State.REFUNDING, MarketOrdersPort.State.REFUNDED);
    }

    private Bank.Answer ask(MarketOrdersPort.Order order, long delta, int newKeys) {
        return askUnder(order, delta, newKeys, delta >= 0 ? "pay" : "charge");
    }

    /**
     * Asks the bank for {@code delta} under the order's keys. Every key asked before is asked again first,
     * and answers as it did; a new key is used only once every earlier one was refused outright, and is
     * written down before it is used.
     */
    private Bank.Answer askUnder(MarketOrdersPort.Order order, long delta, int newKeys, String purpose) {
        String reason = "TradeWinds " + (order.kind() == MarketOrdersPort.Kind.SALE ? "sale" : "purchase") + " at "
                + order.portId();
        int attempts = order.attempts();
        for (int attempt = 1; attempt <= attempts; attempt++) {
            Bank.Answer answer = askOnce(order, delta, reason, purpose, attempt);
            if (answer != Bank.Answer.REFUSED) {
                return answer;
            }
        }
        for (int fresh = 0; fresh < newKeys; fresh++) {
            attempts++;
            orders.attempt(order.id(), attempts);
            Bank.Answer answer = askOnce(order, delta, reason, purpose, attempts);
            if (answer != Bank.Answer.REFUSED) {
                return answer;
            }
        }
        return Bank.Answer.REFUSED;
    }

    private Bank.Answer askOnce(MarketOrdersPort.Order order, long delta, String reason, String purpose, int attempt) {
        try {
            return bank.move(order.vessel(), order.actor(), delta, reason, order.id() + ":" + purpose + ":" + attempt);
        } catch (RuntimeException unanswered) {
            return Bank.Answer.UNKNOWN;
        }
    }

    private Optional<String> away(IslandId vessel, Port port) {
        return switch (where(vessel)) {
            case Where.Docked docked when docked.portId().equals(port.id()) -> Optional.empty();
            case Where.Sailing sailing -> Optional.of("tradewinds.market.at_sea");
            default -> Optional.of("tradewinds.market.not_docked");
        };
    }

    /** Forgets what is kept for {@code vessel}, an island that is gone: its lock, unless an order holds it. */
    public void forget(IslandId islandId) {
        ReentrantLock lock = locks.get(islandId);
        if (lock == null || !lock.isLocked()) {
            // An order that takes a new lock after this is still written over versions and states.
            locks.remove(islandId);
        }
    }

    private ReentrantLock lockOf(IslandId vessel) {
        return locks.computeIfAbsent(vessel, key -> new ReentrantLock());
    }
}
