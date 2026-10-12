package com.uxplima.uxmskyblock.bukkit.loot;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import com.uxplima.uxmskyblock.bukkit.creative.InventoryCodec;
import com.uxplima.uxmskyblock.bukkit.creative.SealedInventory;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffectPlayer;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffects;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * What a table gives reaches only a player whose opening every other listener let through. Island protection
 * refuses a visitor at the same priority the fill once ran at, so which ran first was the order they were
 * registered in.
 */
class OnlyAnOpeningLetThroughFindsTheLootTest extends MockBukkitHarness {

    private final List<ItemStack[]> sealed = new ArrayList<>();
    private final InventoryCodec codec = new InventoryCodec() {
        @Override
        public byte[] write(ItemStack[] items) {
            sealed.add(items.clone());
            return ByteBuffer.allocate(4).putInt(sealed.size() - 1).array();
        }

        @Override
        public ItemStack[] read(byte[] bytes) {
            return sealed.get(ByteBuffer.wrap(bytes).getInt());
        }
    };

    private Plugin plugin;
    private Block block;
    private PlayerMock finder;

    @BeforeEach
    void setUp() {
        plugin = MockBukkit.createMockPlugin();
        LootContainers containers = new LootContainers(
                () -> LootTables.of(new LootTable(
                        "gems",
                        Span.of(1),
                        Map.of("common", new Rarity("common", 1, "")),
                        List.of(new LootEntry.Item(
                                "common", 1, new ItemStack(Material.EMERALD), Span.of(5), OptionalInt.empty())))),
                new LootFiller(
                        new InteractionEffectPlayer(), InteractionEffects::none, (key, random, at) -> List.of()));
        // Registered first, so at one priority it would run before the refusal.
        server.getPluginManager().registerEvents(containers, plugin);
        World world = server.addSimpleWorld("loot");
        block = world.getBlockAt(0, 64, 0);
        block.setType(Material.CHEST);
        LootContainers.give(block, "gems", 3L);
        finder = createPlayer("Finder");
    }

    @AfterEach
    void unseal() {
        SealedInventory.forget(finder);
    }

    @Test
    @DisplayName("A visitor island protection refuses finds nothing, and the container waits for someone let in")
    void aRefusedOpening() {
        server.getPluginManager().registerEvents(new Refusal(), plugin);

        open();

        assertThat(count()).isZero();
        assertThat(LootContainers.waitingFor(block)).isEqualTo("gems");
    }

    @Test
    @DisplayName("A spectator and a player sealed in a creative place leave it waiting, and the next player finds it")
    void aSpectatorAndASealedPlayer() {
        finder.setGameMode(GameMode.SPECTATOR);
        open();
        assertThat(LootContainers.waitingFor(block)).isEqualTo("gems");

        finder.setGameMode(GameMode.SURVIVAL);
        new SealedInventory(codec, "brix").seal(finder);
        open();
        assertThat(LootContainers.waitingFor(block)).isEqualTo("gems");
        assertThat(count()).isZero();

        SealedInventory.forget(finder);
        open();
        assertThat(count()).isEqualTo(5);
        assertThat(LootContainers.waitingFor(block)).isNull();
    }

    private void open() {
        server.getPluginManager().callEvent(new InventoryOpenEvent(finder.openInventory(inventory())));
    }

    private Inventory inventory() {
        return ((Chest) block.getState()).getInventory();
    }

    private int count() {
        return Arrays.stream(inventory().getContents())
                .filter(item -> item != null && item.getType() == Material.EMERALD)
                .mapToInt(ItemStack::getAmount)
                .sum();
    }

    /** Refuses every opening, as island protection refuses a visitor. */
    public static final class Refusal implements Listener {
        @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
        public void onOpen(InventoryOpenEvent event) {
            event.setCancelled(true);
        }
    }
}
