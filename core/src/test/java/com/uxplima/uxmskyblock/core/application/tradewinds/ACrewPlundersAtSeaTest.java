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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.application.gamemode.RootAuthorityPort;
import com.uxplima.uxmskyblock.core.application.tradewinds.PlunderPort.Plunder;
import com.uxplima.uxmskyblock.core.domain.gamemode.AuthorityRoot;
import com.uxplima.uxmskyblock.core.domain.gamemode.RootAuthorityRecord;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.island.IslandAuthorityOutcome;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A crew boards another vessel at sea and takes part of its hold into their own, once in a while: never in port,
 * never from their own vessel, and the goods move between the holds together or not at all.
 */
class ACrewPlundersAtSeaTest {

    private static final Instant NOW = Instant.parse("2026-10-13T12:00:00Z");
    private static final ServerNodeId NODE = ServerNodeId.of("node-a");
    private static final Duration COOLDOWN = Duration.ofMinutes(30);

    private final IslandId raider = IslandId.of(UUID.randomUUID());
    private final IslandId target = IslandId.of(UUID.randomUUID());
    private final IslandId third = IslandId.of(UUID.randomUUID());
    private final Map<IslandId, Integer> gold = new HashMap<>();
    private final Map<IslandId, Long> versions = new HashMap<>();
    private final Map<IslandId, VoyagesPort.Voyage> voyages = new HashMap<>();
    private final Set<IslandId> elsewhere = new HashSet<>();
    private final Plunders plunders = new Plunders();
    private Instant now = NOW;

    /** Half of the target's gold, rounded down, into the raider's hold of room for 20. */
    private static final Piracy.Split HALF = (target, raider, slots) -> {
        int there = count(target);
        int taken = there / 2;
        if (taken == 0 || count(raider) + taken > 20) {
            return Optional.empty();
        }
        return Optional.of(new Piracy.Haul(bytes(there - taken), bytes(count(raider) + taken), taken));
    };

    @Test
    @DisplayName("A crew at sea takes part of another vessel's hold into its own, and the plunder is written down")
    void plundered() {
        atSea(raider, target);
        gold.put(target, 10);
        gold.put(raider, 2);

        Plunder plunder = piracy().plunder(raider, target, HALF).orElseThrow();

        assertThat(plunder).isEqualTo(new Plunder(raider, target, NOW, 5));
        assertThat(gold).containsEntry(target, 5).containsEntry(raider, 7);
        assertThat(plunders.written).containsExactly(plunder);
    }

    @Test
    @DisplayName("Never in port, never one's own vessel, and never when nothing fits or nothing is there")
    void refused() {
        gold.put(target, 10);
        atSea(target);
        voyages.put(raider, new VoyagesPort.Voyage("bay", NOW.minusSeconds(1)));
        assertThat(piracy().plunder(raider, target, HALF).errorOrThrow()).isEqualTo("tradewinds.piracy.raider_in_port");
        atSea(raider);
        voyages.put(target, new VoyagesPort.Voyage("bay", NOW.minusSeconds(1)));
        assertThat(piracy().plunder(raider, target, HALF).errorOrThrow()).isEqualTo("tradewinds.piracy.target_in_port");
        voyages.remove(target);
        assertThat(piracy().plunder(raider, target, HALF).errorOrThrow())
                .describedAs("a vessel that never sailed is in no sea")
                .isEqualTo("tradewinds.piracy.target_in_port");
        assertThat(piracy().plunder(raider, raider, HALF).errorOrThrow()).isEqualTo("tradewinds.piracy.own");
        atSea(target);
        gold.put(raider, 18);
        assertThat(piracy().plunder(raider, target, HALF).errorOrThrow()).isEqualTo("tradewinds.piracy.no_haul");
        gold.put(raider, 0);
        gold.put(target, 1);
        assertThat(piracy().plunder(raider, target, HALF).errorOrThrow()).isEqualTo("tradewinds.piracy.no_haul");
        assertThat(piracy().plunder(raider, target, (t, r, slots) -> Optional.of(new Piracy.Haul(t, r, 0)))
                        .errorOrThrow())
                .describedAs("a haul of nothing")
                .isEqualTo("tradewinds.piracy.no_haul");
        gold.put(target, 10);
        elsewhere.add(target);
        assertThat(piracy().plunder(raider, target, HALF).errorOrThrow()).isEqualTo("tradewinds.hold.elsewhere");
        assertThat(plunders.written).isEmpty();
        assertThat(gold).containsEntry(target, 10);
    }

    @Test
    @DisplayName("A vessel that plundered, or was plundered, waits out the cooldown before either again")
    void cooldown() {
        atSea(raider, target, third);
        gold.put(target, 16);
        gold.put(third, 16);
        piracy().plunder(raider, target, HALF).orElseThrow();

        now = NOW.plus(Duration.ofMinutes(29));
        int[] looked = {0};
        assertThat(piracy().plunder(raider, third, (t, r, slots) -> {
                            looked[0]++;
                            return HALF.split(t, r, slots);
                        })
                        .errorOrThrow())
                .isEqualTo("tradewinds.piracy.too_soon");
        assertThat(looked[0])
                .describedAs("no hold is read for a plunder too soon")
                .isZero();
        assertThat(piracy().plunder(third, target, HALF).errorOrThrow()).isEqualTo("tradewinds.piracy.too_soon");
        plunders.recentSaysNo = true;
        assertThat(piracy().plunder(third, target, HALF).errorOrThrow())
                .describedAs("the transaction checks again")
                .isEqualTo("tradewinds.piracy.too_soon");
        plunders.recentSaysNo = false;

        now = NOW.plus(COOLDOWN).plusSeconds(1);
        assertThat(piracy().plunder(raider, third, HALF).isOk()).isTrue();
        assertThat(plunders.written).hasSize(2);
    }

    @Test
    @DisplayName("A plunder whose hold moved under it looks again, and gives up after three tries")
    void moved() {
        atSea(raider, target);
        gold.put(target, 10);
        plunders.moveFirst = 1;

        assertThat(piracy().plunder(raider, target, HALF).isOk()).isTrue();
        assertThat(gold).containsEntry(raider, 5);

        now = NOW.plus(COOLDOWN).plusSeconds(1);
        gold.put(target, 10);
        plunders.moveFirst = 3;
        assertThat(piracy().plunder(raider, target, HALF).errorOrThrow()).isEqualTo("tradewinds.market.busy");
    }

    @Test
    @DisplayName("A plunder takes something from another vessel, and a cooldown is not below nothing")
    void bounds() {
        assertThatThrownBy(() -> new Plunder(raider, raider, NOW, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Plunder(raider, target, NOW, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                        new Piracy(plunders, market(), new Holds(), lease(), NODE, clock(), Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new Piracy.Haul(bytes(1), bytes(2), 3)).isEqualTo(new Piracy.Haul(bytes(1), bytes(2), 3));
    }

    private void atSea(IslandId... vessels) {
        for (IslandId vessel : vessels) {
            voyages.put(vessel, new VoyagesPort.Voyage("bay", NOW.plus(Duration.ofHours(2))));
        }
    }

    private Clock clock() {
        return Clock.fixed(now, ZoneOffset.UTC);
    }

    private VesselLease lease() {
        return new VesselLease(new Leases(), NODE, clock());
    }

    private Piracy piracy() {
        return new Piracy(plunders, market(), new Holds(), lease(), NODE, clock(), COOLDOWN);
    }

    private PortMarket market() {
        return new PortMarket(
                new Holds(),
                new VoyagesPort() {
                    @Override
                    public Optional<Voyage> voyage(IslandId vessel) {
                        return Optional.ofNullable(voyages.get(vessel));
                    }

                    @Override
                    public boolean setSail(IslandId vessel, long epoch, ServerNodeId node, Voyage voyage) {
                        return false;
                    }
                },
                MarketOrdersPort.class.cast(java.lang.reflect.Proxy.newProxyInstance(
                        getClass().getClassLoader(),
                        new Class<?>[] {MarketOrdersPort.class},
                        (proxy, method, args) -> method.getName().equals("tradeVolume") ? 0L : null)),
                lease(),
                new PortMarket.Bank() {
                    @Override
                    public Answer move(IslandId vessel, PlayerUuid actor, long delta, String reason, String key) {
                        return Answer.REFUSED;
                    }

                    @Override
                    public boolean here(IslandId vessel) {
                        return true;
                    }
                },
                new Holds(),
                Standing.NONE,
                new Ranks(List.of(new Ranks.Rank("dinghy", 0, 3))),
                NODE,
                clock());
    }

    private static int count(byte[] hold) {
        return hold.length == 0 ? 0 : Integer.parseInt(new String(hold, StandardCharsets.UTF_8));
    }

    private static byte[] bytes(int gold) {
        return Integer.toString(gold).getBytes(StandardCharsets.UTF_8);
    }

    /** The plunders written down, refusing one too soon after another as the table does. */
    private final class Plunders implements PlunderPort {
        private final List<Plunder> written = new ArrayList<>();
        private int moveFirst;
        private boolean recentSaysNo;

        @Override
        public boolean recent(IslandId by, IslandId on, Instant since) {
            return !recentSaysNo && soon(by, on, since);
        }

        private boolean soon(IslandId by, IslandId on, Instant since) {
            return written.stream()
                    .anyMatch(one -> one.at().isAfter(since)
                            && (one.raider().equals(by) || one.target().equals(on)));
        }

        @Override
        public Outcome take(
                Plunder plunder,
                MarketOrdersPort.HoldWrite raiderHold,
                MarketOrdersPort.HoldWrite targetHold,
                Instant since) {
            if (soon(plunder.raider(), plunder.target(), since)) {
                return Outcome.TOO_SOON;
            }
            if (moveFirst > 0) {
                moveFirst--;
                versions.merge(plunder.target(), 1L, Long::sum);
                return Outcome.MOVED;
            }
            if (raiderHold.cargoVersion() != versions.getOrDefault(plunder.raider(), 1L)
                    || targetHold.cargoVersion() != versions.getOrDefault(plunder.target(), 1L)) {
                return Outcome.MOVED;
            }
            gold.put(plunder.raider(), count(raiderHold.cargoAfter()));
            gold.put(plunder.target(), count(targetHold.cargoAfter()));
            versions.merge(plunder.raider(), 1L, Long::sum);
            versions.merge(plunder.target(), 1L, Long::sum);
            written.add(plunder);
            return Outcome.TAKEN;
        }
    }

    /** Each vessel's gold, written as a number, at the version it stands at. */
    private final class Holds implements VesselsPort, PortMarket.Goods {
        @Override
        public Set<IslandId> findAll() {
            return Set.of(raider, target, third);
        }

        @Override
        public boolean exists(IslandId islandId) {
            return true;
        }

        @Override
        public void add(IslandId islandId) {}

        @Override
        public Optional<Cargo> cargo(IslandId islandId) {
            return Optional.of(new Cargo(bytes(gold.getOrDefault(islandId, 0)), versions.getOrDefault(islandId, 1L)));
        }

        @Override
        public int count(byte[] stored, String item) {
            return 0;
        }

        @Override
        public Optional<byte[]> take(byte[] stored, String item, int count) {
            return Optional.empty();
        }

        @Override
        public Optional<byte[]> stow(byte[] stored, int slots, String item, int count) {
            return Optional.empty();
        }
    }

    private final class Leases implements RootAuthorityPort {
        @Override
        public IslandAuthorityOutcome acquire(AuthorityRoot root, ServerNodeId node, int leaseSeconds) {
            boolean taken = elsewhere.stream()
                    .anyMatch(vessel -> VesselLease.rootOf(vessel).equals(root));
            return taken ? IslandAuthorityOutcome.rejected() : IslandAuthorityOutcome.success(1);
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
