package com.uxplima.uxmskyblock.bukkit.loot;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.function.Supplier;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.loot.LootContext;

import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffectPlayer;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffects;
import org.jspecify.annotations.Nullable;

/**
 * Fills any container from a loot table: the one filler every mode's chests, barrels and moments go through.
 *
 * <p>An item goes in the slot its entry names when that slot is free, else in a free slot the roll picks, split
 * into stacks as large as the item stacks; what finds no room is not put in. Action lines and each rarity's
 * effects run for the player who found the loot, and for nobody when nobody did.
 */
public final class LootFiller {

    private static final Logger LOGGER = Logger.getLogger(LootFiller.class.getName());

    /** The server's own loot tables, by key: the game's and any a datapack adds. */
    public interface Vanilla {
        Collection<ItemStack> roll(String key, Random random, Location at);
    }

    /** The server's own tables, as the server rolls them. */
    public static final Vanilla SERVER = (key, random, at) -> {
        NamespacedKey name = NamespacedKey.fromString(key);
        org.bukkit.loot.LootTable table = name == null ? null : Bukkit.getLootTable(name);
        if (table == null) {
            LOGGER.warning(() -> "A loot table names the server table '" + key + "', which the server has not.");
            return List.of();
        }
        return table.populateLoot(random, new LootContext.Builder(at).build());
    };

    private final InteractionEffectPlayer effectPlayer;
    private final Supplier<InteractionEffects> effects;
    private final Vanilla vanilla;

    public LootFiller(InteractionEffectPlayer effectPlayer, Supplier<InteractionEffects> effects, Vanilla vanilla) {
        this.effectPlayer = Objects.requireNonNull(effectPlayer, "effectPlayer must not be null");
        this.effects = Objects.requireNonNull(effects, "effects must not be null");
        this.vanilla = Objects.requireNonNull(vanilla, "vanilla must not be null");
    }

    /** Rolls {@code table} into {@code inventory}, at {@code at}, for {@code finder}. Returns the rarities found. */
    public List<Rarity> fill(
            Inventory inventory, LootTable table, Random random, Location at, @Nullable Player finder) {
        Set<Rarity> found = new LinkedHashSet<>();
        for (LootTable.Rolled rolled : table.roll(random)) {
            found.add(rolled.rarity());
            switch (rolled.entry()) {
                case LootEntry.Item item -> putItem(inventory, item, random);
                case LootEntry.Vanilla server -> {
                    for (ItemStack stack : vanilla.roll(server.key(), random, at)) {
                        put(inventory, stack, -1, random);
                    }
                }
                case LootEntry.Actions actions -> {
                    if (finder != null) {
                        String name = "loot " + table.id();
                        effectPlayer.fire(new InteractionEffects(Map.of(name, actions.actions())), name, finder);
                    }
                }
            }
        }
        if (finder != null) {
            for (Rarity rarity : found) {
                if (!rarity.effects().isBlank()) {
                    effectPlayer.fire(effects.get(), rarity.effects(), finder);
                }
            }
        }
        return new ArrayList<>(found);
    }

    private static void putItem(Inventory inventory, LootEntry.Item entry, Random random) {
        int left = entry.amount().pick(random);
        ItemStack template = entry.item();
        int most = Math.max(1, template.getMaxStackSize());
        int slot = entry.slot().orElse(-1);
        while (left > 0) {
            ItemStack stack = template.clone();
            stack.setAmount(Math.min(left, most));
            left -= stack.getAmount();
            put(inventory, stack, slot, random);
            // Only the first stack goes in the slot named; the rest find room of their own.
            slot = -1;
        }
    }

    /** Puts {@code stack} in {@code slot} when it is in the inventory and free, else in a free slot by chance. */
    private static void put(Inventory inventory, ItemStack stack, int slot, Random random) {
        if (slot >= 0 && slot < inventory.getSize() && empty(inventory.getItem(slot))) {
            inventory.setItem(slot, stack);
            return;
        }
        List<Integer> free = new ArrayList<>();
        for (int i = 0; i < inventory.getSize(); i++) {
            if (empty(inventory.getItem(i))) {
                free.add(i);
            }
        }
        if (free.isEmpty()) {
            LOGGER.fine(() -> "A loot roll found no room for " + stack.getType() + " and it was not put in.");
            return;
        }
        inventory.setItem(free.get(random.nextInt(free.size())), stack);
    }

    private static boolean empty(@Nullable ItemStack item) {
        return item == null || item.getType().isAir();
    }
}
