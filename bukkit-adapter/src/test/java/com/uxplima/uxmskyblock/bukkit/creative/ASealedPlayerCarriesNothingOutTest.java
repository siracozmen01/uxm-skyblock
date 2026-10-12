package com.uxplima.uxmskyblock.bukkit.creative;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.bukkit.entity.Item;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;

import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * A player whose items are kept aside throws nothing and opens no ender chest, wherever they stand; a
 * player with nothing kept aside does both as always.
 */
class ASealedPlayerCarriesNothingOutTest extends MockBukkitHarness {

    private final SealedInventoryGuard guard = new SealedInventoryGuard();

    @SuppressWarnings("NullAway.Init")
    private PlayerMock player;

    @BeforeEach
    void setUpPlayer() {
        player = createPlayer("Builder");
    }

    @Test
    @DisplayName("Sealed, the player throws nothing and opens no ender chest, but any other window opens")
    void aSealedPlayerIsHeld() {
        new SealedInventory(
                        new InventoryCodec() {
                            @Override
                            public byte[] write(org.bukkit.inventory.ItemStack[] items) {
                                return new byte[] {1};
                            }

                            @Override
                            public org.bukkit.inventory.ItemStack[] read(byte[] bytes) {
                                return new org.bukkit.inventory.ItemStack[0];
                            }
                        },
                        "brix")
                .seal(player);

        assertThat(threw()).isFalse();
        assertThat(opened(player.getEnderChest())).isFalse();
        assertThat(opened(server.createInventory(null, 27))).isTrue();
    }

    @Test
    @DisplayName("With nothing kept aside the player throws and opens an ender chest as always")
    void anUnsealedPlayerIsNot() {
        assertThat(threw()).isTrue();
        assertThat(opened(player.getEnderChest())).isTrue();
    }

    private boolean threw() {
        PlayerDropItemEvent event = new PlayerDropItemEvent(player, mock(Item.class));
        guard.onThrow(event);
        return !event.isCancelled();
    }

    private boolean opened(Inventory inventory) {
        InventoryView view = mock(InventoryView.class);
        when(view.getPlayer()).thenReturn(player);
        when(view.getTopInventory()).thenReturn(inventory);
        InventoryOpenEvent event = new InventoryOpenEvent(view);
        guard.onEnderChest(event);
        return !event.isCancelled();
    }
}
