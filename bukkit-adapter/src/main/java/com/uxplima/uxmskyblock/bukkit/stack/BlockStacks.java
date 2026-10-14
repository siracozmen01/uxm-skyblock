package com.uxplima.uxmskyblock.bukkit.stack;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import org.bukkit.Chunk;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/**
 * How many blocks each stacked block stands for, kept in its chunk's own data, so a stack is saved and loaded with the
 * world it stands in and needs no table of its own.
 *
 * <p>A block with no entry stands for one. An entry is written under the block's place inside its chunk.
 */
public final class BlockStacks {

    private static final String NAMESPACE = "uxmskyblock";
    private static final String PREFIX = "stack_";

    private BlockStacks() {}

    /** Where a stacked block stands in its world. */
    public record Place(int x, int y, int z) {}

    /** How many blocks {@code block} stands for, one when it is no stack. */
    public static int size(Block block) {
        Objects.requireNonNull(block, "block must not be null");
        Integer written = block.getChunk().getPersistentDataContainer().get(keyOf(block), PersistentDataType.INTEGER);
        return written == null || written < 1 ? 1 : written;
    }

    /** Has {@code block} stand for {@code size} blocks; one or fewer takes the entry away. */
    public static void set(Block block, int size) {
        Objects.requireNonNull(block, "block must not be null");
        PersistentDataContainer data = block.getChunk().getPersistentDataContainer();
        if (size <= 1) {
            data.remove(keyOf(block));
        } else {
            data.set(keyOf(block), PersistentDataType.INTEGER, size);
        }
    }

    /** Every stack in {@code chunk}, by its block's place in the world, each with how many blocks it stands for. */
    public static Map<Place, Integer> in(Chunk chunk) {
        Objects.requireNonNull(chunk, "chunk must not be null");
        PersistentDataContainer data = chunk.getPersistentDataContainer();
        Map<Place, Integer> stacks = new HashMap<>();
        for (NamespacedKey key : data.getKeys()) {
            if (!NAMESPACE.equals(key.getNamespace()) || !key.getKey().startsWith(PREFIX)) {
                continue;
            }
            String[] at = key.getKey().substring(PREFIX.length()).split("_", -1);
            Integer size = data.get(key, PersistentDataType.INTEGER);
            if (at.length != 3 || size == null || size < 2) {
                continue;
            }
            try {
                int x = (chunk.getX() << 4) + Integer.parseInt(at[0]);
                int y = Integer.parseInt(at[1]);
                int z = (chunk.getZ() << 4) + Integer.parseInt(at[2]);
                stacks.put(new Place(x, y, z), size);
            } catch (NumberFormatException unreadable) {
                // A key of ours that is no place is no stack.
            }
        }
        return stacks;
    }

    static NamespacedKey keyOf(Block block) {
        return new NamespacedKey(
                NAMESPACE, PREFIX + (block.getX() & 15) + "_" + block.getY() + "_" + (block.getZ() & 15));
    }
}
