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
import java.util.UUID;

import com.uxplima.uxmskyblock.core.application.gamemode.RootAuthorityPort;
import com.uxplima.uxmskyblock.core.application.tradewinds.ContractsPort.Held;
import com.uxplima.uxmskyblock.core.domain.gamemode.AuthorityRoot;
import com.uxplima.uxmskyblock.core.domain.gamemode.RootAuthorityRecord;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.island.IslandAuthorityOutcome;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A port offers deliveries. The crew takes one where it is offered, carries the goods and delivers them where it
 * names, in time; one not delivered in time fails, once.
 */
class ACrewDeliversWhatItTookTest {

    private static final Instant NOW = Instant.parse("2026-10-13T12:00:00Z");
    private static final ServerNodeId NODE = ServerNodeId.of("node-a");
    private static final Contract GRAIN = new Contract("grain", "bay", "WHEAT", 32, "cove", Duration.ofMinutes(30));
    private static final Contract SALT = new Contract("salt", "bay", "SUGAR", 8, "cove", Duration.ofMinutes(10));
    private static final Contract IRON = new Contract("iron", "cove", "IRON_INGOT", 4, "bay", Duration.ofMinutes(10));

    private final IslandId vessel = IslandId.of(UUID.randomUUID());
    private final PlayerUuid ada = PlayerUuid.of(UUID.randomUUID());
    private final Hold hold = new Hold();
    private final Taken taken = new Taken();
    private final Map<IslandId, VoyagesPort.Voyage> voyages = new HashMap<>();
    private Instant now = NOW;

    @Test
    @DisplayName("A contract is taken where it is offered, once at a time, and no more than a vessel holds")
    void taken() {
        assertThat(board(2).take(vessel, ada, "grain").errorOrThrow())
                .isEqualTo("tradewinds.contracts.not_offered_here");
        lieIn("cove");
        assertThat(board(2).take(vessel, ada, "grain").errorOrThrow())
                .isEqualTo("tradewinds.contracts.not_offered_here");
        lieIn("bay");

        Held grain = board(2).take(vessel, ada, "grain").orElseThrow();

        assertThat(grain.to()).isEqualTo("cove");
        assertThat(grain.count()).isEqualTo(32);
        assertThat(grain.taker()).isEqualTo(ada);
        assertThat(grain.due()).isEqualTo(NOW.plus(Duration.ofMinutes(30)));
        assertThat(board(2).held(vessel)).containsExactly(grain);
        assertThat(board(2).take(vessel, ada, "grain").errorOrThrow()).isEqualTo("tradewinds.contracts.already_taken");
        assertThat(board(1).take(vessel, ada, "salt").errorOrThrow()).isEqualTo("tradewinds.contracts.too_many");
        assertThat(board(1).take(vessel, ada, "grain").errorOrThrow())
                .describedAs("one held already is said so, not that the vessel holds too many")
                .isEqualTo("tradewinds.contracts.already_taken");
        assertThat(board(2).take(vessel, ada, "nothing").errorOrThrow()).isEqualTo("tradewinds.contracts.unknown");
        Held salt = board(2).take(vessel, ada, "salt").orElseThrow();
        assertThat(board(2).held(vessel)).describedAs("the soonest due first").containsExactly(salt, grain);
        assertThat(board(2).offeredAt("bay")).containsExactly(GRAIN, SALT);
        assertThat(board(2).offeredAt("cove")).containsExactly(IRON);
    }

    @Test
    @DisplayName("A contract is delivered where it names, out of the hold, and can be taken again after")
    void delivered() {
        lieIn("bay");
        board(3).take(vessel, ada, "grain").orElseThrow();
        hold.items.put("WHEAT", 40);
        assertThat(board(3).deliver(vessel, "grain").errorOrThrow()).isEqualTo("tradewinds.contracts.not_there");
        lieIn("cove");
        hold.items.put("WHEAT", 31);
        assertThat(board(3).deliver(vessel, "grain").errorOrThrow()).isEqualTo("tradewinds.contracts.too_few");
        hold.items.put("WHEAT", 40);

        Held done = board(3).deliver(vessel, "grain").orElseThrow();

        assertThat(done.state()).isEqualTo(ContractsPort.State.DELIVERED);
        assertThat(hold.items).containsEntry("WHEAT", 8);
        assertThat(board(3).held(vessel)).isEmpty();
        assertThat(board(3).deliver(vessel, "grain").errorOrThrow()).isEqualTo("tradewinds.contracts.not_taken");
        assertThat(hold.items).containsEntry("WHEAT", 8);
        lieIn("bay");
        assertThat(board(3).take(vessel, ada, "grain").isOk())
                .describedAs("a contract delivered can be taken again")
                .isTrue();
    }

    @Test
    @DisplayName("A delivery whose hold moved under it looks again and delivers what the hold now holds")
    void holdMoved() {
        lieIn("bay");
        board(3).take(vessel, ada, "grain").orElseThrow();
        lieIn("cove");
        hold.items.put("WHEAT", 40);
        taken.moveHoldOnce = () -> hold.items.put("WHEAT", 50);

        board(3).deliver(vessel, "grain").orElseThrow();

        assertThat(hold.items).containsEntry("WHEAT", 18);
    }

    @Test
    @DisplayName("A contract not delivered in time is late, fails once, and can be taken again")
    void late() {
        lieIn("bay");
        board(3).take(vessel, ada, "salt").orElseThrow();
        board(3).take(vessel, ada, "grain").orElseThrow();
        lieIn("cove");
        hold.items.put("SUGAR", 8);
        now = NOW.plus(Duration.ofMinutes(10));

        assertThat(board(3).deliver(vessel, "salt").errorOrThrow()).isEqualTo("tradewinds.contracts.late");
        assertThat(hold.items).containsEntry("SUGAR", 8);
        List<Held> failed = board(3).failOverdue();
        assertThat(failed).extracting(Held::contractId).containsExactly("salt");
        assertThat(board(3).failOverdue()).describedAs("failed once").isEmpty();
        assertThat(board(3).held(vessel)).extracting(Held::contractId).containsExactly("grain");
        assertThat(java.util.Objects.requireNonNull(taken.rows.get("salt")).state())
                .isEqualTo(ContractsPort.State.FAILED);
        lieIn("bay");
        assertThat(board(3).take(vessel, ada, "salt").isOk()).isTrue();
    }

    @Test
    @DisplayName("A contract is delivered to another port, asks for some goods and gives some time")
    void written() {
        assertThatThrownBy(() -> new Contract("a", "bay", "WHEAT", 1, "bay", Duration.ofMinutes(5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Contract("a", "bay", "WHEAT", 0, "cove", Duration.ofMinutes(5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () -> new Contract("a", "bay", "WHEAT", Contract.MAX_COUNT + 1, "cove", Duration.ofMinutes(5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Contract("a", "bay", "WHEAT", 1, "cove", Duration.ofSeconds(59)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Contract("a", "bay", "WHEAT", 1, "cove", Duration.ofDays(8)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Contract("A b", "bay", "WHEAT", 1, "cove", Duration.ofMinutes(5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new Contract("a", "bay", "WHEAT", Contract.MAX_COUNT, "cove", Contract.MAX_WITHIN).count())
                .isEqualTo(Contract.MAX_COUNT);
        assertThatThrownBy(() -> board(0)).isInstanceOf(IllegalArgumentException.class);
    }

    private void lieIn(String port) {
        voyages.put(vessel, new VoyagesPort.Voyage(port, now.minusSeconds(1)));
    }

    private ContractBoard board(int atOnce) {
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        PortMarket market = new PortMarket(
                hold,
                new VoyagesPort() {
                    @Override
                    public Optional<Voyage> voyage(IslandId island) {
                        return Optional.ofNullable(voyages.get(island));
                    }

                    @Override
                    public boolean setSail(IslandId island, long leaseEpoch, ServerNodeId node, Voyage voyage) {
                        return false;
                    }
                },
                MarketOrdersPort.class.cast(java.lang.reflect.Proxy.newProxyInstance(
                        getClass().getClassLoader(), new Class<?>[] {MarketOrdersPort.class}, (proxy, method, args) -> {
                            throw new UnsupportedOperationException(method.getName());
                        })),
                new VesselLease(new Leases(), NODE, clock),
                new PortMarket.Bank() {
                    @Override
                    public Answer move(IslandId island, PlayerUuid actor, long delta, String reason, String key) {
                        return Answer.REFUSED;
                    }

                    @Override
                    public boolean here(IslandId island) {
                        return true;
                    }
                },
                hold,
                Standing.NONE,
                new Ranks(List.of(new Ranks.Rank("dinghy", 0, 3))),
                NODE,
                clock);
        return new ContractBoard(
                taken,
                market,
                hold,
                hold,
                new VesselLease(new Leases(), NODE, clock),
                NODE,
                clock,
                List.of(GRAIN, SALT, IRON),
                atOnce);
    }

    /** The contracts taken, by key, as storage keeps them for the one vessel. */
    private final class Taken implements ContractsPort {
        private final Map<String, Held> rows = new LinkedHashMap<>();
        private Runnable moveHoldOnce = () -> {};

        @Override
        public List<Held> of(IslandId island) {
            return new ArrayList<>(rows.values());
        }

        @Override
        public List<Held> overdue(Instant at) {
            return rows.values().stream()
                    .filter(one -> one.state() == State.TAKEN && !one.due().isAfter(at))
                    .toList();
        }

        @Override
        public boolean take(Held held) {
            Held there = rows.get(held.contractId());
            if (there != null && there.state() == State.TAKEN) {
                return false;
            }
            rows.put(held.contractId(), held);
            return true;
        }

        @Override
        public boolean deliver(IslandId island, String contractId, Instant at, MarketOrdersPort.HoldWrite write) {
            Runnable move = moveHoldOnce;
            moveHoldOnce = () -> {};
            move.run();
            Held there = rows.get(contractId);
            if (there == null || there.state() != State.TAKEN || !there.due().isAfter(at)) {
                return false;
            }
            if (write.cargoVersion() != hold.version) {
                return false;
            }
            hold.writeOver(write.cargoAfter());
            rows.put(contractId, withState(there, State.DELIVERED));
            return true;
        }

        @Override
        public boolean fail(IslandId island, String contractId) {
            Held there = rows.get(contractId);
            if (there == null || there.state() != State.TAKEN) {
                return false;
            }
            rows.put(contractId, withState(there, State.FAILED));
            return true;
        }

        private static Held withState(Held held, State state) {
            return new Held(
                    held.vessel(),
                    held.contractId(),
                    held.to(),
                    held.item(),
                    held.count(),
                    held.taker(),
                    held.due(),
                    state);
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
            now.put(item, have - count);
            return Optional.of(write(now));
        }

        @Override
        public Optional<byte[]> stow(byte[] stored, int slots, String item, int count) {
            return Optional.empty();
        }

        void writeOver(byte[] after) {
            items.clear();
            items.putAll(read(after));
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
