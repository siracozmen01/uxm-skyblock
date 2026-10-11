package com.uxplima.uxmskyblock.core.application.chunkblock;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.application.bank.IslandBankPort;
import com.uxplima.uxmskyblock.core.domain.bank.BankTransaction;
import com.uxplima.uxmskyblock.core.domain.bank.BankTransactionOutcome;
import com.uxplima.uxmskyblock.core.domain.bank.IslandBank;
import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkPayments;
import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkPos;
import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkRequirement;
import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkTerritory;
import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkTier;
import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkTiers;
import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkUnlockRules;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A chunk is paid for line by line, a part at a time, from the player, the island's bank and its chunk points; what
 * it no longer needs goes back; it opens once every line is met, bought for good, and gives its grade's points.
 */
class AChunkIsPaidForAndOpensTest {

    private static final IslandBounds BOUNDS = IslandBounds.fromCenterAndRadius(8, 8, 40);
    private static final ChunkPos NEXT = new ChunkPos(1, 0);
    private static final ChunkRequirement LOGS = new ChunkRequirement.Items("oak_log", 96);
    private static final ChunkRequirement MONEY = new ChunkRequirement.Money(5_000, ChunkRequirement.Source.ISLAND);
    private static final ChunkRequirement POINTS = new ChunkRequirement.Points(3);
    private static final ChunkRequirement GEMS =
            new ChunkRequirement.Currency("gems", 500, ChunkRequirement.Source.PLAYER);
    private static final ChunkRequirement BANK_GEMS =
            new ChunkRequirement.Currency("gems", 50, ChunkRequirement.Source.ISLAND);

    private final IslandId island = IslandId.of(UUID.randomUUID());
    private static final com.uxplima.uxmskyblock.core.domain.session.ServerNodeId NODE =
            new com.uxplima.uxmskyblock.core.domain.session.ServerNodeId("node-a");

    private final ChunkUnlocks.IslandPayer payer = new ChunkUnlocks.IslandPayer(UUID.randomUUID(), NODE);
    private final Claims claims = new Claims();
    private final Payments payments = new Payments();
    private final Bank bank = new Bank();
    private final ChunkBlockService service = new ChunkBlockService(claims, new ChunkUnlockRules(List.of(1L), 1));

    /** Who holds each island: this node, at epoch one, for every island. */
    private final com.uxplima.uxmskyblock.core.application.island.IslandAuthorityPort authorities = authorities();

    private ChunkUnlocks with(ChunkTier tier) {
        return with(new ChunkTiers(List.of(tier)));
    }

    private ChunkUnlocks with(ChunkTiers tiers) {
        return new ChunkUnlocks(
                service,
                payments,
                tiers,
                bank,
                new com.uxplima.uxmskyblock.core.application.bank.IslandBankService(
                        bank,
                        org.mockito.Mockito.mock(
                                com.uxplima.uxmskyblock.core.application.island.IslandStoragePort.class),
                        authorities),
                authorities);
    }

    private static com.uxplima.uxmskyblock.core.application.island.IslandAuthorityPort authorities() {
        var port = org.mockito.Mockito.mock(com.uxplima.uxmskyblock.core.application.island.IslandAuthorityPort.class);
        org.mockito.Mockito.when(port.findAuthority(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(call -> Optional.of(new com.uxplima.uxmskyblock.core.domain.island.IslandAuthorityRecord(
                        call.getArgument(0), NODE, 1, Instant.now().plusSeconds(60), Instant.now())));
        return port;
    }

    private static ChunkTier tier(List<ChunkRequirement> cost, List<ChunkRequirement> bypass, long points) {
        return new ChunkTier("common", "@common", 1, 99, 1, cost, bypass, List.of("[message] opened"), points);
    }

    @BeforeEach
    void start() {
        service.start(island, 8, 8);
    }

    @Test
    @DisplayName("Items handed in count a part at a time, what the chunk does not need goes back, and it opens")
    void itemsAreHandedIn() {
        ChunkUnlocks unlocks = with(tier(List.of(LOGS), List.of(), 2));

        ChunkUnlocks.Result first = unlocks.handIn(island, NEXT, BOUNDS, LOGS, 40, 0);
        assertThat(first.outcome()).isEqualTo(ChunkUnlocks.Outcome.PAID);
        assertThat(first.taken()).isEqualTo(40);
        assertThat(unlocks.offer(island, NEXT, BOUNDS).orElseThrow().remaining(LOGS))
                .isEqualTo(56);

        ChunkUnlocks.Result last = unlocks.handIn(island, NEXT, BOUNDS, LOGS, 64, 0);

        assertThat(last.outcome()).isEqualTo(ChunkUnlocks.Outcome.OPENED);
        assertThat(last.taken()).isEqualTo(56);
        assertThat(last.excess()).describedAs("eight logs more than it needed").isEqualTo(8);
        assertThat(last.opened()).map(ChunkTier::id).contains("common");
        assertThat(service.isOpen(island, NEXT)).contains(true);
        assertThat(service.inMemory(island).orElseThrow().isBought(NEXT)).isTrue();
        assertThat(payments.find(island, NEXT))
                .describedAs("forgotten once open, all but the mark that it gave its rewards")
                .isEqualTo(new ChunkPayments(java.util.Map.of(ChunkUnlocks.REWARDED, 1L)));
        assertThat(payments.points(island)).describedAs("the grade's points").isEqualTo(2);
    }

    @Test
    @DisplayName("A line the grade does not ask for, a closed place or a full line take nothing and give it all back")
    void whatIsNotNeededGoesBack() {
        ChunkUnlocks unlocks = with(tier(List.of(LOGS, POINTS), List.of(), 0));

        assertThat(unlocks.handIn(island, NEXT, BOUNDS, new ChunkRequirement.Items("stone", 5), 5, 0))
                .isEqualTo(new ChunkUnlocks.Result(ChunkUnlocks.Outcome.NOT_ON_COST, 0, 5, Optional.empty()));
        assertThat(unlocks.handIn(island, new ChunkPos(2, 2), BOUNDS, LOGS, 5, 0)
                        .outcome())
                .isEqualTo(ChunkUnlocks.Outcome.NOT_BESIDE_TERRITORY);
        assertThat(unlocks.handIn(island, new ChunkPos(2, 2), BOUNDS, LOGS, 5, 0)
                        .excess())
                .isEqualTo(5);
        assertThat(unlocks.handIn(island, NEXT, BOUNDS, LOGS, 0, 0).outcome())
                .isEqualTo(ChunkUnlocks.Outcome.NOTHING_TAKEN);
        unlocks.handIn(island, NEXT, BOUNDS, LOGS, 96, 0);
        assertThat(unlocks.handIn(island, NEXT, BOUNDS, LOGS, 3, 0))
                .isEqualTo(new ChunkUnlocks.Result(ChunkUnlocks.Outcome.ALREADY_PAID, 0, 3, Optional.empty()));
        assertThat(unlocks.handIn(island, NEXT, BOUNDS, LOGS, 0, 0).outcome())
                .isEqualTo(ChunkUnlocks.Outcome.ALREADY_PAID);
        assertThat(service.isOpen(island, NEXT))
                .describedAs("the points are not paid yet")
                .contains(false);
    }

    @Test
    @DisplayName("A payment that cannot be recorded counts nothing and all of it goes back")
    void aFailedRecordGivesItAllBack() {
        ChunkUnlocks unlocks = with(tier(List.of(LOGS), List.of(), 0));
        payments.failing = true;

        assertThat(unlocks.handIn(island, NEXT, BOUNDS, LOGS, 30, 0))
                .isEqualTo(new ChunkUnlocks.Result(ChunkUnlocks.Outcome.FAILED, 0, 30, Optional.empty()));
    }

    @Test
    @DisplayName("The island's bank pays as much as it holds, and the chunk opens when its money is paid")
    void theBankPays() {
        ChunkUnlocks unlocks = with(tier(List.of(MONEY), List.of(), 0));
        bank.balance = 3_000;

        ChunkUnlocks.Result part = unlocks.payFromIsland(island, NEXT, BOUNDS, MONEY, payer, 0);
        assertThat(part.outcome()).isEqualTo(ChunkUnlocks.Outcome.PAID);
        assertThat(part.taken()).isEqualTo(3_000);
        assertThat(bank.balance).isZero();
        assertThat(unlocks.payFromIsland(island, NEXT, BOUNDS, MONEY, payer, 0).outcome())
                .isEqualTo(ChunkUnlocks.Outcome.NOTHING_TAKEN);

        bank.balance = 10_000;
        ChunkUnlocks.Result rest = unlocks.payFromIsland(island, NEXT, BOUNDS, MONEY, payer, 0);
        assertThat(rest.outcome()).isEqualTo(ChunkUnlocks.Outcome.OPENED);
        assertThat(rest.taken()).isEqualTo(2_000);
        assertThat(bank.balance).isEqualTo(8_000);
        assertThat(bank.columns).containsOnly("PRIMARY");
        assertThat(bank.scales).containsOnly(2);
    }

    @Test
    @DisplayName("A store that charges and records together is asked first, and a currency in the bank is its column")
    void theStoreChargesTogether() {
        ChunkUnlocks unlocks = with(tier(List.of(BANK_GEMS), List.of(), 0));
        bank.held.put("gems", 80L);
        payments.together = true;

        ChunkUnlocks.Result paid = unlocks.payFromIsland(island, NEXT, BOUNDS, BANK_GEMS, payer, 0);

        assertThat(paid.outcome()).isEqualTo(ChunkUnlocks.Outcome.OPENED);
        assertThat(payments.charges).hasSize(1);
        ChunkIslandCharge charge = payments.charges.getFirst();
        assertThat(charge.currencyColumn()).isEqualTo("gems");
        assertThat(charge.scale()).isZero();
        assertThat(charge.amount()).isEqualTo(50);
        assertThat(charge.lineKey()).isEqualTo(BANK_GEMS.key());
        assertThat(bank.columns).describedAs("the store charged it").isEmpty();
    }

    @Test
    @DisplayName("A node that does not hold the island charges nothing out of its bank")
    void anotherNodeChargesNothing() {
        ChunkUnlocks unlocks = with(tier(List.of(MONEY), List.of(), 0));
        bank.balance = 9_000;
        payments.together = true;
        var stranger = new ChunkUnlocks.IslandPayer(
                UUID.randomUUID(), new com.uxplima.uxmskyblock.core.domain.session.ServerNodeId("node-b"));

        assertThat(unlocks.payFromIsland(island, NEXT, BOUNDS, MONEY, stranger, 0)
                        .outcome())
                .isEqualTo(ChunkUnlocks.Outcome.BANK_REFUSED);
        assertThat(payments.charges).isEmpty();
        payments.together = false;
        assertThat(unlocks.payFromIsland(island, NEXT, BOUNDS, MONEY, stranger, 0)
                        .outcome())
                .isEqualTo(ChunkUnlocks.Outcome.BANK_REFUSED);
        assertThat(bank.balance).isEqualTo(9_000);
    }

    @Test
    @DisplayName("Without a store that charges and records together, a currency in the bank is charged in its column")
    void aCurrencyIsChargedInTwoSteps() {
        ChunkUnlocks unlocks = with(tier(List.of(BANK_GEMS), List.of(), 0));
        bank.held.put("gems", 80L);

        assertThat(unlocks.payFromIsland(island, NEXT, BOUNDS, BANK_GEMS, payer, 0)
                        .outcome())
                .isEqualTo(ChunkUnlocks.Outcome.OPENED);
        assertThat(bank.columns).containsExactly("gems");
        assertThat(bank.scales).containsExactly(0);
        assertThat(bank.held).containsEntry("gems", 30L);
    }

    @Test
    @DisplayName("A bank charge that cannot be recorded goes back to the bank, and so does a bypass that falls short")
    void theBankIsMadeWhole() {
        ChunkUnlocks unlocks = with(tier(List.of(MONEY), List.of(MONEY, POINTS), 0));
        bank.balance = 6_000;
        payments.failing = true;

        assertThat(unlocks.payFromIsland(island, NEXT, BOUNDS, MONEY, payer, 0).outcome())
                .isEqualTo(ChunkUnlocks.Outcome.FAILED);
        assertThat(bank.balance).isEqualTo(6_000);

        payments.failing = false;
        assertThat(unlocks.bypass(island, NEXT, BOUNDS, List.of(), payer).outcome())
                .describedAs("the island holds no points")
                .isEqualTo(ChunkUnlocks.Outcome.NOTHING_TAKEN);
        assertThat(bank.balance)
                .describedAs("the money the bypass charged first came back")
                .isEqualTo(6_000);
    }

    @Test
    @DisplayName("Chunk points are spent as far as the island holds them")
    void pointsAreSpent() {
        ChunkUnlocks unlocks = with(tier(List.of(POINTS), List.of(), 0));
        payments.addPoints(island, 2);

        assertThat(unlocks.payFromIsland(island, NEXT, BOUNDS, POINTS, payer, 0).taken())
                .isEqualTo(2);
        assertThat(payments.points(island)).isZero();
        payments.addPoints(island, 5);

        assertThat(unlocks.payFromIsland(island, NEXT, BOUNDS, POINTS, payer, 0).outcome())
                .isEqualTo(ChunkUnlocks.Outcome.OPENED);
        assertThat(payments.points(island)).isEqualTo(4);
    }

    @Test
    @DisplayName("A grade priced by the level alone opens at that level and stays held, closing when it falls")
    void theLevelHolds() {
        ChunkUnlocks unlocks = with(tier(List.of(new ChunkRequirement.Level(5)), List.of(), 0));

        assertThat(unlocks.open(island, NEXT, BOUNDS, 4).outcome()).isEqualTo(ChunkUnlocks.Outcome.LEVEL_TOO_LOW);
        assertThat(unlocks.open(island, NEXT, BOUNDS, 5).outcome()).isEqualTo(ChunkUnlocks.Outcome.OPENED);
        assertThat(service.inMemory(island).orElseThrow().isBought(NEXT)).isFalse();
        assertThat(service.onLevel(island, 0)).containsExactly(NEXT);
    }

    @Test
    @DisplayName("A held chunk that closes and opens again gives its rewards and points the first time only")
    void aChunkRewardsOnce() {
        ChunkUnlocks unlocks = with(tier(List.of(new ChunkRequirement.Level(5)), List.of(), 4));

        ChunkUnlocks.Result first = unlocks.open(island, NEXT, BOUNDS, 5);
        assertThat(first.outcome()).isEqualTo(ChunkUnlocks.Outcome.OPENED);
        assertThat(first.rewards()).isTrue();
        assertThat(payments.points(island)).isEqualTo(4);

        assertThat(service.onLevel(island, 0)).containsExactly(NEXT);
        ChunkUnlocks.Result again = unlocks.open(island, NEXT, BOUNDS, 5);
        assertThat(again.outcome()).describedAs("it opens again").isEqualTo(ChunkUnlocks.Outcome.OPENED);
        assertThat(again.opened()).isPresent();
        assertThat(again.rewards()).describedAs("and gives nothing again").isFalse();
        assertThat(payments.points(island)).isEqualTo(4);
        assertThat(unlocks.offer(island, NEXT, BOUNDS).orElseThrow().met(5))
                .describedAs("the mark is no line of its price")
                .isTrue();
    }

    @Test
    @DisplayName("Opening a chunk with lines unpaid says so, and a server with no grades opens by the ladder")
    void openingAsksForEveryLine() {
        assertThat(with(tier(List.of(LOGS), List.of(), 0))
                        .open(island, NEXT, BOUNDS, 99)
                        .outcome())
                .isEqualTo(ChunkUnlocks.Outcome.PAID);
        ChunkUnlocks ladder = with(ChunkTiers.ladder());
        assertThat(ladder.open(island, NEXT, BOUNDS, 0).outcome()).isEqualTo(ChunkUnlocks.Outcome.LEVEL_TOO_LOW);
        assertThat(ladder.open(island, NEXT, BOUNDS, 1).outcome()).isEqualTo(ChunkUnlocks.Outcome.OPENED);
    }

    @Test
    @DisplayName("A bypass opens the chunk bought, charging the island's lines in full, and a short one charges none")
    void theBypass() {
        ChunkUnlocks unlocks = with(tier(List.of(LOGS), List.of(GEMS, POINTS, MONEY), 0));
        bank.balance = 5_000;
        payments.addPoints(island, 2);

        ChunkUnlocks.Result short1 = unlocks.bypass(island, NEXT, BOUNDS, List.of(GEMS), payer);
        assertThat(short1.outcome()).isEqualTo(ChunkUnlocks.Outcome.NOTHING_TAKEN);
        assertThat(payments.points(island)).isEqualTo(2);
        assertThat(bank.balance).describedAs("nothing charged").isEqualTo(5_000);

        payments.addPoints(island, 1);
        ChunkUnlocks.Result opened = unlocks.bypass(island, NEXT, BOUNDS, List.of(GEMS), payer);

        assertThat(opened.outcome()).isEqualTo(ChunkUnlocks.Outcome.OPENED);
        assertThat(payments.points(island)).isZero();
        assertThat(bank.balance).isZero();
        assertThat(service.inMemory(island).orElseThrow().isBought(NEXT)).isTrue();
        assertThat(with(tier(List.of(LOGS), List.of(), 0))
                        .bypass(island, new ChunkPos(2, 0), BOUNDS, List.of(), payer)
                        .outcome())
                .isEqualTo(ChunkUnlocks.Outcome.NO_BYPASS);
    }

    @Test
    @DisplayName("A chunk the level would hold, opened by its bypass, is bought and stays when the level falls")
    void aBypassBuysAHeldChunk() {
        ChunkUnlocks unlocks = with(tier(List.of(new ChunkRequirement.Level(50)), List.of(POINTS), 0));
        payments.addPoints(island, 3);

        assertThat(unlocks.bypass(island, NEXT, BOUNDS, List.of(), payer).outcome())
                .isEqualTo(ChunkUnlocks.Outcome.OPENED);
        assertThat(service.inMemory(island).orElseThrow().isBought(NEXT)).isTrue();
        assertThat(service.onLevel(island, 0)).isEmpty();
    }

    @Test
    @DisplayName("A bank charge the money for which the island lacks gives back what the bypass charged before it")
    void aShortBankGivesBackThePoints() {
        ChunkUnlocks unlocks = with(tier(List.of(LOGS), List.of(POINTS, MONEY), 0));
        payments.addPoints(island, 3);
        bank.balance = 4_999;

        assertThat(unlocks.bypass(island, NEXT, BOUNDS, List.of(), payer).outcome())
                .isEqualTo(ChunkUnlocks.Outcome.NOTHING_TAKEN);
        assertThat(payments.points(island)).describedAs("given back").isEqualTo(3);
        assertThat(service.isOpen(island, NEXT)).contains(false);
    }

    /** The claims, in memory. */
    private static final class Claims implements ChunkTerritoryPort {
        private final Map<IslandId, ChunkPos> origins = new HashMap<>();
        private final Map<IslandId, List<ChunkPos>> opened = new HashMap<>();
        private final Map<IslandId, Set<ChunkPos>> bought = new HashMap<>();

        @Override
        public Map<IslandId, ChunkTerritory> findAll() {
            return Map.of();
        }

        @Override
        public Optional<ChunkTerritory> find(IslandId islandId) {
            ChunkPos origin = origins.get(islandId);
            return origin == null
                    ? Optional.empty()
                    : Optional.of(new ChunkTerritory(
                            origin, opened.getOrDefault(islandId, List.of()), bought.getOrDefault(islandId, Set.of())));
        }

        @Override
        public void start(IslandId islandId, ChunkPos origin) {
            origins.putIfAbsent(islandId, origin);
        }

        @Override
        public boolean open(IslandId islandId, ChunkPos chunk, int order) {
            return open(islandId, chunk, order, false);
        }

        @Override
        public boolean open(IslandId islandId, ChunkPos chunk, int order, boolean paid) {
            opened.computeIfAbsent(islandId, id -> new ArrayList<>()).add(chunk);
            if (paid) {
                bought.computeIfAbsent(islandId, id -> new HashSet<>()).add(chunk);
            }
            return true;
        }

        @Override
        public void close(IslandId islandId, List<ChunkPos> chunks) {
            opened.getOrDefault(islandId, new ArrayList<>()).removeAll(chunks);
        }
    }

    /** The payments and points, in memory, failing on request, charging the bank itself on request. */
    private static final class Payments implements ChunkPaymentPort {
        private final Map<String, Long> paid = new HashMap<>();
        private final Map<IslandId, Long> points = new HashMap<>();
        private final List<ChunkIslandCharge> charges = new ArrayList<>();
        boolean failing;
        boolean together;

        private String at(IslandId islandId, ChunkPos chunk) {
            return islandId + "/" + chunk.x() + "," + chunk.z() + "/";
        }

        @Override
        public ChunkPayments find(IslandId islandId, ChunkPos chunk) {
            Map<String, Long> found = new HashMap<>();
            paid.forEach((key, amount) -> {
                if (key.startsWith(at(islandId, chunk))) {
                    found.put(key.substring(at(islandId, chunk).length()), amount);
                }
            });
            return new ChunkPayments(found);
        }

        @Override
        public void add(IslandId islandId, ChunkPos chunk, String key, long amount) {
            if (failing) {
                throw new IllegalStateException("the table is gone");
            }
            paid.merge(at(islandId, chunk) + key, amount, Long::sum);
        }

        @Override
        public void clear(IslandId islandId, ChunkPos chunk) {
            paid.keySet().removeIf(key -> key.startsWith(at(islandId, chunk)));
        }

        @Override
        public long points(IslandId islandId) {
            return points.getOrDefault(islandId, 0L);
        }

        @Override
        public void addPoints(IslandId islandId, long amount) {
            points.merge(islandId, amount, Long::sum);
        }

        @Override
        public boolean spendPoints(IslandId islandId, long amount) {
            if (points(islandId) < amount) {
                return false;
            }
            points.merge(islandId, -amount, Long::sum);
            return true;
        }

        @Override
        public Optional<BankTransactionOutcome> chargeIslandAndRecord(ChunkIslandCharge charge) {
            if (!together) {
                return Optional.empty();
            }
            charges.add(charge);
            add(charge.islandId(), charge.chunk(), charge.lineKey(), charge.amount());
            IslandBank after = new IslandBank(charge.islandId(), 0, 0, 0, 2, Instant.now());
            return Optional.of(new BankTransactionOutcome.Success(
                    after,
                    new BankTransaction(
                            UUID.randomUUID(),
                            charge.operationId(),
                            charge.islandId(),
                            charge.actorUuid(),
                            charge.currencyColumn(),
                            charge.scale(),
                            -charge.amount(),
                            0,
                            charge.reason(),
                            Instant.now())));
        }
    }

    /** One island's bank, in memory. */
    private static final class Bank implements IslandBankPort {
        long balance;
        long version = 1;
        final Map<String, Long> held = new HashMap<>();
        final List<String> columns = new ArrayList<>();
        final List<Integer> scales = new ArrayList<>();

        @Override
        public Optional<IslandBank> findBankByIslandId(IslandId id) {
            return Optional.of(new IslandBank(id, balance, 0L, 0L, version, Instant.now(), held));
        }

        @Override
        public IslandBank createBank(IslandId id) {
            return findBankByIslandId(id).orElseThrow();
        }

        @Override
        public BankTransactionOutcome executeTransaction(
                IslandId id,
                UUID actor,
                String currency,
                int scale,
                long delta,
                String reason,
                String node,
                long epoch,
                long expectedVersion,
                UUID operation,
                String key) {
            long now = "PRIMARY".equals(currency) ? balance : held.getOrDefault(currency, 0L);
            if (now + delta < 0) {
                return new BankTransactionOutcome.InsufficientFunds(now, delta);
            }
            columns.add(currency);
            scales.add(scale);
            if ("PRIMARY".equals(currency)) {
                balance += delta;
            } else {
                held.put(currency, now + delta);
            }
            version++;
            return new BankTransactionOutcome.Success(
                    findBankByIslandId(id).orElseThrow(),
                    new BankTransaction(
                            UUID.randomUUID(),
                            operation,
                            id,
                            actor,
                            currency,
                            scale,
                            delta,
                            balance,
                            reason,
                            Instant.now()));
        }

        @Override
        public List<BankTransaction> getTransactionHistory(IslandId id, int limit) {
            return List.of();
        }
    }
}
