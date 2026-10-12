package com.uxplima.uxmskyblock.bukkit.vault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.uxplima.uxmskyblock.bukkit.config.VaultConfiguration;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.vault.IslandVaultService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * The vault does not open to a player whose own items are kept aside on a creative plot: what they
 * hold was made there, and stored in the vault it would be taken out anywhere.
 */
class AVaultDoesNotOpenToCreativeItemsTest {

    @SuppressWarnings("NullAway.Init")
    private ServerMock server;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("A sealed player is told why and nothing is asked of the island or the vault")
    void aSealedPlayerIsRefused() {
        IslandStoragePort islands = mock(IslandStoragePort.class);
        SchedulerPort scheduler = mock(SchedulerPort.class);
        IslandVaultWindow window = new IslandVaultWindow(
                mock(IslandVaultService.class),
                islands,
                scheduler,
                VaultConfiguration.defaultConfiguration(),
                Messages.bundled(),
                null);
        PlayerMock player = server.addPlayer();
        new com.uxplima.uxmskyblock.bukkit.creative.SealedInventory(
                        new com.uxplima.uxmskyblock.bukkit.creative.ServerInventoryCodec(), "brix")
                .seal(player);

        window.open(player, 1);

        assertThat(player.nextMessage()).contains("creative place");
        verifyNoInteractions(islands, scheduler);
    }

    @Test
    @DisplayName("A player with nothing kept aside goes on to the usual checks")
    void anUnsealedPlayerGoesOn() {
        IslandVaultWindow window = new IslandVaultWindow(
                mock(IslandVaultService.class),
                mock(IslandStoragePort.class),
                mock(SchedulerPort.class),
                VaultConfiguration.defaultConfiguration(),
                Messages.bundled(),
                null);
        PlayerMock player = server.addPlayer();

        window.open(player, 1);

        assertThat(player.nextMessage()).doesNotContain("creative place");
    }
}
