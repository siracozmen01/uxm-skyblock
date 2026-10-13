package com.uxplima.uxmskyblock.core.application.tradewinds;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.application.gamemode.RootAuthorityPort;
import com.uxplima.uxmskyblock.core.domain.gamemode.AuthorityRoot;
import com.uxplima.uxmskyblock.core.domain.gamemode.RootAuthorityRecord;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.island.IslandAuthorityOutcome;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * An order at a port's market moves the goods and the money once: a refused bank, a bank that does not
 * answer, a crash between the steps or a hold that fills up never leaves the goods in two places or the
 * money moved twice.
 */
class AnOrderMovesMoneyOnceTest {

    private static final Instant NOW = Instant.parse("2026-10-08T12:00:00Z");
    private static final ServerNodeId NODE = ServerNodeId.of("node-a");
    private static final Port.Good WHEAT = new Port.Good("WHEAT", 10, 20, 50);
    private static final Port.Good SILK = new Port.Good("SILK", 5, 0, 300);
    private static final Port BAY = new Port("emerald-bay", 60, List.of(WHEAT, SILK));
    private static final Port COVE = new Port("cove", 0, List.of(WHEAT));
    private static final Ranks RANKS =
            new Ranks(List.of(new Ranks.Rank("dinghy", 0, 1), new Ranks.Rank("sloop", 1_000, 2)));

    private final IslandId vessel = IslandId.of(UUID.randomUUID());
    private final PlayerUuid ada = PlayerUuid.of(UUID.randomUUID());
    private final Hold hold = new Hold();
    private final Orders orders = new Orders();
    private final Voyages voyages = new Voyages();
    private final FakeBank bank = new FakeBank();
    private Instant now = NOW;

    @Test
    @DisplayName("A vessel sails, is at sea until it arrives, and trades only at the port it lies in")
    void aVesselSailsAndArrives() {
        PortMarket market = market();
        assertThat(market.where(vessel)).isEqualTo(new PortMarket.Where.Adrift());
        assertThat(market.sell(vessel, ada, BAY, WHEAT).errorOrThrow()).isEqualTo("tradewinds.market.not_docked");

        assertThat(market.sail(vessel, BAY).orElseThrow()).isEqualTo(NOW.plusSeconds(60));
        assertThat(market.where(vessel)).isEqualTo(new PortMarket.Where.Sailing("emerald-bay", NOW.plusSeconds(60)));
        assertThat(market.sell(vessel, ada, BAY, WHEAT).errorOrThrow()).isEqualTo("tradewinds.market.at_sea");

        now = NOW.plusSeconds(60);
        assertThat(market().where(vessel)).isEqualTo(new PortMarket.Where.Docked("emerald-bay"));
        assertThat(market().sail(vessel, BAY).errorOrThrow()).isEqualTo("tradewinds.sail.already_there");
        assertThat(market().sell(vessel, ada, COVE, WHEAT).errorOrThrow()).isEqualTo("tradewinds.market.not_docked");
    }

    @Test
    @DisplayName("An operator puts a vessel in a port at once, gives it a rank and sets its standing in a port")
    void anOperatorsVessel() {
        PortMarket market = market();

        assertThat(market.moveTo(vessel, BAY).orElseThrow()).isEqualTo(NOW);
        assertThat(market.where(vessel)).isEqualTo(new PortMarket.Where.Docked("emerald-bay"));
        assertThat(market.moveTo(vessel, BAY).isOk())
                .describedAs("moving it where it lies is no harm")
                .isTrue();

        assertThat(market.setRank(vessel, "sloop").orElseThrow().id()).isEqualTo("sloop");
        assertThat(market.rank(vessel).id()).isEqualTo("sloop");
        assertThat(orders.tradeVolume(vessel)).isEqualTo(1_000);
        assertThat(market.setRank(vessel, "dinghy").orElseThrow().id()).isEqualTo("dinghy");
        assertThat(market.rank(vessel).id()).isEqualTo("dinghy");
        assertThat(market.setRank(vessel, "galleon").errorOrThrow()).isEqualTo("isadmin.tradewinds.unknown_rank");

        market.setStanding(vessel, BAY, 2_500);
        assertThat(orders.standing(vessel, "emerald-bay")).isEqualTo(2_500);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> market.setStanding(vessel, BAY, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("A sale takes a lot from the hold and the bank pays for it once")
    void aSale() {
        docked();
        hold.items.put("WHEAT", 25);

        PortMarket.Deal deal = market().sell(vessel, ada, BAY, WHEAT).orElseThrow();

        assertThat(deal).isEqualTo(new PortMarket.Deal("WHEAT", 10, 200));
        assertThat(hold.items).containsEntry("WHEAT", 15);
        assertThat(bank.balance).isEqualTo(200);
        assertThat(only().state()).isEqualTo(MarketOrdersPort.State.DONE);
        assertThat(orders.volume).isEqualTo(200);
        assertThat(orders.standing).containsEntry("emerald-bay", 200L);
        assertThat(market().sell(vessel, ada, BAY, SILK).errorOrThrow()).isEqualTo("tradewinds.market.not_bought");
    }

    @Test
    @DisplayName("A sale pushes what the port pays down for the next, and a purchase pushes what it asks up")
    void pricesMove() {
        docked();
        hold.items.put("WHEAT", 30);
        MarketPrices prices = new MarketPrices(
                new MarketPricesPort() {
                    private final Map<String, Pressure> rows = new HashMap<>();

                    @Override
                    public Optional<Pressure> find(String portId, String item) {
                        return Optional.ofNullable(rows.get(portId + item));
                    }

                    @Override
                    public List<Pressure> findAll() {
                        return List.copyOf(rows.values());
                    }

                    @Override
                    public boolean keep(Pressure next) {
                        rows.put(next.portId() + next.item(), next);
                        return true;
                    }
                },
                new PriceDrift(0.01, 0.5, 1.5, java.time.Duration.ofHours(1)),
                Clock.fixed(now, ZoneOffset.UTC),
                java.time.Duration.ZERO);
        PortMarket market = market();
        market.usePrices(prices);

        assertThat(market.sell(vessel, ada, BAY, WHEAT).orElseThrow().amount())
                .describedAs("a lot is paid at the price it leaves: a tenth less for its ten")
                .isEqualTo(180);
        assertThat(market.sell(vessel, ada, BAY, WHEAT).orElseThrow().amount())
                .describedAs("ten sold: a tenth less again")
                .isEqualTo(160);
        assertThat(market.asks(vessel, BAY, WHEAT)).isEqualTo(45);

        bank.balance = 10_000;
        assertThat(market.buy(vessel, ada, BAY, WHEAT).orElseThrow().amount()).isEqualTo(450);
        assertThat(market.asks(vessel, BAY, WHEAT))
                .describedAs("ten bought back")
                .isEqualTo(50);
    }

    @Test
    @DisplayName("A good the port wants today is paid the more, and said so")
    void wantedToday() {
        PortMarket market = market();
        assertThat(market.wanted(BAY, "WHEAT")).isFalse();
        market.useDemand(new DailyDemand(1, 0.5, java.time.ZoneId.of("UTC")));

        assertThat(market.wanted(BAY, "WHEAT")).isTrue();
        assertThat(market.wanted(BAY, "SILK")).describedAs("not a good it buys").isFalse();
        assertThat(market.pays(vessel, BAY, WHEAT)).isEqualTo(30);
        assertThat(market.asks(vessel, BAY, WHEAT))
                .describedAs("what it asks stays")
                .isEqualTo(50);
        market.useDemand(new DailyDemand(1, 2, java.time.ZoneId.of("UTC")));
        assertThat(market.pays(vessel, BAY, WHEAT))
                .describedAs("never as much as it asks")
                .isEqualTo(49);
    }

    @Test
    @DisplayName("Contraband is bought only at the ports that take it, and every other good anywhere")
    void contraband() {
        docked();
        hold.items.put("WHEAT", 10);
        PortMarket market = market();
        market.useContraband(Set.of("WHEAT"), Set.of("cove"));

        assertThat(market.sell(vessel, ada, BAY, WHEAT).errorOrThrow()).isEqualTo("tradewinds.market.contraband");
        assertThat(hold.items).containsEntry("WHEAT", 10);
        assertThat(orders.orders).isEmpty();
        assertThat(market.takes(COVE, "WHEAT")).isTrue();
        assertThat(market.takes(BAY, "SILK")).isTrue();
        assertThat(market().takes(BAY, "WHEAT"))
                .describedAs("no contraband written")
                .isTrue();
    }

    @Test
    @DisplayName("A bank that refuses a sale leaves it owed, and recovery pays it once under a new key")
    void aRefusedSaleIsPaidLater() {
        docked();
        hold.items.put("WHEAT", 10);
        bank.next(PortMarket.Bank.Answer.REFUSED, PortMarket.Bank.Answer.REFUSED, PortMarket.Bank.Answer.REFUSED);

        assertThat(market().sell(vessel, ada, BAY, WHEAT).isOk()).isTrue();
        assertThat(hold.items).doesNotContainKey("WHEAT");
        assertThat(only().state()).isEqualTo(MarketOrdersPort.State.OWED);
        assertThat(only().attempts()).isEqualTo(3);
        assertThat(bank.balance).isZero();

        assertThat(market().recover(vessel)).isEqualTo(1);

        assertThat(bank.balance).isEqualTo(200);
        assertThat(bank.asked).endsWith(only().id() + ":pay:4");
        assertThat(only().state()).isEqualTo(MarketOrdersPort.State.DONE);
    }

    @Test
    @DisplayName("A sale whose payment landed without an answer is not paid again")
    void anUnansweredPaymentIsNotRepeated() {
        docked();
        hold.items.put("WHEAT", 10);
        bank.next(PortMarket.Bank.Answer.UNKNOWN);
        bank.landsAnyway = true;

        market().sell(vessel, ada, BAY, WHEAT);
        assertThat(only().state()).isEqualTo(MarketOrdersPort.State.OWED);
        market().recover(vessel);

        assertThat(bank.balance).isEqualTo(200);
        assertThat(bank.asked).containsExactly(only().id() + ":pay:1", only().id() + ":pay:1");
        assertThat(only().state()).isEqualTo(MarketOrdersPort.State.DONE);
    }

    @Test
    @DisplayName("A key the bank refused before writing it down is asked again, never passed over")
    void aKeyNotWrittenIsAskedAgain() {
        docked();
        hold.items.put("WHEAT", 10);
        bank.next(PortMarket.Bank.Answer.NOT_NOW, PortMarket.Bank.Answer.NOT_NOW);
        bank.forgets = true;

        market().sell(vessel, ada, BAY, WHEAT);
        assertThat(only().attempts()).isEqualTo(1);
        market().recover(vessel);
        assertThat(only().state()).isEqualTo(MarketOrdersPort.State.OWED);
        market().recover(vessel);

        assertThat(bank.asked).containsExactly(only().id() + ":pay:1", only().id() + ":pay:1", only().id() + ":pay:1");
        assertThat(bank.balance).isEqualTo(200);
        assertThat(only().state()).isEqualTo(MarketOrdersPort.State.DONE);
    }

    @Test
    @DisplayName("A purchase is paid once and stowed, and one the bank cannot pay changes nothing")
    void aPurchase() {
        docked();
        bank.balance = 600;

        assertThat(market().buy(vessel, ada, BAY, WHEAT).orElseThrow())
                .isEqualTo(new PortMarket.Deal("WHEAT", 10, 500));
        assertThat(hold.items).containsEntry("WHEAT", 10);
        assertThat(bank.balance).isEqualTo(100);

        assertThat(market().buy(vessel, ada, BAY, WHEAT).errorOrThrow()).isEqualTo("tradewinds.market.no_funds");
        assertThat(hold.items).containsEntry("WHEAT", 10);
        assertThat(bank.balance).isEqualTo(100);
        assertThat(orders.orders.values())
                .extracting(MarketOrdersPort.Order::state)
                .containsExactly(MarketOrdersPort.State.DONE, MarketOrdersPort.State.CANCELLED);
    }

    @Test
    @DisplayName("A purchase paid without an answer is stowed by recovery, and charged once")
    void anUnansweredPurchaseIsStowedLater() {
        docked();
        bank.balance = 500;
        bank.next(PortMarket.Bank.Answer.UNKNOWN);
        bank.landsAnyway = true;

        assertThat(market().buy(vessel, ada, BAY, WHEAT).errorOrThrow()).isEqualTo("tradewinds.market.pending");
        assertThat(hold.items).doesNotContainKey("WHEAT");

        market().recover(vessel);

        assertThat(hold.items).containsEntry("WHEAT", 10);
        assertThat(bank.balance).isZero();
        assertThat(only().state()).isEqualTo(MarketOrdersPort.State.DONE);
    }

    @Test
    @DisplayName("A purchase whose hold filled up while it was paid for is paid back, once")
    void aFullHoldIsPaidBack() {
        docked();
        bank.balance = 500;
        bank.whenCharged = () -> hold.items.put("STONE", Hold.ROOM - 5);

        assertThat(market().buy(vessel, ada, BAY, WHEAT).errorOrThrow()).isEqualTo("tradewinds.market.refunded");

        assertThat(bank.balance).isEqualTo(500);
        assertThat(hold.items).doesNotContainKey("WHEAT");
        assertThat(only().state()).isEqualTo(MarketOrdersPort.State.REFUNDED);
        assertThat(bank.asked).containsExactly(only().id() + ":charge:1", only().id() + ":refund:1");
        assertThat(market().recover(vessel)).isZero();
        assertThat(bank.balance).isEqualTo(500);
    }

    @Test
    @DisplayName("Trade raises a vessel's rank, and a higher rank's hold has room for more")
    void aRankGrowsTheHold() {
        docked();
        hold.items.put("WHEAT", 50);
        PortMarket market = market();
        assertThat(market.rank(vessel).id()).isEqualTo("dinghy");

        for (int sale = 0; sale < 5; sale++) {
            assertThat(market.sell(vessel, ada, BAY, WHEAT).isOk()).isTrue();
        }
        assertThat(market.rank(vessel).id()).isEqualTo("sloop");

        hold.items.put("STONE", Hold.ROOM - 5);
        bank.balance = 500;
        assertThat(market.buy(vessel, ada, BAY, WHEAT).isOk())
                .describedAs("a dinghy's hold would have no room for it")
                .isTrue();
        assertThat(new TradeVolumeMetric(orders).read(5))
                .singleElement()
                .satisfies(reading -> assertThat(reading.value()).isEqualTo(1_500));
    }

    @Test
    @DisplayName("Ranks start from no trade, each takes more trade than the last, and none shrinks the hold")
    void ranksAreChecked() {
        assertThat(RANKS.of(999).id()).isEqualTo("dinghy");
        assertThat(RANKS.of(1_000).id()).isEqualTo("sloop");
        assertThat(RANKS.after(RANKS.of(0))).contains(RANKS.of(1_000));
        assertThat(RANKS.after(RANKS.of(1_000))).isEmpty();
        assertThat(RANKS.of(0).holdSlots()).isEqualTo(9);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new Ranks(List.of(new Ranks.Rank("a", 5, 1))))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new Ranks(List.of(new Ranks.Rank("a", 0, 1), new Ranks.Rank("b", 0, 2))))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new Ranks(List.of(new Ranks.Rank("a", 0, 3), new Ranks.Rank("b", 10, 2))))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new Ranks(List.of(new Ranks.Rank("a", 0, 1), new Ranks.Rank("a", 10, 2))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Standing lowers what a port asks and raises what it pays, closing at most half the gap")
    void standingMovesPrices() {
        Standing standing = new Standing(1000, 5, 4);
        Port.Good wide = new Port.Good("WHEAT", 1, 100, 200);
        Port.Good narrow = new Port.Good("WHEAT", 1, 100, 110);
        Port.Good soldOnly = new Port.Good("SILK", 1, 0, 300);

        assertThat(standing.steps(999)).isZero();
        assertThat(standing.steps(2500)).isEqualTo(2);
        assertThat(standing.steps(1_000_000)).isEqualTo(4);
        assertThat(standing.pays(wide, 0)).isEqualTo(100);
        assertThat(standing.asks(wide, 0)).isEqualTo(200);
        assertThat(standing.pays(wide, 1000)).isEqualTo(105);
        assertThat(standing.asks(wide, 1000)).isEqualTo(190);
        assertThat(standing.pays(wide, 1_000_000)).isEqualTo(120);
        assertThat(standing.asks(wide, 1_000_000)).isEqualTo(175);
        assertThat(standing.asks(narrow, 1_000_000) - standing.pays(narrow, 1_000_000))
                .describedAs("a round trip still costs half the gap")
                .isEqualTo(6);
        assertThat(standing.asks(soldOnly, 1_000_000)).isEqualTo(240);
        assertThat(Standing.NONE.pays(wide, 1_000_000)).isEqualTo(100);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new Port.Good("WHEAT", 1, 100, 100))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void docked() {
        voyages.voyages.put(vessel, new VoyagesPort.Voyage(BAY.id(), NOW.minusSeconds(1)));
    }

    private MarketOrdersPort.Order only() {
        assertThat(orders.orders).hasSize(1);
        return orders.orders.values().iterator().next();
    }

    private PortMarket market() {
        return new PortMarket(
                hold,
                voyages,
                orders,
                new VesselLease(new Leases(), NODE, Clock.fixed(now, ZoneOffset.UTC)),
                bank,
                hold,
                Standing.NONE,
                RANKS,
                NODE,
                Clock.fixed(now, ZoneOffset.UTC));
    }

    /** A hold of named counts, written as text, with room for {@link #ROOM} items. */
    private final class Hold implements VesselsPort, PortMarket.Goods {
        static final int ROOM = 100;
        private final Map<String, Integer> items = new LinkedHashMap<>();
        private long version = 1;

        @Override
        public Set<IslandId> findAll() {
            return Set.of(vessel);
        }

        @Override
        public boolean exists(IslandId islandId) {
            return vessel.equals(islandId);
        }

        @Override
        public void add(IslandId islandId) {}

        @Override
        public Optional<Cargo> cargo(IslandId islandId) {
            return Optional.of(new Cargo(write(items), version));
        }

        @Override
        public int count(byte[] stored, String item) {
            return read(stored).getOrDefault(item, 0);
        }

        @Override
        public Optional<byte[]> take(byte[] stored, String item, int count) {
            Map<String, Integer> now = read(stored);
            int have = now.getOrDefault(item, 0);
            if (have < count) {
                return Optional.empty();
            }
            if (have == count) {
                now.remove(item);
            } else {
                now.put(item, have - count);
            }
            return Optional.of(write(now));
        }

        @Override
        public Optional<byte[]> stow(byte[] stored, int slots, String item, int count) {
            Map<String, Integer> now = read(stored);
            int total = now.values().stream().mapToInt(Integer::intValue).sum();
            if (total + count > ROOM * slots / 9) {
                return Optional.empty();
            }
            now.merge(item, count, Integer::sum);
            return Optional.of(write(now));
        }

        void writeOver(long expected, byte[] after) {
            assertThat(expected).isEqualTo(version);
            items.clear();
            items.putAll(read(after));
            version++;
        }

        private static byte[] write(Map<String, Integer> items) {
            StringBuilder text = new StringBuilder();
            items.forEach(
                    (item, count) -> text.append(item).append('=').append(count).append(';'));
            return text.toString().getBytes(StandardCharsets.UTF_8);
        }

        private static Map<String, Integer> read(byte[] stored) {
            Map<String, Integer> items = new LinkedHashMap<>();
            String text = new String(stored, StandardCharsets.UTF_8);
            int from = 0;
            while (from < text.length()) {
                int end = text.indexOf(';', from);
                int equals = text.indexOf('=', from);
                items.put(text.substring(from, equals), Integer.parseInt(text.substring(equals + 1, end)));
                from = end + 1;
            }
            return items;
        }
    }

    private final class Orders implements MarketOrdersPort {
        private final Map<UUID, Order> orders = new LinkedHashMap<>();
        private final Map<String, Long> standing = new HashMap<>();
        private long volume;

        @Override
        public boolean recordSale(Order order, HoldWrite write) {
            if (write.cargoVersion() != hold.version) {
                return false;
            }
            hold.writeOver(write.cargoVersion(), write.cargoAfter());
            orders.put(order.id(), order);
            grow(order);
            return true;
        }

        @Override
        public void recordPurchase(Order order) {
            orders.put(order.id(), order);
        }

        @Override
        public boolean deliver(UUID orderId, HoldWrite write) {
            Order order = orders.get(orderId);
            if (order == null || order.state() != State.PAYING || write.cargoVersion() != hold.version) {
                return false;
            }
            hold.writeOver(write.cargoVersion(), write.cargoAfter());
            orders.put(orderId, with(order, State.DONE, order.attempts()));
            grow(order);
            return true;
        }

        @Override
        public void attempt(UUID orderId, int attempt) {
            Order order = orders.get(orderId);
            if (order != null) {
                orders.put(orderId, with(order, order.state(), attempt));
            }
        }

        @Override
        public boolean settle(UUID orderId, State from, State to) {
            Order order = orders.get(orderId);
            if (order == null || order.state() != from) {
                return false;
            }
            orders.put(orderId, with(order, to, to == State.REFUNDING ? 0 : order.attempts()));
            return true;
        }

        @Override
        public Optional<Order> find(UUID orderId) {
            return Optional.ofNullable(orders.get(orderId));
        }

        @Override
        public List<Order> open(IslandId islandId) {
            return orders.values().stream()
                    .filter(order -> !order.state().settled())
                    .toList();
        }

        @Override
        public List<IslandId> vesselsWithOpenOrders() {
            return open(vessel).isEmpty() ? List.of() : List.of(vessel);
        }

        @Override
        public long standing(IslandId islandId, String portId) {
            return standing.getOrDefault(portId, 0L);
        }

        @Override
        public long tradeVolume(IslandId islandId) {
            return volume;
        }

        @Override
        public boolean setTradeVolume(IslandId islandId, long set) {
            volume = set;
            return true;
        }

        @Override
        public void setStanding(IslandId islandId, String portId, long set) {
            standing.put(portId, set);
        }

        @Override
        public List<Traded> mostTraded(int limit) {
            return volume == 0 ? List.of() : List.of(new Traded(vessel, "vessel", volume));
        }

        private void grow(Order order) {
            volume += order.amount();
            standing.merge(order.portId(), order.amount(), Long::sum);
        }

        private static Order with(Order order, State state, int attempts) {
            return new Order(
                    order.id(),
                    order.vessel(),
                    order.portId(),
                    order.kind(),
                    order.item(),
                    order.count(),
                    order.amount(),
                    order.actor(),
                    state,
                    attempts);
        }
    }

    private static final class Voyages implements VoyagesPort {
        private final Map<IslandId, Voyage> voyages = new HashMap<>();

        @Override
        public Optional<Voyage> voyage(IslandId vessel) {
            return Optional.ofNullable(voyages.get(vessel));
        }

        @Override
        public boolean setSail(IslandId vessel, long leaseEpoch, ServerNodeId node, Voyage voyage) {
            voyages.put(vessel, voyage);
            return true;
        }
    }

    /**
     * A bank that answers each key once and repeats its answer after, the way the island bank does. A new
     * key is answered from {@link #next}, and lands when the script says nothing.
     */
    private static final class FakeBank implements PortMarket.Bank {
        private final Map<String, Answer> answered = new HashMap<>();
        private final Deque<Answer> script = new ArrayDeque<>();
        private final List<String> asked = new ArrayList<>();
        private long balance;
        private boolean landsAnyway;
        private boolean forgets;
        private Runnable whenCharged = () -> {};

        void next(Answer... answers) {
            script.addAll(List.of(answers));
        }

        @Override
        public Answer move(IslandId vessel, PlayerUuid actor, long delta, String reason, String key) {
            asked.add(key);
            Answer before = answered.get(key);
            if (before != null) {
                return before;
            }
            Answer answer = script.isEmpty() ? Answer.LANDED : script.poll();
            if (answer == Answer.NOT_NOW && forgets) {
                // Not written down: the same key is a new question next time.
                return Answer.NOT_NOW;
            }
            if (answer == Answer.UNKNOWN) {
                if (landsAnyway) {
                    balance += delta;
                    answered.put(key, Answer.LANDED);
                }
                return Answer.UNKNOWN;
            }
            if (answer == Answer.LANDED && balance + delta < 0) {
                answer = Answer.NO_FUNDS;
            }
            if (answer == Answer.LANDED) {
                balance += delta;
                if (delta < 0) {
                    whenCharged.run();
                }
            }
            answered.put(key, answer);
            return answer;
        }

        @Override
        public boolean here(IslandId vessel) {
            return true;
        }
    }

    private static final class Leases implements RootAuthorityPort {
        @Override
        public IslandAuthorityOutcome acquire(AuthorityRoot root, ServerNodeId node, int leaseSeconds) {
            return IslandAuthorityOutcome.success(1);
        }

        @Override
        public IslandAuthorityOutcome renew(
                AuthorityRoot root, ServerNodeId node, long expectedEpoch, int leaseSeconds) {
            return IslandAuthorityOutcome.success(expectedEpoch);
        }

        @Override
        public IslandAuthorityOutcome takeover(
                AuthorityRoot root, ServerNodeId newNode, long expectedEpoch, int leaseSeconds) {
            return IslandAuthorityOutcome.success(expectedEpoch + 1);
        }

        @Override
        public Optional<RootAuthorityRecord> find(AuthorityRoot root) {
            return Optional.empty();
        }
    }
}
