package com.uxplima.uxmskyblock.core.application.tradewinds;

import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.locks.ReentrantLock;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.result.Result;

/**
 * A market order stowed in the hold and paid under the bank's answer, and the orders a crash left open carried on. Split out of {@link PortMarket}, whose services and words it uses, so neither file is over the size the
 * standards draw a line at.
 */
final class MarketOrders {

    private final PortMarket host;

    MarketOrders(PortMarket host) {
        this.host = host;
    }

    /** Stows what a paid purchase bought, or pays it back when the hold has no room for it. */
    Result<PortMarket.Deal, String> stow(MarketOrdersPort.Order order) {
        for (int tried = 0; tried < PortMarket.STOW_TRIES; tried++) {
            OptionalLong epoch = host.lease.hold(order.vessel());
            Optional<VesselsPort.Cargo> cargo = host.vessels.cargo(order.vessel());
            if (epoch.isEmpty() || cargo.isEmpty()) {
                return Result.err("tradewinds.market.pending");
            }
            Optional<byte[]> after = host.goods.stow(
                    cargo.get().items(), host.rank(order.vessel()).holdSlots(), order.item(), order.count());
            if (after.isEmpty()) {
                if (host.orders.settle(order.id(), MarketOrdersPort.State.PAYING, MarketOrdersPort.State.REFUNDING)) {
                    host.refund(order);
                }
                return Result.err("tradewinds.market.refunded");
            }
            if (host.orders.deliver(
                    order.id(),
                    new MarketOrdersPort.HoldWrite(
                            epoch.getAsLong(), host.node, cargo.get().version(), after.get()))) {
                return Result.ok(new PortMarket.Deal(order.item(), order.count(), order.amount()));
            }
            // The hold moved under the purchase, or another hand delivered it: look again.
            if (host.orders
                    .find(order.id())
                    .map(found -> found.state() == MarketOrdersPort.State.DONE)
                    .orElse(false)) {
                return Result.ok(new PortMarket.Deal(order.item(), order.count(), order.amount()));
            }
        }
        return Result.err("tradewinds.market.pending");
    }
    /**
     * Asks the bank for {@code delta} under the order's keys. Every key asked before is asked again first,
     * and answers as it did; a new key is used only once every earlier one was refused outright, and is
     * written down before it is used.
     */
    PortMarket.Bank.Answer askUnder(MarketOrdersPort.Order order, long delta, int newKeys, String purpose) {
        String reason = "TradeWinds " + (order.kind() == MarketOrdersPort.Kind.SALE ? "sale" : "purchase") + " at "
                + order.portId();
        int attempts = order.attempts();
        for (int attempt = 1; attempt <= attempts; attempt++) {
            PortMarket.Bank.Answer answer = host.askOnce(order, delta, reason, purpose, attempt);
            if (answer != PortMarket.Bank.Answer.REFUSED) {
                return answer;
            }
        }
        for (int fresh = 0; fresh < newKeys; fresh++) {
            attempts++;
            host.orders.attempt(order.id(), attempts);
            PortMarket.Bank.Answer answer = host.askOnce(order, delta, reason, purpose, attempts);
            if (answer != PortMarket.Bank.Answer.REFUSED) {
                return answer;
            }
        }
        return PortMarket.Bank.Answer.REFUSED;
    }
    /**
     * Carries on every order of {@code vessel} a crash or a busy bank left open, while this node writes its
     * bank. Answers how many it settled.
     */
    public int recover(IslandId vessel) {
        if (!host.bank.here(vessel)) {
            return 0;
        }
        ReentrantLock held = host.lockOf(vessel);
        held.lock();
        try {
            int settled = 0;
            for (MarketOrdersPort.Order order : host.orders.open(vessel)) {
                if (host.carryOn(order)) {
                    settled++;
                }
            }
            return settled;
        } finally {
            held.unlock();
        }
    }
}
