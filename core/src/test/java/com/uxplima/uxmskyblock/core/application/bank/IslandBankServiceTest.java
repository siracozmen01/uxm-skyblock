package com.uxplima.uxmskyblock.core.application.bank;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.application.island.IslandAuthorityPort;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.domain.bank.BankTransaction;
import com.uxplima.uxmskyblock.core.domain.bank.BankTransactionOutcome;
import com.uxplima.uxmskyblock.core.domain.bank.IslandBank;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandAuthorityOutcome;
import com.uxplima.uxmskyblock.core.domain.island.IslandAuthorityRecord;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IslandBankServiceTest {

    private FakeBankPort bankPort;
    private FakeStoragePort storagePort;
    private FakeAuthorityPort authorityPort;
    private IslandBankService bankService;

    private ProfileId profileId;
    private PlayerUuid playerUuid;
    private IslandId islandId;
    private ServerNodeId nodeId;

    @BeforeEach
    void setUp() {
        bankPort = new FakeBankPort();
        storagePort = new FakeStoragePort();
        authorityPort = new FakeAuthorityPort();
        bankService = new IslandBankService(bankPort, storagePort, authorityPort);

        profileId = new ProfileId(UUID.randomUUID());
        playerUuid = new PlayerUuid(UUID.randomUUID());
        islandId = IslandId.of(UUID.randomUUID());
        nodeId = ServerNodeId.of("node-1");

        storagePort.profileToIsland.put(profileId, islandId);
        bankPort.banks.put(islandId, new IslandBank(islandId, 5000L, 0L, 0L, 1L, Instant.now()));
        authorityPort.authorities.put(
                islandId,
                new IslandAuthorityRecord(islandId, nodeId, 1L, Instant.now().plusSeconds(3600), Instant.now()));
    }

    @Test
    @DisplayName("A keyed move names the column of its currency: the island's money in cents, any other in units")
    void aMoveNamesItsCurrency() {
        BankTransactionOutcome money = bankService.moveOnce(profileId, playerUuid, 250L, "In", nodeId, "k-money");
        BankTransactionOutcome experience =
                bankService.moveOnce(profileId, playerUuid, "experience", 40L, "In", nodeId, "k-experience");

        assertThat(((BankTransactionOutcome.Success) money).transaction().currencyId())
                .isEqualTo("PRIMARY");
        assertThat(((BankTransactionOutcome.Success) money).transaction().currencyScale())
                .isEqualTo(2);
        assertThat(((BankTransactionOutcome.Success) experience).transaction().currencyId())
                .isEqualTo("experience");
        assertThat(((BankTransactionOutcome.Success) experience).transaction().currencyScale())
                .isZero();
    }

    @Test
    @DisplayName("A move of the money that lands tells the balance; another currency, a refused move or a failing"
            + " listener does not stop it")
    void aBalanceIsTold() {
        List<String> told = new java.util.ArrayList<>();
        bankService.whenBalanced((island, balance) -> {
            throw new IllegalStateException("a listener that fails");
        });
        bankService.whenBalanced((island, balance) -> told.add(island.equals(islandId) + " " + balance));

        BankTransactionOutcome landed = bankService.deposit(profileId, playerUuid, 2000L, nodeId);
        bankService.moveOnce(profileId, playerUuid, "experience", 40L, "In", nodeId, "k-experience");
        bankService.deposit(new ProfileId(UUID.randomUUID()), playerUuid, 1L, nodeId);

        assertThat(landed).isInstanceOf(BankTransactionOutcome.Success.class);
        assertThat(told).containsExactly("true 7000");
    }

    @Test
    @DisplayName("The bank of a profile is read with everything it holds, and nothing without an island")
    void theBankOfAProfile() {
        bankPort.banks.put(
                islandId, new IslandBank(islandId, 5000L, 0L, 0L, 1L, Instant.now(), java.util.Map.of("points", 9L)));

        assertThat(bankService.findBank(profileId).orElseThrow().heldOf("points"))
                .isEqualTo(9L);
        assertThat(bankService.findBank(new ProfileId(UUID.randomUUID()))).isEmpty();
    }

    @Test
    @DisplayName("getBalanceMinorUnits returns current balance when island and bank exist")
    void getBalanceReturnsValue() {
        Optional<Long> balance = bankService.getBalanceMinorUnits(profileId);
        assertThat(balance).contains(5000L);
    }

    @Test
    @DisplayName("getBalanceMinorUnits returns empty when profile has no island")
    void getBalanceReturnsEmptyWhenNoIsland() {
        Optional<Long> balance = bankService.getBalanceMinorUnits(new ProfileId(UUID.randomUUID()));
        assertThat(balance).isEmpty();
    }

    @Test
    @DisplayName("deposit executes positive transaction and returns Success")
    void depositExecutesTransaction() {
        BankTransactionOutcome outcome = bankService.deposit(profileId, playerUuid, 2000L, nodeId);

        assertThat(outcome).isInstanceOf(BankTransactionOutcome.Success.class);
        assertThat(bankPort.lastDelta).isEqualTo(2000L);
    }

    @Test
    @DisplayName("withdraw executes negative transaction and returns Success")
    void withdrawExecutesTransaction() {
        BankTransactionOutcome outcome = bankService.withdraw(profileId, playerUuid, 1500L, nodeId);

        assertThat(outcome).isInstanceOf(BankTransactionOutcome.Success.class);
        assertThat(bankPort.lastDelta).isEqualTo(-1500L);
    }

    @Test
    @DisplayName("returns AuthorityRejected when profile has no island")
    void returnsRejectedWhenNoIsland() {
        ProfileId unknown = new ProfileId(UUID.randomUUID());
        BankTransactionOutcome outcome = bankService.deposit(unknown, playerUuid, 1000L, nodeId);

        assertThat(outcome).isInstanceOf(BankTransactionOutcome.AuthorityRejected.class);
    }

    @Test
    @DisplayName("returns AuthorityRejected when local node does not hold authority")
    void returnsRejectedWhenWrongNode() {
        ServerNodeId wrongNode = ServerNodeId.of("wrong-node");
        BankTransactionOutcome outcome = bankService.deposit(profileId, playerUuid, 1000L, wrongNode);

        assertThat(outcome).isInstanceOf(BankTransactionOutcome.AuthorityRejected.class);
        assertThat(((BankTransactionOutcome.AuthorityRejected) outcome).reason())
                .contains("does not hold authority");
    }

    @Test
    @DisplayName("returns AuthorityRejected when authority lease is expired")
    void returnsRejectedWhenLeaseExpired() {
        authorityPort.authorities.put(
                islandId,
                new IslandAuthorityRecord(
                        islandId,
                        nodeId,
                        1L,
                        Instant.now().minusSeconds(10),
                        Instant.now().minusSeconds(3600)));

        BankTransactionOutcome outcome = bankService.deposit(profileId, playerUuid, 1000L, nodeId);

        assertThat(outcome).isInstanceOf(BankTransactionOutcome.AuthorityRejected.class);
        assertThat(((BankTransactionOutcome.AuthorityRejected) outcome).reason())
                .contains("Authority lease expired");
    }

    @Test
    @DisplayName("returns AuthorityRejected when no authority record exists")
    void returnsRejectedWhenNoAuthorityRecord() {
        authorityPort.authorities.remove(islandId);

        BankTransactionOutcome outcome = bankService.deposit(profileId, playerUuid, 1000L, nodeId);

        assertThat(outcome).isInstanceOf(BankTransactionOutcome.AuthorityRejected.class);
        assertThat(((BankTransactionOutcome.AuthorityRejected) outcome).reason())
                .contains("No authority record found");
    }

    @Test
    @DisplayName("uses legitimate authority epoch from existing authority record")
    void usesLegitimateEpochFromRecord() {
        authorityPort.authorities.put(
                islandId,
                new IslandAuthorityRecord(islandId, nodeId, 42L, Instant.now().plusSeconds(3600), Instant.now()));

        BankTransactionOutcome outcome = bankService.deposit(profileId, playerUuid, 2000L, nodeId);

        assertThat(outcome).isInstanceOf(BankTransactionOutcome.Success.class);
        assertThat(bankPort.lastEpoch).isEqualTo(42L);
    }

    private static class FakeBankPort implements IslandBankPort {
        final Map<IslandId, IslandBank> banks = new HashMap<>();
        long lastDelta;
        long lastEpoch;

        @Override
        public Optional<IslandBank> findBankByIslandId(IslandId islandId) {
            return Optional.ofNullable(banks.get(islandId));
        }

        @Override
        public IslandBank createBank(IslandId islandId) {
            IslandBank bank = IslandBank.initial(islandId);
            banks.put(islandId, bank);
            return bank;
        }

        @Override
        public BankTransactionOutcome executeTransaction(
                IslandId islandId,
                UUID actorUuid,
                String currencyId,
                int currencyScale,
                long deltaAmountMinorUnits,
                String reason,
                String currentNode,
                long expectedEpoch,
                long expectedVersion,
                UUID operationId,
                String idempotencyKey) {
            this.lastDelta = deltaAmountMinorUnits;
            this.lastEpoch = expectedEpoch;
            IslandBank bank =
                    new IslandBank(islandId, 5000 + deltaAmountMinorUnits, 0L, 0L, expectedVersion + 1, Instant.now());
            BankTransaction tx = new BankTransaction(
                    UUID.randomUUID(),
                    operationId,
                    islandId,
                    actorUuid,
                    currencyId,
                    currencyScale,
                    deltaAmountMinorUnits,
                    5000 + deltaAmountMinorUnits,
                    reason,
                    Instant.now());
            return new BankTransactionOutcome.Success(bank, tx);
        }

        @Override
        public List<BankTransaction> getTransactionHistory(IslandId islandId, int limit) {
            return List.of();
        }
    }

    private static class FakeStoragePort implements IslandStoragePort {
        final Map<ProfileId, IslandId> profileToIsland = new HashMap<>();

        @Override
        public void saveIsland(Island island, IslandLocation location) {}

        @Override
        public Optional<Island> findIslandById(IslandId islandId) {
            return Optional.empty();
        }

        @Override
        public Optional<IslandId> findIslandIdByProfileId(ProfileId profileId) {
            return Optional.ofNullable(profileToIsland.get(profileId));
        }

        @Override
        public Optional<IslandLocation> findLocationByIslandId(IslandId islandId) {
            return Optional.empty();
        }

        @Override
        public void deleteIsland(IslandId islandId) {}
    }

    private static class FakeAuthorityPort implements IslandAuthorityPort {
        final Map<IslandId, IslandAuthorityRecord> authorities = new HashMap<>();

        @Override
        public IslandAuthorityOutcome acquireAuthority(IslandId islandId, ServerNodeId nodeId, int leaseSeconds) {
            return new IslandAuthorityOutcome.Success(1L);
        }

        @Override
        public IslandAuthorityOutcome renewAuthority(
                IslandId islandId, ServerNodeId nodeId, long expectedEpoch, int leaseSeconds) {
            return new IslandAuthorityOutcome.Success(expectedEpoch);
        }

        @Override
        public IslandAuthorityOutcome takeoverAuthority(
                IslandId islandId, ServerNodeId newNodeId, long expectedEpoch, int leaseSeconds) {
            return new IslandAuthorityOutcome.Success(expectedEpoch + 1);
        }

        @Override
        public Optional<IslandAuthorityRecord> findAuthority(IslandId islandId) {
            return Optional.ofNullable(authorities.get(islandId));
        }

        @Override
        public com.uxplima.uxmskyblock.core.domain.island.IslandAuthoritySweep sweepAuthority(
                ServerNodeId nodeId, String worldName, int leaseSeconds) {
            return com.uxplima.uxmskyblock.core.domain.island.IslandAuthoritySweep.NOTHING;
        }
    }

    @Test
    @DisplayName("A profile with no island is refused as having no island, whatever the sentence says")
    void noIslandIsItsOwnKind() {
        ProfileId stranger = new ProfileId(UUID.randomUUID());

        assertThat(bankService.deposit(stranger, playerUuid, 100L, nodeId))
                .isEqualTo(new BankTransactionOutcome.AuthorityRejected(
                        BankTransactionOutcome.AuthorityRejected.Kind.NO_ISLAND,
                        "No island associated with profile " + stranger));
    }

    @Test
    @DisplayName("An island another node holds is refused as held elsewhere")
    void anotherNodeIsItsOwnKind() {
        authorityPort.authorities.put(
                islandId,
                new IslandAuthorityRecord(
                        islandId, ServerNodeId.of("node-7"), 1L, Instant.now().plusSeconds(3600), Instant.now()));

        BankTransactionOutcome outcome = bankService.deposit(profileId, playerUuid, 100L, nodeId);

        assertThat(outcome)
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(
                        BankTransactionOutcome.AuthorityRejected.class))
                .extracting(BankTransactionOutcome.AuthorityRejected::kind)
                .isEqualTo(BankTransactionOutcome.AuthorityRejected.Kind.NO_AUTHORITY);
    }

    @Test
    @DisplayName("An island whose lease ran out is refused as held elsewhere")
    void anExpiredLeaseIsItsOwnKind() {
        authorityPort.authorities.put(
                islandId,
                new IslandAuthorityRecord(islandId, nodeId, 1L, Instant.now().minusSeconds(5), Instant.now()));

        BankTransactionOutcome outcome = bankService.withdraw(profileId, playerUuid, 100L, nodeId);

        assertThat(outcome)
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(
                        BankTransactionOutcome.AuthorityRejected.class))
                .extracting(BankTransactionOutcome.AuthorityRejected::kind)
                .isEqualTo(BankTransactionOutcome.AuthorityRejected.Kind.NO_AUTHORITY);
    }
}
