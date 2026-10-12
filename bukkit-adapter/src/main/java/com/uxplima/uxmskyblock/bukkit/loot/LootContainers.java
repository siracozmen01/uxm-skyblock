package com.uxplima.uxmskyblock.bukkit.loot;

import java.util.Objects;
import java.util.Random;
import java.util.function.Supplier;
import java.util.logging.Logger;

import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.TileState;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import org.jspecify.annotations.Nullable;

/**
 * A container given a loot table is filled the first time a player opens it, so the actions and the rarity
 * effects of what it holds reach the player who found it.
 *
 * <p>The table and the seed are written on the container, so a restart between the placing and the opening
 * changes nothing, and the mark is taken off before the fill, so a container is filled once.
 */
public final class LootContainers implements Listener {

    static final NamespacedKey TABLE = Objects.requireNonNull(NamespacedKey.fromString("uxmskyblock:loot_table"));
    static final NamespacedKey SEED = Objects.requireNonNull(NamespacedKey.fromString("uxmskyblock:loot_seed"));

    private static final Logger LOGGER = Logger.getLogger(LootContainers.class.getName());

    private final Supplier<LootTables> tables;
    private final LootFiller filler;

    public LootContainers(Supplier<LootTables> tables, LootFiller filler) {
        this.tables = Objects.requireNonNull(tables, "tables must not be null");
        this.filler = Objects.requireNonNull(filler, "filler must not be null");
    }

    /**
     * Gives the container at {@code block} the loot table {@code table}, rolled with {@code seed} when it is first
     * opened. Returns whether the block holds anything a table can fill.
     */
    public static boolean give(Block block, String table, long seed) {
        BlockState state = block.getState();
        if (!(state instanceof TileState tile) || !(state instanceof InventoryHolder)) {
            return false;
        }
        PersistentDataContainer data = tile.getPersistentDataContainer();
        data.set(TABLE, PersistentDataType.STRING, table);
        data.set(SEED, PersistentDataType.LONG, seed);
        tile.update(true, false);
        return true;
    }

    /** The loot table a container waits to be filled from, or nothing. */
    public static @Nullable String waitingFor(Block block) {
        return block.getState() instanceof TileState tile
                ? tile.getPersistentDataContainer().get(TABLE, PersistentDataType.STRING)
                : null;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        Inventory inventory = event.getInventory();
        InventoryHolder holder = inventory.getHolder();
        if (holder instanceof DoubleChest both) {
            fillFrom(both.getLeftSide(), inventory, player);
            fillFrom(both.getRightSide(), inventory, player);
        } else {
            fillFrom(holder, inventory, player);
        }
    }

    private void fillFrom(@Nullable InventoryHolder half, Inventory inventory, Player player) {
        if (!(half instanceof BlockState state) || !(state.getBlock().getState() instanceof TileState tile)) {
            return;
        }
        PersistentDataContainer data = tile.getPersistentDataContainer();
        String id = data.get(TABLE, PersistentDataType.STRING);
        if (id == null) {
            return;
        }
        LootTable table = tables.get().named(id).orElse(null);
        if (table == null) {
            // Left waiting: once the file is there, the next opening fills it.
            LOGGER.warning(() -> "A container at " + state.getLocation() + " waits for the loot table " + id
                    + ", which loot/ does not hold.");
            return;
        }
        long seed = data.getOrDefault(SEED, PersistentDataType.LONG, 0L);
        // Off before the fill, so nothing fills it twice.
        data.remove(TABLE);
        data.remove(SEED);
        tile.update(true, false);
        filler.fill(inventory, table, new Random(seed), state.getLocation(), player);
    }
}
