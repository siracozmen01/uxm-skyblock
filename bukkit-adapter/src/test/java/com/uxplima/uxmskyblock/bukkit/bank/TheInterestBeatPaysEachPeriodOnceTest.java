package com.uxplima.uxmskyblock.bukkit.bank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.uxplima.uxmskyblock.core.application.bank.IslandBankInterest;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.bank.BankTransactionOutcome;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The interest beat looks every minute; an island is asked once a period, and again only if the bank refused. */
class TheInterestBeatPaysEachPeriodOnceTest {

    private static final Instant NOW = Instant.parse("2026-10-14T12:00:00Z");
    private static final ServerNodeId NODE = ServerNodeId.of("node-a");

    private final IslandBankInterest interest = mock(IslandBankInterest.class);
    private final AtomicInteger reads = new AtomicInteger();
    private final Island first = island();
    private final Island second = island();
    private IslandBankInterest.Policy policy =
            new IslandBankInterest.Policy(true, Duration.ofHours(1), 1.0, Duration.ofDays(3), 0L);

    private static Island island() {
        return Island.create(
                IslandId.of(UUID.randomUUID()),
                IslandBounds.fromCenterAndRadius(0, 0, 32),
                new PlayerUuid(UUID.randomUUID()),
                new ProfileId(UUID.randomUUID()),
                NOW);
    }

    private BankInterestBeat beat() {
        return new BankInterestBeat(
                interest,
                () -> policy,
                () -> {
                    reads.incrementAndGet();
                    return List.of(first, second);
                },
                NODE);
    }

    @Test
    @DisplayName("A settled period is not asked again; the next period is")
    void oncePerPeriod() {
        when(interest.pay(any(), any(), any(), any())).thenReturn(new IslandBankInterest.Payment.Paid(1L));
        BankInterestBeat beat = beat();

        beat.payFor(NOW);
        beat.payFor(NOW.plusSeconds(60));
        beat.payFor(NOW.plus(Duration.ofHours(1)));

        verify(interest, times(2)).pay(eq(first), any(), any(), eq(NODE));
        assertThat(reads.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("An island the bank refused is asked again on the next look, alone")
    void aRefusalIsAskedAgain() {
        when(interest.pay(eq(first), any(), any(), any())).thenReturn(new IslandBankInterest.Payment.Paid(1L));
        when(interest.pay(eq(second), any(), any(), any()))
                .thenReturn(new IslandBankInterest.Payment.Refused(new BankTransactionOutcome.StaleVersion(1, 2)))
                .thenReturn(new IslandBankInterest.Payment.Paid(1L));
        BankInterestBeat beat = beat();

        beat.payFor(NOW);
        beat.payFor(NOW.plusSeconds(60));
        beat.payFor(NOW.plusSeconds(120));

        verify(interest, times(1)).pay(eq(first), any(), any(), any());
        verify(interest, times(2)).pay(eq(second), any(), any(), any());
        assertThat(reads.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Interest off: nothing is scheduled and nothing is paid")
    void offDoesNothing() {
        policy = new IslandBankInterest.Policy(false, Duration.ofHours(1), 1.0, Duration.ofDays(3), 0L);
        SchedulerPort scheduler = mock(SchedulerPort.class);
        BankInterestBeat beat = beat();

        beat.start(scheduler);
        beat.payFor(NOW);

        verify(scheduler, never()).repeatAsync(any(), any(), any());
        verify(interest, never()).pay(any(), any(), any(), any());
    }
}
