package com.uxplima.uxmskyblock.bukkit.loot;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Random;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import com.uxplima.uxmskyblock.bukkit.config.EffectsConfiguration;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffectPlayer;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffects;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** One filler for any container: items where they belong, the server's tables, and what the finder is told. */
class TheLootFillerFillsAnyContainerTest extends MockBukkitHarness {

    private final List<String> serverTables = new ArrayList<>();
    private final InteractionEffects effects = new InteractionEffects(
            Map.of("loot-rare", EffectsConfiguration.parseAll("loot-rare", List.of("[message] A rare find."))));
    private final LootFiller filler =
            new LootFiller(new InteractionEffectPlayer(), () -> effects, (key, random, at) -> {
                serverTables.add(key);
                return List.of(new ItemStack(Material.SADDLE));
            });

    private static Map<String, Rarity> rarities() {
        return Map.of("common", new Rarity("common", 1, ""), "rare", new Rarity("rare", 1, "loot-rare"));
    }

    @Test
    @DisplayName("An item goes in its slot when free, splits into stacks, and what finds no room is not put in")
    void itemsFindTheirPlace() {
        Inventory chest = server.createInventory(null, 9);
        LootTable table = new LootTable(
                "test",
                Span.of(1),
                Map.of("common", new Rarity("common", 1, "")),
                List.of(new LootEntry.Item(
                        "common", 1, new ItemStack(Material.ARROW), Span.of(100), OptionalInt.of(4))));

        filler.fill(chest, table, new Random(1), at(), null);

        assertThat(Objects.requireNonNull(chest.getItem(4)).getAmount()).isEqualTo(64);
        assertThat(count(chest, Material.ARROW)).isEqualTo(100);

        Inventory full = server.createInventory(null, 9);
        for (int slot = 0; slot < 9; slot++) {
            full.setItem(slot, new ItemStack(Material.DIRT));
        }
        filler.fill(full, table, new Random(1), at(), null);
        assertThat(count(full, Material.ARROW)).isZero();
    }

    @Test
    @DisplayName("A server table is rolled into the container, and lines and rarity effects reach the finder only")
    void serverTablesAndTheFinder() {
        PlayerMock finder = createPlayer("Finder");
        LootTable table = new LootTable(
                "test",
                Span.of(2),
                rarities(),
                List.of(
                        new LootEntry.Vanilla("common", 1, "minecraft:chests/igloo_chest"),
                        new LootEntry.Actions(
                                "rare",
                                1,
                                List.of("[message] You found it."),
                                EffectsConfiguration.parseAll("test", List.of("[message] You found it.")))));
        Inventory chest = server.createInventory(null, 27);

        List<Rarity> found = new ArrayList<>();
        Random random = new Random(5);
        while (found.stream().noneMatch(rarity -> rarity.name().equals("rare"))
                || found.stream().noneMatch(rarity -> rarity.name().equals("common"))) {
            found.addAll(filler.fill(chest, table, random, at(), finder));
        }

        assertThat(serverTables).contains("minecraft:chests/igloo_chest");
        assertThat(count(chest, Material.SADDLE)).isPositive();
        List<String> said = new ArrayList<>();
        for (String line = finder.nextMessage(); line != null; line = finder.nextMessage()) {
            said.add(line);
        }
        assertThat(said).contains("You found it.", "A rare find.");

        PlayerMock other = createPlayer("Other");
        Inventory unopened = server.createInventory(null, 27);
        for (int i = 0; i < 20; i++) {
            filler.fill(unopened, table, random, at(), null);
        }
        assertThat(other.nextMessage())
                .describedAs("nobody found it, so nobody is told")
                .isNull();
    }

    private Location at() {
        return new Location(server.addSimpleWorld("loot"), 0, 64, 0);
    }

    private static int count(Inventory inventory, Material type) {
        return Arrays.stream(inventory.getContents())
                .filter(item -> item != null && item.getType() == type)
                .mapToInt(ItemStack::getAmount)
                .sum();
    }
}
