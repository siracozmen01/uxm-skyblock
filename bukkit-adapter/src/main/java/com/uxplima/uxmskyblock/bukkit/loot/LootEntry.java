package com.uxplima.uxmskyblock.bukkit.loot;

import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.bukkit.inventory.ItemStack;

import com.uxplima.uxmlib.condition.action.ActionList;
import org.jspecify.annotations.Nullable;

/** One thing a roll can land on: an item, a list of action lines, or one of the server's own loot tables. */
public sealed interface LootEntry {

    /** The rarity it belongs to. */
    String rarity();

    /** How often a roll lands on it against the other entries of its rarity. */
    int weight();

    /**
     * An item, as many as the span says, in the slot named or wherever there is room. It is made the first time a
     * roll lands on it, so a table is read before the server or the plugin that owns the item is up.
     */
    record Item(String rarity, int weight, Supplier<ItemStack> made, Span amount, OptionalInt slot)
            implements LootEntry {
        public Item {
            Objects.requireNonNull(rarity, "rarity");
            Objects.requireNonNull(made, "made");
            Objects.requireNonNull(amount, "amount");
            Objects.requireNonNull(slot, "slot");
            made = memoized(made);
        }

        public Item(String rarity, int weight, ItemStack item, Span amount, OptionalInt slot) {
            this(rarity, weight, constant(item), amount, slot);
        }

        /** A copy of the item, made now if no roll has landed on it before. */
        public ItemStack item() {
            return made.get().clone();
        }

        private static Supplier<ItemStack> constant(ItemStack item) {
            ItemStack kept = Objects.requireNonNull(item, "item").clone();
            return () -> kept;
        }

        private static Supplier<ItemStack> memoized(Supplier<ItemStack> made) {
            AtomicReference<@Nullable ItemStack> once = new AtomicReference<>();
            return () -> {
                ItemStack item = once.get();
                if (item == null) {
                    item = Objects.requireNonNull(made.get(), "the item made");
                    once.compareAndSet(null, item);
                    item = Objects.requireNonNull(once.get());
                }
                return item;
            };
        }
    }

    /** Action lines run for the player who finds it; nothing runs when nobody does. */
    record Actions(String rarity, int weight, List<String> lines, ActionList actions) implements LootEntry {
        public Actions {
            Objects.requireNonNull(rarity, "rarity");
            Objects.requireNonNull(actions, "actions");
            lines = List.copyOf(lines);
        }
    }

    /** One of the server's own loot tables, by its key, the game's or a datapack's. */
    record Vanilla(String rarity, int weight, String key) implements LootEntry {
        public Vanilla {
            Objects.requireNonNull(rarity, "rarity");
            Objects.requireNonNull(key, "key");
        }
    }
}
