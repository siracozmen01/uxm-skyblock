package com.uxplima.uxmskyblock.core.application.tradewinds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.application.gamemode.RootAuthorityPort;
import com.uxplima.uxmskyblock.core.application.tradewinds.SeaHazards.Chances;
import com.uxplima.uxmskyblock.core.application.tradewinds.SeaHazards.Struck;
import com.uxplima.uxmskyblock.core.application.tradewinds.VoyageEventsPort.Hazard;
import com.uxplima.uxmskyblock.core.application.tradewinds.VoyageEventsPort.Planned;
import com.uxplima.uxmskyblock.core.domain.gamemode.AuthorityRoot;
import com.uxplima.uxmskyblock.core.domain.gamemode.RootAuthorityRecord;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.island.IslandAuthorityOutcome;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A voyage rolls what may happen at sea as it sets out, and each event that comes happens once at its moment: a
 * storm makes the voyage longer, pirates take a toll or board, and a find fills the hold.
 */
class AVoyageMeetsTheSeaTest {

    private static final Instant NOW = Instant.parse("2026-10-13T12:00:00Z");
    private static final ServerNodeId NODE = ServerNodeId.of("node-a");
    private static final Chances ALWAYS = new Chances(1, Duration.ofSeconds(30), 1, 500, 1);

    private final IslandId vessel = IslandId.of(UUID.randomUUID());
    private final PlayerUuid captain = PlayerUuid.of(UUID.randomUUID());
    private final Events events = new Events();
    private final Map<IslandId, VoyagesPort.Voyage> voyages = new HashMap<>();
    private final Hold hold = new Hold();
    private final List<String> asked = new ArrayList<>();
    private PortMarket.Bank.Answer bankSays = PortMarket.Bank.Answer.LANDED;
    private boolean leaseHeld = true;
    private Instant now = NOW;
    private java.util.random.RandomGenerator random = new SplittableRandom(7);

    @Test
    @DisplayName("Each event is rolled once by its chance as the vessel sets out, at a moment of the voyage")
    void planned() {
        List<Planned> all = hazards(ALWAYS).plan(vessel, "cove", NOW, NOW.plusSeconds(120));

        assertThat(all).extracting(Planned::hazard).containsExactly(Hazard.STORM, Hazard.PIRATES, Hazard.FIND);
        assertThat(all).allSatisfy(one -> {
            assertThat(one.portId()).isEqualTo("cove");
            assertThat(one.at()).isAfterOrEqualTo(NOW).isBefore(NOW.plusSeconds(120));
        });
        assertThat(events.rows.values()).containsExactlyInAnyOrderElementsOf(all);
        assertThat(hazards(Chances.NONE).plan(vessel, "cove", NOW, NOW.plusSeconds(120)))
                .isEmpty();
        assertThat(events.rows)
                .describedAs("a new voyage puts its own in place")
                .isEmpty();
        assertThat(hazards(ALWAYS).plan(vessel, "cove", NOW, NOW))
                .describedAs("no voyage, no sea")
                .isEmpty();
        random = new java.util.random.RandomGenerator() {
            @Override
            public long nextLong() {
                return 0;
            }

            @Override
            public double nextDouble() {
                return 0;
            }
        };
        assertThat(hazards(Chances.NONE).plan(vessel, "cove", NOW, NOW.plusSeconds(120)))
                .describedAs("a chance of 0 never comes, however the roll falls")
                .isEmpty();
        random = new SplittableRandom(7);
        List<Planned> some =
                hazards(new Chances(0.5, Duration.ZERO, 0.5, 0, 0.5)).plan(vessel, "cove", NOW, NOW.plusSeconds(120));
        assertThat(some).hasSizeBetween(0, 3);
    }

    @Test
    @DisplayName("A storm makes the voyage longer, once")
    void storm() {
        sailFor("cove", NOW.plusSeconds(100));
        plant(Hazard.STORM, NOW.plusSeconds(10));
        now = NOW.plusSeconds(10);

        List<Struck> struck = hazards(ALWAYS).strike();

        assertThat(struck).singleElement().isInstanceOfSatisfying(Struck.Storm.class, storm -> {
            assertThat(storm.lost()).isEqualTo(Duration.ofSeconds(30));
            assertThat(storm.arrives()).isEqualTo(NOW.plusSeconds(130));
        });
        assertThat(java.util.Objects.requireNonNull(voyages.get(vessel)).arrivesAt())
                .isEqualTo(NOW.plusSeconds(130));
        assertThat(hazards(ALWAYS).strike()).describedAs("once").isEmpty();
    }

    @Test
    @DisplayName("A storm on the way still comes when the watch looks only after the vessel arrived")
    void lateWatch() {
        sailFor("cove", NOW.plusSeconds(8));
        plant(Hazard.STORM, NOW.plusSeconds(5));
        now = NOW.plusSeconds(10);

        assertThat(hazards(ALWAYS).strike()).singleElement().isInstanceOf(Struck.Storm.class);
        assertThat(java.util.Objects.requireNonNull(voyages.get(vessel)).arrivesAt())
                .isEqualTo(NOW.plusSeconds(38));

        sailFor("cove", NOW.plusSeconds(4));
        plant(Hazard.STORM, NOW.plusSeconds(5));
        assertThat(hazards(ALWAYS).strike())
                .describedAs("a moment after the voyage ended is no moment of it")
                .isEmpty();
        assertThat(events.rows).isEmpty();
    }

    @Test
    @DisplayName("Pirates take their toll from the island bank, and board when it cannot pay or they ask none")
    void pirates() {
        sailFor("cove", NOW.plusSeconds(100));
        plant(Hazard.PIRATES, NOW.plusSeconds(5));
        now = NOW.plusSeconds(10);

        assertThat(hazards(ALWAYS).strike()).singleElement().isEqualTo(new Struck.Toll(planted(), 500));
        assertThat(asked)
                .containsExactly(captain + " -500 pirates:" + vessel.value() + ":"
                        + planted().at().toEpochMilli());

        plant(Hazard.PIRATES, NOW.plusSeconds(6));
        bankSays = PortMarket.Bank.Answer.NO_FUNDS;
        assertThat(hazards(ALWAYS).strike()).singleElement().isInstanceOf(Struck.Boarded.class);

        plant(Hazard.PIRATES, NOW.plusSeconds(7));
        asked.clear();
        assertThat(hazards(new Chances(0, Duration.ZERO, 1, 0, 0)).strike())
                .singleElement()
                .isInstanceOf(Struck.Boarded.class);
        assertThat(asked).describedAs("no toll, no bank").isEmpty();
    }

    @Test
    @DisplayName("An event comes to nothing once the voyage ended another way, and waits while another node holds it")
    void comesToNothing() {
        sailFor("cove", NOW.plusSeconds(100));
        plant(Hazard.STORM, NOW.plusSeconds(5));
        now = NOW.plusSeconds(10);
        leaseHeld = false;

        assertThat(hazards(ALWAYS).strike()).isEmpty();
        assertThat(events.rows).describedAs("another node's to strike").hasSize(1);

        leaseHeld = true;
        sailFor("bay", NOW.plusSeconds(100));
        assertThat(hazards(ALWAYS).strike()).isEmpty();
        assertThat(events.rows).describedAs("bound elsewhere now: gone").isEmpty();
        assertThat(java.util.Objects.requireNonNull(voyages.get(vessel)).arrivesAt())
                .isEqualTo(NOW.plusSeconds(100));
    }

    @Test
    @DisplayName("A find waits to be stowed, goes into the hold once, and is lost when the hold has no room")
    void find() {
        sailFor("cove", NOW.plusSeconds(100));
        plant(Hazard.FIND, NOW.plusSeconds(5));
        now = NOW.plusSeconds(10);
        SeaHazards sea = hazards(ALWAYS);

        Struck.Find find = (Struck.Find) sea.strike().getFirst();
        assertThat(events.rows).describedAs("not taken until stowed").hasSize(1);
        hold.items.put("GOLD", 1);
        events.moveHoldOnce = () -> hold.items.put("GOLD", 2);

        assertThat(sea.stow(
                        find,
                        (stored, slots) -> Optional.of(
                                Hold.write(Map.of("GOLD", Hold.read(stored).getOrDefault("GOLD", 0) + 5)))))
                .isEqualTo(SeaHazards.Stowed.STOWED);
        assertThat(hold.items).containsEntry("GOLD", 7);
        assertThat(events.rows).isEmpty();
        assertThat(sea.stow(find, (stored, slots) -> Optional.of(stored)))
                .describedAs("once")
                .isEqualTo(SeaHazards.Stowed.NOT_NOW);

        plant(Hazard.FIND, NOW.plusSeconds(6));
        Struck.Find lost = (Struck.Find) sea.strike().getFirst();
        assertThat(sea.stow(lost, (stored, slots) -> Optional.empty())).isEqualTo(SeaHazards.Stowed.NO_ROOM);
        assertThat(events.rows).isEmpty();
        assertThat(hold.items).containsEntry("GOLD", 7);
    }

    @Test
    @DisplayName("A chance is from 0 to 1, a storm costs at most a day, and a toll is not below 0")
    void chances() {
        assertThatThrownBy(() -> new Chances(1.1, Duration.ZERO, 0, 0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Chances(0, Duration.ZERO, -0.1, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Chances(0, Duration.ZERO, 0, 0, 2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Chances(0, Duration.ofDays(2), 0, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Chances(0, Duration.ofSeconds(-1), 0, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Chances(0, Duration.ZERO, 0, -1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new Chances(1, Duration.ofDays(1), 1, 0, 1).storm()).isEqualTo(1);
    }

    private void sailFor(String port, Instant arrives) {
        voyages.put(vessel, new VoyagesPort.Voyage(port, arrives));
    }

    private void plant(Hazard hazard, Instant at) {
        events.rows.put(hazard, new Planned(vessel, hazard, "cove", at));
    }

    private Planned planted() {
        return new Planned(vessel, Hazard.PIRATES, "cove", NOW.plusSeconds(5));
    }

    private SeaHazards hazards(Chances chances) {
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        VoyagesPort ports = new VoyagesPort() {
            @Override
            public Optional<Voyage> voyage(IslandId island) {
                return Optional.ofNullable(voyages.get(island));
            }

            @Override
            public boolean setSail(IslandId island, long leaseEpoch, ServerNodeId node, Voyage voyage) {
                voyages.put(island, voyage);
                return true;
            }
        };
        PortMarket.Bank bank = new PortMarket.Bank() {
            @Override
            public Answer move(IslandId island, PlayerUuid actor, long delta, String reason, String key) {
                asked.add(actor + " " + delta + " " + key);
                return bankSays;
            }

            @Override
            public boolean here(IslandId island) {
                return true;
            }
        };
        VesselLease lease = new VesselLease(new Leases(), NODE, clock);
        PortMarket market = new PortMarket(
                hold,
                ports,
                MarketOrdersPort.class.cast(java.lang.reflect.Proxy.newProxyInstance(
                        getClass().getClassLoader(),
                        new Class<?>[] {MarketOrdersPort.class},
                        (proxy, method, args) -> method.getName().equals("tradeVolume") ? 0L : null)),
                lease,
                bank,
                hold,
                Standing.NONE,
                new Ranks(List.of(new Ranks.Rank("dinghy", 0, 3))),
                NODE,
                clock);
        return new SeaHazards(
                events, market, ports, hold, lease, bank, NODE, clock, random, island -> Optional.of(captain), chances);
    }

    /** The events of the one vessel, by kind, as storage keeps them. */
    private final class Events implements VoyageEventsPort {
        private final Map<Hazard, Planned> rows = new LinkedHashMap<>();
        private Runnable moveHoldOnce = () -> {};

        @Override
        public void plan(IslandId island, List<Planned> planned) {
            rows.clear();
            planned.forEach(one -> rows.put(one.hazard(), one));
        }

        @Override
        public List<Planned> due(Instant at) {
            return rows.values().stream().filter(one -> !one.at().isAfter(at)).toList();
        }

        @Override
        public boolean claim(Planned event) {
            return rows.remove(event.hazard(), event);
        }

        @Override
        public boolean stow(Planned find, MarketOrdersPort.HoldWrite write) {
            Runnable move = moveHoldOnce;
            moveHoldOnce = () -> {};
            move.run();
            if (!find.equals(rows.get(find.hazard())) || write.cargoVersion() != hold.version) {
                return false;
            }
            rows.remove(find.hazard());
            hold.items.clear();
            hold.items.putAll(Hold.read(write.cargoAfter()));
            return true;
        }
    }

    /** A hold of named counts, written as text, whose version moves with every change. */
    private final class Hold implements VesselsPort, PortMarket.Goods {
        private final Map<String, Integer> items = new LinkedHashMap<>() {
            @Override
            public Integer put(String key, Integer value) {
                version++;
                return super.put(key, value);
            }
        };
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
            return leaseHeld ? Optional.of(new Cargo(write(items), version)) : Optional.empty();
        }

        @Override
        public int count(byte[] stored, String item) {
            return read(stored).getOrDefault(item, 0);
        }

        @Override
        public Optional<byte[]> take(byte[] stored, String item, int count) {
            return Optional.empty();
        }

        @Override
        public Optional<byte[]> stow(byte[] stored, int slots, String item, int count) {
            return Optional.empty();
        }

        static byte[] write(Map<String, Integer> items) {
            StringBuilder text = new StringBuilder();
            items.forEach(
                    (item, count) -> text.append(item).append('=').append(count).append(';'));
            return text.toString().getBytes(StandardCharsets.UTF_8);
        }

        static Map<String, Integer> read(byte[] stored) {
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

    private final class Leases implements RootAuthorityPort {
        @Override
        public IslandAuthorityOutcome acquire(AuthorityRoot root, ServerNodeId node, int leaseSeconds) {
            return leaseHeld ? IslandAuthorityOutcome.success(1) : IslandAuthorityOutcome.rejected();
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
