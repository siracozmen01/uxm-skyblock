package com.uxplima.uxmskyblock.bukkit.bank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.application.bank.IslandBankPort;
import com.uxplima.uxmskyblock.core.application.bank.IslandBankService;
import com.uxplima.uxmskyblock.core.domain.bank.BankTransactionOutcome;
import com.uxplima.uxmskyblock.core.domain.bank.IslandBank;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** SuperiorSkyblock2 pays a share of the bank back when an island is disbanded; here it was simply cleared. */
class ADeletedIslandPaysItsOwnerBackTest {

    private final IslandBankPort banks = mock(IslandBankPort.class);
    private final IslandBankService bank = mock(IslandBankService.class);
    private final List<Long> paid = new ArrayList<>();
    private final PlayerUuid owner = new PlayerUuid(UUID.randomUUID());
    private final Island island = Island.create(
            IslandId.of(UUID.randomUUID()),
            IslandBounds.fromCenterAndRadius(0, 0, 32),
            owner,
            new ProfileId(UUID.randomUUID()),
            Instant.now());
    private double share = 0.5;
    private boolean walletTakes = true;

    @BeforeEach
    void setUp() {
        when(banks.findBankByIslandId(island.id()))
                .thenReturn(Optional.of(new IslandBank(island.id(), 1_001L, 0L, 0L, 1L, Instant.now())));
        when(bank.depositToIsland(any(), any(), anyLong(), anyString(), any(), any(), anyString(), anyString()))
                .thenReturn(mock(BankTransactionOutcome.Success.class));
    }

    private void delete() {
        new DeleteRefund(
                        banks,
                        bank,
                        () -> share,
                        (player, minor) -> {
                            if (player.equals(owner) && walletTakes) {
                                paid.add(minor);
                                return true;
                            }
                            return false;
                        },
                        ServerNodeId.of("node-a"))
                .accept(island);
    }

    @Test
    @DisplayName("The share leaves the bank once, keyed by the island, and reaches the owner's wallet")
    void theOwnerIsPaidTheShare() {
        delete();

        String key = "delete-refund-" + island.id().value();
        verify(bank)
                .depositToIsland(
                        eq(island.id()), eq(owner), eq(-500L), anyString(), any(), any(), eq(key), eq("DELETE_REFUND"));
        assertThat(paid).containsExactly(500L);
    }

    @Test
    @DisplayName("A wallet that will not take it puts the share back into the bank")
    void aRefusedWalletPutsItBack() {
        walletTakes = false;
        delete();

        String key = "delete-refund-" + island.id().value();
        verify(bank)
                .depositToIsland(
                        eq(island.id()), eq(owner), eq(-500L), anyString(), any(), any(), eq(key), anyString());
        verify(bank)
                .depositToIsland(
                        eq(island.id()),
                        eq(owner),
                        eq(500L),
                        anyString(),
                        any(),
                        any(),
                        eq(key + "-back"),
                        anyString());
    }

    @Test
    @DisplayName("A share of zero, or a bank that would not give it up, pays nothing")
    void nothingToPay() {
        share = 0.0;
        delete();
        verify(bank, never())
                .depositToIsland(any(), any(), anyLong(), anyString(), any(), any(), anyString(), anyString());

        share = 1.0;
        when(bank.depositToIsland(any(), any(), anyLong(), anyString(), any(), any(), anyString(), anyString()))
                .thenReturn(new BankTransactionOutcome.StaleVersion(1, 2));
        delete();
        assertThat(paid).isEmpty();
    }
}
