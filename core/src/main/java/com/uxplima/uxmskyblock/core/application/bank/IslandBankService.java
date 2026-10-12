package com.uxplima.uxmskyblock.core.application.bank;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.application.event.OutboxPort;
import com.uxplima.uxmskyblock.core.application.island.IslandAuthorityPort;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.domain.bank.BankTransactionOutcome;
import com.uxplima.uxmskyblock.core.domain.bank.IslandBank;
import com.uxplima.uxmskyblock.core.domain.event.EventId;
import com.uxplima.uxmskyblock.core.domain.event.JsonText;
import com.uxplima.uxmskyblock.core.domain.event.StagedOutboxEvent;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.IslandAuthorityRecord;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import org.jspecify.annotations.Nullable;

/**
 * Application service managing island bank balances and authority-fenced transactions.
 */
public final class IslandBankService {

    private static final java.util.logging.Logger LOGGER =
            java.util.logging.Logger.getLogger(IslandBankService.class.getName());

    private final IslandBankPort islandBankPort;
    private final IslandStoragePort islandStoragePort;
    private final IslandAuthorityPort islandAuthorityPort;
    private final @Nullable OutboxPort outboxPort;

    public IslandBankService(
            IslandBankPort islandBankPort,
            IslandStoragePort islandStoragePort,
            IslandAuthorityPort islandAuthorityPort,
            @Nullable OutboxPort outboxPort) {
        this.islandBankPort = Objects.requireNonNull(islandBankPort, "islandBankPort must not be null");
        this.islandStoragePort = Objects.requireNonNull(islandStoragePort, "islandStoragePort must not be null");
        this.islandAuthorityPort = Objects.requireNonNull(islandAuthorityPort, "islandAuthorityPort must not be null");
        this.outboxPort = outboxPort;
    }

    public IslandBankService(
            IslandBankPort islandBankPort,
            IslandStoragePort islandStoragePort,
            IslandAuthorityPort islandAuthorityPort) {
        this(islandBankPort, islandStoragePort, islandAuthorityPort, null);
    }

    public Optional<Long> getBalanceMinorUnits(ProfileId profileId) {
        Objects.requireNonNull(profileId, "profileId must not be null");
        Optional<IslandId> optIslandId = islandStoragePort.findIslandIdByProfileId(profileId);
        if (optIslandId.isEmpty()) {
            return Optional.empty();
        }
        return islandBankPort.findBankByIslandId(optIslandId.get()).map(IslandBank::primaryBalanceMinorUnits);
    }

    /** The bank of the profile's island, everything it holds included, or nothing without an island or a bank. */
    private final java.util.List<java.util.function.BiConsumer<IslandId, Long>> balanceListeners =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    /**
     * Told the island's balance of the main currency, in minor units, each time a move of it lands. A listener
     * that fails is said in the console and the move stands.
     */
    public void whenBalanced(java.util.function.BiConsumer<IslandId, Long> listener) {
        balanceListeners.add(Objects.requireNonNull(listener, "listener must not be null"));
    }

    public Optional<IslandBank> findBank(ProfileId profileId) {
        Objects.requireNonNull(profileId, "profileId must not be null");
        return islandStoragePort.findIslandIdByProfileId(profileId).flatMap(islandBankPort::findBankByIslandId);
    }

    public Optional<IslandId> findIslandIdByProfileId(ProfileId profileId) {
        Objects.requireNonNull(profileId, "profileId must not be null");
        return islandStoragePort.findIslandIdByProfileId(profileId);
    }

    public BankTransactionOutcome depositToIsland(
            IslandId islandId, PlayerUuid playerUuid, long amountMinorUnits, ServerNodeId serverNodeId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        return execute(islandId, playerUuid, amountMinorUnits, "Bank deposit", serverNodeId);
    }

    public BankTransactionOutcome depositToIsland(
            IslandId islandId, PlayerUuid playerUuid, long amountMinorUnits, String reason, ServerNodeId serverNodeId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        return execute(islandId, playerUuid, amountMinorUnits, reason, serverNodeId);
    }

    public BankTransactionOutcome withdrawFromIsland(
            IslandId islandId, PlayerUuid playerUuid, long amountMinorUnits, ServerNodeId serverNodeId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        return execute(islandId, playerUuid, -amountMinorUnits, "Bank withdrawal", serverNodeId);
    }

    /** Takes money out of the island bank, saying what for. */
    public BankTransactionOutcome withdrawFromIsland(
            IslandId islandId, PlayerUuid playerUuid, long amountMinorUnits, String reason, ServerNodeId serverNodeId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        return execute(islandId, playerUuid, -amountMinorUnits, reason, serverNodeId);
    }

    public BankTransactionOutcome deposit(
            ProfileId profileId, PlayerUuid playerUuid, long amountMinorUnits, ServerNodeId serverNodeId) {
        Objects.requireNonNull(profileId, "profileId must not be null");
        Optional<IslandId> optIslandId = islandStoragePort.findIslandIdByProfileId(profileId);
        if (optIslandId.isEmpty()) {
            return new BankTransactionOutcome.AuthorityRejected(
                    BankTransactionOutcome.AuthorityRejected.Kind.NO_ISLAND,
                    "No island associated with profile " + profileId);
        }
        return execute(optIslandId.get(), playerUuid, amountMinorUnits, "Player deposit", serverNodeId);
    }

    public BankTransactionOutcome withdraw(
            ProfileId profileId, PlayerUuid playerUuid, long amountMinorUnits, ServerNodeId serverNodeId) {
        Objects.requireNonNull(profileId, "profileId must not be null");
        Optional<IslandId> optIslandId = islandStoragePort.findIslandIdByProfileId(profileId);
        if (optIslandId.isEmpty()) {
            return new BankTransactionOutcome.AuthorityRejected(
                    BankTransactionOutcome.AuthorityRejected.Kind.NO_ISLAND,
                    "No island associated with profile " + profileId);
        }
        return execute(optIslandId.get(), playerUuid, -amountMinorUnits, "Player withdrawal", serverNodeId);
    }

    /** The scope a wallet and bank saga records its bank moves under. */
    public static final String SAGA_SCOPE = "ECONOMY_SAGA";

    /**
     * Moves money in or out of the profile's island bank once per {@code idempotencyKey}.
     *
     * <p>A saga between a wallet and the bank repeats a bank move when it recovers after a crash, and
     * cannot tell whether the move had already landed. Keyed, the second ask is answered as the same
     * operation instead of moving the money again.
     *
     * @param deltaMinorUnits positive to put money in, negative to take it out
     */
    public BankTransactionOutcome moveOnce(
            ProfileId profileId,
            PlayerUuid playerUuid,
            long deltaMinorUnits,
            String reason,
            ServerNodeId serverNodeId,
            String idempotencyKey) {
        return moveOnce(
                profileId,
                playerUuid,
                BankCurrencies.PRIMARY_MONEY,
                deltaMinorUnits,
                reason,
                serverNodeId,
                idempotencyKey);
    }

    /**
     * The same, in {@code currency}: the island's own money under {@link BankCurrencies#PRIMARY_MONEY}, in minor
     * units, or an operator currency under its id, in whole units.
     */
    public BankTransactionOutcome moveOnce(
            ProfileId profileId,
            PlayerUuid playerUuid,
            String currency,
            long deltaMinorUnits,
            String reason,
            ServerNodeId serverNodeId,
            String idempotencyKey) {
        Objects.requireNonNull(profileId, "profileId must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        Optional<IslandId> optIslandId = islandStoragePort.findIslandIdByProfileId(profileId);
        if (optIslandId.isEmpty()) {
            return new BankTransactionOutcome.AuthorityRejected(
                    BankTransactionOutcome.AuthorityRejected.Kind.NO_ISLAND,
                    "No island associated with profile " + profileId);
        }
        UUID operationId = UUID.nameUUIDFromBytes(
                (SAGA_SCOPE + ":" + idempotencyKey).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return execute(
                optIslandId.get(),
                playerUuid,
                BankCurrencies.columnOf(currency),
                BankCurrencies.scaleOf(currency),
                deltaMinorUnits,
                reason,
                serverNodeId,
                operationId,
                idempotencyKey,
                SAGA_SCOPE);
    }

    /**
     * Moves {@code delta} of {@code currency} in or out of the island's bank, saying what for: the island's own money
     * under {@link BankCurrencies#PRIMARY_COLUMN}, in minor units, or an operator currency under its id, in whole
     * units.
     *
     * @param delta positive to put in, negative to take out
     */
    public BankTransactionOutcome move(
            IslandId islandId,
            PlayerUuid playerUuid,
            String currency,
            long delta,
            String reason,
            ServerNodeId serverNodeId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        return execute(
                islandId,
                playerUuid,
                BankCurrencies.columnOf(currency),
                BankCurrencies.scaleOf(currency),
                delta,
                reason,
                serverNodeId,
                null,
                null,
                "ISLAND_BANK");
    }

    /** Whether a keyed bank move has landed, now or on an earlier ask. */
    public static boolean landed(BankTransactionOutcome outcome) {
        return outcome instanceof BankTransactionOutcome.Success
                || (outcome instanceof BankTransactionOutcome.DuplicateOperation dup && "APPLIED".equals(dup.status()));
    }

    public BankTransactionOutcome depositToIsland(
            IslandId islandId,
            PlayerUuid playerUuid,
            long amountMinorUnits,
            String reason,
            ServerNodeId serverNodeId,
            UUID operationId,
            String idempotencyKey,
            String operationScope) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        return execute(
                islandId,
                playerUuid,
                BankCurrencies.PRIMARY_COLUMN,
                BankCurrencies.PRIMARY_SCALE,
                amountMinorUnits,
                reason,
                serverNodeId,
                operationId,
                idempotencyKey,
                operationScope);
    }

    private BankTransactionOutcome execute(
            IslandId islandId, PlayerUuid playerUuid, long deltaMinorUnits, String reason, ServerNodeId serverNodeId) {
        return execute(
                islandId,
                playerUuid,
                BankCurrencies.PRIMARY_COLUMN,
                BankCurrencies.PRIMARY_SCALE,
                deltaMinorUnits,
                reason,
                serverNodeId,
                null,
                null,
                "ISLAND_BANK");
    }

    private BankTransactionOutcome execute(
            IslandId islandId,
            PlayerUuid playerUuid,
            String column,
            int scale,
            long deltaMinorUnits,
            String reason,
            ServerNodeId serverNodeId,
            @Nullable UUID customOperationId,
            @Nullable String customIdempotencyKey,
            @Nullable String customOperationScope) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(playerUuid, "playerUuid must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(serverNodeId, "serverNodeId must not be null");

        Optional<IslandBank> optBank = islandBankPort.findBankByIslandId(islandId);
        IslandBank bank = optBank.orElseGet(() -> islandBankPort.createBank(islandId));

        Optional<IslandAuthorityRecord> optAuth = islandAuthorityPort.findAuthority(islandId);
        if (optAuth.isEmpty()) {
            return new BankTransactionOutcome.AuthorityRejected(
                    BankTransactionOutcome.AuthorityRejected.Kind.NO_AUTHORITY,
                    "No authority record found for island " + islandId);
        }

        IslandAuthorityRecord auth = optAuth.get();
        if (!auth.authoritativeNode().equals(serverNodeId)) {
            return new BankTransactionOutcome.AuthorityRejected(
                    BankTransactionOutcome.AuthorityRejected.Kind.NO_AUTHORITY,
                    "Local node " + serverNodeId + " does not hold authority for island " + islandId + " (held by "
                            + auth.authoritativeNode() + ")");
        }

        if (auth.leaseExpiresAt().isBefore(java.time.Instant.now())) {
            return new BankTransactionOutcome.AuthorityRejected(
                    BankTransactionOutcome.AuthorityRejected.Kind.NO_AUTHORITY,
                    "Authority lease expired at " + auth.leaseExpiresAt() + " for island " + islandId);
        }

        long epoch = auth.authorityEpoch();

        UUID operationId = (customOperationId != null) ? customOperationId : UUID.randomUUID();
        String idempotencyKey = (customIdempotencyKey != null) ? customIdempotencyKey : "tx-" + operationId;
        String operationScope = (customOperationScope != null) ? customOperationScope : "ISLAND_BANK";

        StagedOutboxEvent outboxEvent = (outboxPort != null)
                ? new StagedOutboxEvent(
                        EventId.random(),
                        "ISLAND_BANK_TRANSACTION",
                        islandId.value().toString(),
                        String.format(
                                "{\"islandId\":\"%s\",\"playerUuid\":\"%s\",\"deltaMinorUnits\":%d,\"currency\":\"%s\",\"reason\":\"%s\"}",
                                islandId.value(),
                                playerUuid.value(),
                                deltaMinorUnits,
                                column,
                                JsonText.escaped(reason)))
                : null;

        BankTransactionOutcome outcome = islandBankPort.executeTransaction(
                islandId,
                playerUuid.value(),
                column,
                scale,
                deltaMinorUnits,
                reason,
                serverNodeId.value(),
                epoch,
                bank.version(),
                operationId,
                idempotencyKey,
                operationScope,
                outboxEvent);
        if (outcome instanceof BankTransactionOutcome.Success done && BankCurrencies.PRIMARY_COLUMN.equals(column)) {
            long balance = done.updatedBank().primaryBalanceMinorUnits();
            for (java.util.function.BiConsumer<IslandId, Long> listener : balanceListeners) {
                try {
                    listener.accept(islandId, balance);
                } catch (RuntimeException e) {
                    LOGGER.log(java.util.logging.Level.WARNING, "A listener to the bank's balance failed", e);
                }
            }
        }
        return outcome;
    }
}
