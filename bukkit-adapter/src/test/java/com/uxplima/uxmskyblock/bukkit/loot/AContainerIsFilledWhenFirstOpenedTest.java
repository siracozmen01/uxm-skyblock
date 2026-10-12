package com.uxplima.uxmskyblock.bukkit.loot;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicReference;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffectPlayer;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffects;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** A container given a loot table is filled the first time a player opens it, and once only. */
class AContainerIsFilledWhenFirstOpenedTest extends MockBukkitHarness {

    private final AtomicReference<LootTables> tables = new AtomicReference<>(LootTables.none());
    private final LootContainers containers = new LootContainers(
            tables::get,
            new LootFiller(new InteractionEffectPlayer(), InteractionEffects::none, (key, random, at) -> List.of()));

    @Test
    @DisplayName("Opened once it is filled from its table, opened again nothing more, and a table not there waits")
    void filledOnce() throws Exception {
        World world = server.addSimpleWorld("loot");
        PlayerMock finder = createPlayer("Finder");
        Block block = world.getBlockAt(0, 64, 0);
        block.setType(Material.CHEST);
        assertThat(LootContainers.give(block, "gems", 42L)).isTrue();
        assertThat(LootContainers.give(world.getBlockAt(1, 64, 0), "gems", 1L))
                .describedAs("air holds nothing a table can fill")
                .isFalse();

        open(finder, block);
        assertThat(LootContainers.waitingFor(block))
                .describedAs("left waiting while loot/ has no such table")
                .isEqualTo("gems");
        assertThat(count(inventory(block))).isZero();

        tables.set(LootTables.of(new LootTable(
                "gems",
                Span.of(1),
                Map.of("common", new Rarity("common", 1, "")),
                List.of(new LootEntry.Item(
                        "common", 1, new ItemStack(Material.EMERALD), Span.of(5), OptionalInt.empty())))));
        open(finder, block);
        assertThat(count(inventory(block))).isEqualTo(5);
        assertThat(LootContainers.waitingFor(block)).isNull();

        open(finder, block);
        assertThat(count(inventory(block))).describedAs("filled once").isEqualTo(5);
    }

    private void open(PlayerMock player, Block block) {
        containers.onOpen(new InventoryOpenEvent(player.openInventory(inventory(block))));
    }

    private static Inventory inventory(Block block) {
        return ((Chest) block.getState()).getInventory();
    }

    private static int count(Inventory inventory) {
        return Arrays.stream(inventory.getContents())
                .filter(item -> item != null && item.getType() == Material.EMERALD)
                .mapToInt(ItemStack::getAmount)
                .sum();
    }
}
