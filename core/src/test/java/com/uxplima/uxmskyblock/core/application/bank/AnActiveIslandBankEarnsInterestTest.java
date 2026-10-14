package com.uxplima.uxmskyblock.core.application.bank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.application.inactivity.PlayerActivityProvider;
import com.uxplima.uxmskyblock.core.domain.bank.BankTransactionOutcome;
import com.uxplima.uxmskyblock.core.domain.bank.IslandBank;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.island.IslandMember;
import com.uxplima.uxmskyblock.core.domain.island.IslandRole;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** SuperiorSkyblock2 pays bank interest to islands whose members played lately; this plugin's bank paid none. */
class AnActiveIslandBankEarnsInterestTest {

    private static final Instant NOW = Instant.parse("2026-10-14T12:00:00Z");
    private static final ServerNodeId NODE = ServerNodeId.of("node-a");

    private final IslandBankPort banks = mock(IslandBankPort.class);
    private final IslandBankService bank = mock(IslandBankService.class);
    private final Map<PlayerUuid, Instant> lastPlayed = new HashMap<>();
    private final PlayerActivityProvider activity = (player, profile) -> Optional.ofNullable(lastPlayed.get(player));
    private final PlayerUuid owner = new PlayerUuid(UUID.randomUUID());
    private final PlayerUuid member = new PlayerUuid(UUID.randomUUID());
    private long cap = Long.MAX_VALUE;
    private Island island = Island.create(
            IslandId.of(UUID.randomUUID()),
            IslandBounds.fromCenterAndRadius(0, 0, 32),
            owner,
            new ProfileId(UUID.randomUUID()),
            NOW.minus(Duration.ofDays(30)));

    @BeforeEach
    void setUp() {
        island = island.addMember(new IslandMember(member, new ProfileId(UUID.randomUUID()), IslandRole.MEMBER, NOW));
        when(banks.findBankByIslandId(island.id()))
                .thenReturn(Optional.of(new IslandBank(island.id(), 1_000_00L, 0L, 0L, 1L, NOW)));
        when(bank.depositToIsland(any(), any(), anyLong(), anyString(), any(), any(), anyString(), anyString()))
                .thenReturn(mock(BankTransactionOutcome.Success.class));
    }

    private IslandBankInterest.Payment pay(IslandBankInterest.Policy policy) {
        return new IslandBankInterest(banks, bank, activity, id -> cap).pay(island, policy, NOW, NODE);
    }

    private static IslandBankInterest.Policy policy(double percent, long maxMinor) {
        return new IslandBankInterest.Policy(true, Duration.ofHours(24), percent, Duration.ofDays(3), maxMinor);
    }

    @Test
    @DisplayName("A member who played lately earns the island its share, keyed by the period so it is paid once")
    void paidOncePerPeriod() {
        lastPlayed.put(member, NOW.minus(Duration.ofDays(3)));

        assertThat(pay(policy(2.5, 0))).isEqualTo(new IslandBankInterest.Payment.Paid(25_00L));

        String key = "interest-" + island.id().value() + "-" + policy(2.5, 0).periodOf(NOW);
        verify(bank)
                .depositToIsland(
                        eq(island.id()),
                        eq(new PlayerUuid(IslandBankInterest.SYSTEM_INTEREST_ACTOR)),
                        eq(25_00L),
                        anyString(),
                        eq(NODE),
                        eq(UUID.nameUUIDFromBytes(key.getBytes(java.nio.charset.StandardCharsets.UTF_8))),
                        eq(key),
                        eq("BANK_INTEREST"));
    }

    @Test
    @DisplayName("Nobody played within the window, or interest is off: nothing is paid")
    void quietIslandsEarnNothing() {
        lastPlayed.put(owner, NOW.minus(Duration.ofDays(3)).minusSeconds(1));
        assertThat(pay(policy(2.5, 0))).isInstanceOf(IslandBankInterest.Payment.Nothing.class);

        lastPlayed.put(owner, NOW);
        assertThat(pay(new IslandBankInterest.Policy(false, Duration.ofHours(24), 2.5, Duration.ofDays(3), 0)))
                .isInstanceOf(IslandBankInterest.Payment.Nothing.class);
        verify(bank, never())
                .depositToIsland(any(), any(), anyLong(), anyString(), any(), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("A payment stops at its most, and at the room left under the bank limit")
    void heldToTheMostAndTheLimit() {
        lastPlayed.put(owner, NOW);
        assertThat(pay(policy(10, 3_00L))).isEqualTo(new IslandBankInterest.Payment.Paid(3_00L));

        cap = 1_001_50L;
        assertThat(pay(policy(10, 0))).isEqualTo(new IslandBankInterest.Payment.Paid(1_50L));

        cap = 1_000_00L;
        assertThat(pay(policy(10, 0))).describedAs("full").isInstanceOf(IslandBankInterest.Payment.Nothing.class);
    }

    @Test
    @DisplayName("A bank that will not take it now is said to have refused, so the beat asks again")
    void aRefusalIsSaid() {
        lastPlayed.put(owner, NOW);
        when(bank.depositToIsland(any(), any(), anyLong(), anyString(), any(), any(), anyString(), anyString()))
                .thenReturn(new BankTransactionOutcome.StaleVersion(1, 2));
        assertThat(pay(policy(1, 0))).isInstanceOf(IslandBankInterest.Payment.Refused.class);
    }
}
