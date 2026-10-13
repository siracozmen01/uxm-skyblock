package com.uxplima.uxmskyblock.core.application.tradewinds;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.random.RandomGenerator;

import com.uxplima.uxmskyblock.core.application.tradewinds.VoyageEventsPort.Hazard;
import com.uxplima.uxmskyblock.core.application.tradewinds.VoyageEventsPort.Planned;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;

/**
 * What may happen to a vessel at sea. As it sets sail each kind of event is rolled once, by the operator's chance,
 * and the ones that come are given a moment of the voyage. When the moment comes, a storm makes the voyage longer,
 * pirates take a toll from the island bank or board when it cannot pay, and a find fills the hold from a loot table.
 *
 * <p>A lawful port may also send customs aboard as a vessel arrives, as often as its chance says: what the hold
 * carries of the goods the operator marks as contraband is seized, and the vessel's standing in that port falls.
 *
 * <p>Each event happens once, on the node that holds the vessel's lease; one of a voyage that ended another way, as
 * when an operator moved the vessel, comes to nothing. Everything here reads and writes durable storage: call it off
 * the main thread.
 */
public final class SeaHazards {

    /** How many times a find tries to write the hold while it moves under it. */
    private static final int STOW_TRIES = 3;

    /** Who the bank names as moving a toll when the vessel's captain cannot be read. */
    private static final PlayerUuid NOBODY = PlayerUuid.of(new UUID(0L, 0L));

    /**
     * How likely each event is on one voyage, from 0 to 1, and what it does.
     *
     * @param storm how likely a storm is
     * @param stormDelay how much longer a storm makes the voyage
     * @param pirates how likely pirates are
     * @param toll what pirates take from the island bank, in its minor units; 0 and they always board
     * @param find how likely a find is
     */
    public record Chances(double storm, Duration stormDelay, double pirates, long toll, double find) {

        /** Nothing ever happens at sea. */
        public static final Chances NONE = new Chances(0, Duration.ZERO, 0, 0, 0);

        public Chances {
            Objects.requireNonNull(stormDelay, "stormDelay");
            for (double chance : new double[] {storm, pirates, find}) {
                if (!(chance >= 0 && chance <= 1)) {
                    throw new IllegalArgumentException("a chance is from 0 to 1: " + chance);
                }
            }
            if (stormDelay.isNegative() || stormDelay.compareTo(Duration.ofDays(1)) > 0) {
                throw new IllegalArgumentException("a storm costs from nothing to a day: " + stormDelay);
            }
            if (toll < 0) {
                throw new IllegalArgumentException("a toll is not below 0: " + toll);
            }
        }

        double of(Hazard hazard) {
            return switch (hazard) {
                case STORM -> storm;
                case PIRATES -> pirates;
                case FIND -> find;
                case CUSTOMS -> 0;
            };
        }
    }

    /**
     * The goods only some ports take, and the customs of the ports that do not.
     *
     * @param contraband the items the operator marks as contraband
     * @param chances how likely customs are to board a vessel arriving at each lawful port, by its key
     * @param standingLost how much standing a vessel caught with contraband loses in that port
     */
    public record Customs(Set<String> contraband, Map<String, Double> chances, long standingLost) {

        /** No contraband and no customs. */
        public static final Customs NONE = new Customs(Set.of(), Map.of(), 0);

        public Customs {
            contraband = Set.copyOf(contraband);
            chances = Map.copyOf(chances);
            for (double chance : chances.values()) {
                if (!(chance >= 0 && chance <= 1)) {
                    throw new IllegalArgumentException("a chance is from 0 to 1: " + chance);
                }
            }
            if (standingLost < 0) {
                throw new IllegalArgumentException("standing lost is not below 0: " + standingLost);
            }
        }

        double chanceAt(String portId) {
            return chances.getOrDefault(portId, 0.0);
        }
    }

    /** What an event did when its moment came. */
    public sealed interface Struck {

        /** The event that came. */
        Planned event();

        /** A storm: the voyage lost {@code lost} and arrives at {@code arrives}. */
        record Storm(Planned event, Duration lost, Instant arrives) implements Struck {}

        /** Pirates took {@code paid} from the island bank and went. */
        record Toll(Planned event, long paid) implements Struck {}

        /** Pirates boarded: the bank could not, or would not, pay. */
        record Boarded(Planned event) implements Struck {}

        /** A find, waiting to be rolled and stowed by {@link #stow}. */
        record Find(Planned event) implements Struck {}

        /**
         * Customs looked through the hold: {@code seized} is what they took of each contraband item, empty when the
         * hold carried none, and the vessel's standing in the port fell by {@code standingLost}.
         */
        record Inspected(Planned event, Map<String, Integer> seized, long standingLost) implements Struck {
            public Inspected {
                seized = Map.copyOf(seized);
            }
        }
    }

    /** What became of a find. */
    public enum Stowed {
        /** It went into the hold. */
        STOWED,
        /** The hold had no room for it, and it was lost. */
        NO_ROOM,
        /** Another call took it first, or the hold could not be written now. */
        NOT_NOW
    }

    private final VoyageEventsPort port;
    private final PortMarket market;
    private final VoyagesPort voyages;
    private final VesselsPort vessels;
    private final VesselLease lease;
    private final PortMarket.Bank bank;
    private final ServerNodeId node;
    private final Clock clock;
    private final RandomGenerator random;
    private final Function<IslandId, Optional<PlayerUuid>> captainOf;
    private final Chances chances;
    private final PortMarket.Goods goods;
    private final MarketOrdersPort orders;
    private final Customs customs;

    /** @param captainOf who the bank names as paying a vessel's toll */
    @SuppressWarnings("TooManyParameters")
    public SeaHazards(
            VoyageEventsPort port,
            PortMarket market,
            VoyagesPort voyages,
            VesselsPort vessels,
            VesselLease lease,
            PortMarket.Bank bank,
            ServerNodeId node,
            Clock clock,
            RandomGenerator random,
            Function<IslandId, Optional<PlayerUuid>> captainOf,
            Chances chances,
            PortMarket.Goods goods,
            MarketOrdersPort orders,
            Customs customs) {
        this.port = Objects.requireNonNull(port, "port");
        this.market = Objects.requireNonNull(market, "market");
        this.voyages = Objects.requireNonNull(voyages, "voyages");
        this.vessels = Objects.requireNonNull(vessels, "vessels");
        this.lease = Objects.requireNonNull(lease, "lease");
        this.bank = Objects.requireNonNull(bank, "bank");
        this.node = Objects.requireNonNull(node, "node");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.random = Objects.requireNonNull(random, "random");
        this.captainOf = Objects.requireNonNull(captainOf, "captainOf");
        this.chances = Objects.requireNonNull(chances, "chances");
        this.goods = Objects.requireNonNull(goods, "goods");
        this.orders = Objects.requireNonNull(orders, "orders");
        this.customs = Objects.requireNonNull(customs, "customs");
    }

    /**
     * Rolls the events of a voyage {@code vessel} set out on at {@code sailed} for {@code portId}, arriving at
     * {@code arrives}, each at a moment of it, in place of any it still held. Answers those that will come.
     */
    public List<Planned> plan(IslandId vessel, String portId, Instant sailed, Instant arrives) {
        List<Planned> planned = new ArrayList<>();
        long span = Duration.between(sailed, arrives).toMillis();
        if (span > 0) {
            for (Hazard hazard : Hazard.values()) {
                if (random.nextDouble() < chances.of(hazard)) {
                    planned.add(new Planned(vessel, hazard, portId, sailed.plusMillis(random.nextLong(span))));
                }
            }
        }
        // Customs board as the vessel arrives, however long the voyage took.
        if (random.nextDouble() < customs.chanceAt(portId)) {
            planned.add(new Planned(vessel, Hazard.CUSTOMS, portId, arrives));
        }
        port.plan(vessel, planned);
        return List.copyOf(planned);
    }

    /** Lets every event whose moment came happen, once each. Answers what each did; a find waits for its stowing. */
    public List<Struck> strike() {
        List<Struck> struck = new ArrayList<>();
        for (Planned event : port.due(clock.instant())) {
            OptionalLong epoch = lease.hold(event.vessel());
            if (epoch.isEmpty()) {
                // Another node holds the vessel: the event is that node's.
                continue;
            }
            if (event.hazard() == Hazard.CUSTOMS) {
                inspect(event, epoch.getAsLong()).ifPresent(struck::add);
                continue;
            }
            Optional<VoyagesPort.Voyage> sailing = heldBy(event);
            if (sailing.isEmpty()) {
                // The voyage ended another way: the event comes to nothing.
                port.claim(event);
                continue;
            }
            if (event.hazard() == Hazard.FIND) {
                struck.add(new Struck.Find(event));
                continue;
            }
            if (!port.claim(event)) {
                continue;
            }
            struck.add(
                    event.hazard() == Hazard.STORM ? storm(event, sailing.get(), epoch.getAsLong()) : pirates(event));
        }
        return List.copyOf(struck);
    }

    /**
     * Customs look through the hold of a vessel that arrived where they wait, and take its contraband in the same
     * transaction that takes them away. Nothing yet while the vessel is still at sea, as after a storm; nothing ever
     * for a voyage bound elsewhere now.
     */
    private Optional<Struck> inspect(Planned event, long epoch) {
        Optional<VoyagesPort.Voyage> voyage =
                voyages.voyage(event.vessel()).filter(one -> one.portId().equals(event.portId()));
        if (voyage.isEmpty()) {
            port.claim(event);
            return Optional.empty();
        }
        if (voyage.get().arrivesAt().isAfter(clock.instant())) {
            return Optional.empty();
        }
        Optional<VesselsPort.Cargo> cargo = vessels.cargo(event.vessel());
        if (cargo.isEmpty()) {
            return Optional.empty();
        }
        byte[] hold = cargo.get().items();
        Map<String, Integer> seized = new java.util.TreeMap<>();
        for (String item : customs.contraband()) {
            int count = goods.count(hold, item);
            Optional<byte[]> without = count == 0 ? Optional.empty() : goods.take(hold, item, count);
            if (without.isPresent()) {
                hold = without.get();
                seized.put(item, count);
            }
        }
        if (seized.isEmpty()) {
            return port.claim(event) ? Optional.of(new Struck.Inspected(event, Map.of(), 0)) : Optional.empty();
        }
        if (!port.stow(
                event, new MarketOrdersPort.HoldWrite(epoch, node, cargo.get().version(), hold))) {
            // The hold moved under the customs, or another call took them: the next watch looks again.
            return Optional.empty();
        }
        long standing = orders.standing(event.vessel(), event.portId());
        long lost = Math.min(standing, customs.standingLost());
        orders.setStanding(event.vessel(), event.portId(), standing - lost);
        return Optional.of(new Struck.Inspected(event, seized, lost));
    }

    /**
     * The voyage {@code event} belongs to: bound for its port and still at sea at its moment. A watch that comes a
     * little after the vessel arrived still finds it, as the event happened on the way.
     */
    private Optional<VoyagesPort.Voyage> heldBy(Planned event) {
        return voyages.voyage(event.vessel())
                .filter(voyage -> voyage.portId().equals(event.portId())
                        && voyage.arrivesAt().isAfter(event.at()));
    }

    private Struck storm(Planned event, VoyagesPort.Voyage sailing, long epoch) {
        Instant later = sailing.arrivesAt().plus(chances.stormDelay());
        boolean held = voyages.setSail(event.vessel(), epoch, node, new VoyagesPort.Voyage(sailing.portId(), later));
        return new Struck.Storm(event, held ? chances.stormDelay() : Duration.ZERO, held ? later : sailing.arrivesAt());
    }

    private Struck pirates(Planned event) {
        if (chances.toll() == 0) {
            return new Struck.Boarded(event);
        }
        PortMarket.Bank.Answer answer;
        try {
            answer = bank.move(
                    event.vessel(),
                    captainOf.apply(event.vessel()).orElse(NOBODY),
                    -chances.toll(),
                    "TradeWinds pirates",
                    "pirates:" + event.vessel().value() + ":" + event.at().toEpochMilli());
        } catch (RuntimeException unanswered) {
            answer = PortMarket.Bank.Answer.UNKNOWN;
        }
        return answer == PortMarket.Bank.Answer.LANDED
                ? new Struck.Toll(event, chances.toll())
                : new Struck.Boarded(event);
    }

    /**
     * Stows a find into the hold: {@code fill} answers the hold of the given slots with the find in it, or nothing
     * when it has no room. A find with no room is lost, once.
     */
    public Stowed stow(Struck.Find find, BiFunction<byte[], Integer, Optional<byte[]>> fill) {
        Planned event = find.event();
        for (int tried = 0; tried < STOW_TRIES; tried++) {
            OptionalLong epoch = lease.hold(event.vessel());
            Optional<VesselsPort.Cargo> cargo = vessels.cargo(event.vessel());
            if (epoch.isEmpty() || cargo.isEmpty()) {
                return Stowed.NOT_NOW;
            }
            Optional<byte[]> after =
                    fill.apply(cargo.get().items(), market.rank(event.vessel()).holdSlots());
            if (after.isEmpty()) {
                return port.claim(event) ? Stowed.NO_ROOM : Stowed.NOT_NOW;
            }
            if (port.stow(
                    event,
                    new MarketOrdersPort.HoldWrite(
                            epoch.getAsLong(), node, cargo.get().version(), after.get()))) {
                return Stowed.STOWED;
            }
            // The hold moved under the find, or another call took it: look again.
        }
        return Stowed.NOT_NOW;
    }
}
