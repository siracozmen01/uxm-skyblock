package com.uxplima.uxmskyblock.bukkit.loot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

/**
 * A loot table: how many rolls it makes, the rarities a roll lands on by weight, and the entries of each rarity,
 * one of which a roll lands on by weight.
 */
public record LootTable(String id, Span rolls, Map<String, Rarity> rarities, List<LootEntry> entries) {

    /** One thing a roll landed on, and the rarity it landed on first. */
    public record Rolled(LootEntry entry, Rarity rarity) {}

    public LootTable {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(rolls, "rolls");
        rarities = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(rarities));
        entries = List.copyOf(entries);
        for (LootEntry entry : entries) {
            if (!rarities.containsKey(entry.rarity())) {
                throw new IllegalArgumentException(
                        "an entry of the loot table " + id + " is " + entry.rarity() + ", a rarity it does not name");
            }
        }
    }

    /**
     * Rolls the table: each roll lands on a rarity by weight, among those with an entry that can be landed on, then
     * on one of that rarity's entries by weight.
     */
    public List<Rolled> roll(Random random) {
        List<Rolled> rolled = new ArrayList<>();
        int times = rolls.pick(random);
        for (int i = 0; i < times; i++) {
            List<Rarity> open = new ArrayList<>();
            for (Rarity rarity : rarities.values()) {
                if (rarity.weight() > 0 && weightOf(rarity.name()) > 0) {
                    open.add(rarity);
                }
            }
            Rarity rarity = pick(open, Rarity::weight, random);
            if (rarity == null) {
                break;
            }
            List<LootEntry> ofRarity = entries.stream()
                    .filter(entry -> entry.rarity().equals(rarity.name()))
                    .toList();
            LootEntry entry = pick(ofRarity, LootEntry::weight, random);
            if (entry != null) {
                rolled.add(new Rolled(entry, rarity));
            }
        }
        return rolled;
    }

    private int weightOf(String rarity) {
        int total = 0;
        for (LootEntry entry : entries) {
            if (entry.rarity().equals(rarity)) {
                total += Math.max(0, entry.weight());
            }
        }
        return total;
    }

    private static <T> @org.jspecify.annotations.Nullable T pick(
            List<T> among, java.util.function.ToIntFunction<T> weight, Random random) {
        int total = 0;
        for (T one : among) {
            total += Math.max(0, weight.applyAsInt(one));
        }
        if (total <= 0) {
            return null;
        }
        int at = random.nextInt(total);
        for (T one : among) {
            at -= Math.max(0, weight.applyAsInt(one));
            if (at < 0) {
                return one;
            }
        }
        return null;
    }
}
