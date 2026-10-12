package com.uxplima.uxmskyblock.bukkit.loot;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Random;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Each roll lands on a rarity by its weight, then on one of that rarity's entries by theirs. */
class ALootTableIsRolledByRarityThenEntryTest extends MockBukkitHarness {

    private static LootEntry.Item item(String rarity, int weight, Material material) {
        return new LootEntry.Item(rarity, weight, new ItemStack(material), Span.of(1), OptionalInt.empty());
    }

    @Test
    @DisplayName("A table rolls as often as its span says, and lands on rarities and entries in their proportions")
    void proportions() {
        LootTable table = new LootTable(
                "test",
                new Span(2, 4),
                Map.of(
                        "common", new Rarity("common", 3, ""),
                        "rare", new Rarity("rare", 1, "")),
                List.of(
                        item("common", 1, Material.STONE),
                        item("common", 3, Material.DIRT),
                        item("rare", 1, Material.DIAMOND)));
        Random random = new Random(7);
        Map<Material, Integer> landed = new HashMap<>();
        java.util.Set<Integer> counts = new java.util.HashSet<>();
        int rolls = 0;
        for (int i = 0; i < 4000; i++) {
            List<LootTable.Rolled> rolled = table.roll(random);
            counts.add(rolled.size());
            rolls += rolled.size();
            for (LootTable.Rolled one : rolled) {
                landed.merge(((LootEntry.Item) one.entry()).item().getType(), 1, Integer::sum);
            }
        }

        assertThat(counts)
                .describedAs("every count the span allows, and no other")
                .containsExactlyInAnyOrder(2, 3, 4);
        // A quarter rare, and three quarters common split one to three.
        assertThat(landed.getOrDefault(Material.DIAMOND, 0) / (double) rolls).isBetween(0.22, 0.28);
        assertThat(landed.getOrDefault(Material.DIRT, 0) / (double) rolls).isBetween(0.53, 0.60);
        assertThat(landed.getOrDefault(Material.STONE, 0) / (double) rolls).isBetween(0.16, 0.22);
    }

    @Test
    @DisplayName("A rarity of no weight, or with no entry that can be landed on, is never landed on")
    void whatCannotBeLandedOn() {
        LootTable table = new LootTable(
                "test",
                Span.of(50),
                Map.of(
                        "common", new Rarity("common", 1, ""),
                        "off", new Rarity("off", 0, ""),
                        "empty", new Rarity("empty", 100, "")),
                List.of(
                        item("common", 1, Material.STONE),
                        item("off", 5, Material.DIAMOND),
                        item("empty", 0, Material.EMERALD)));

        List<LootTable.Rolled> rolled = table.roll(new Random(3));

        assertThat(rolled).hasSize(50);
        assertThat(rolled).allSatisfy(one -> assertThat(one.rarity().name()).isEqualTo("common"));
        assertThat(new LootTable("none", Span.of(3), Map.of("common", new Rarity("common", 1, "")), List.of())
                        .roll(new Random(1)))
                .describedAs("a table with nothing to land on gives nothing")
                .isEmpty();
    }

    @Test
    @DisplayName("An item is made the first time it is asked for, once, and each roll is handed its own copy")
    void madeOnce() {
        int[] made = {0};
        LootEntry.Item entry = new LootEntry.Item(
                "common",
                1,
                () -> {
                    made[0]++;
                    return new ItemStack(Material.STONE);
                },
                Span.of(1),
                OptionalInt.empty());

        assertThat(made[0]).isZero();
        ItemStack first = entry.item();
        first.setAmount(9);
        assertThat(entry.item().getAmount()).isEqualTo(1);
        assertThat(made[0]).isEqualTo(1);
    }
}
