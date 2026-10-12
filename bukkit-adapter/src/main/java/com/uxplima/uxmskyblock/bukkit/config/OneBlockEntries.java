package com.uxplima.uxmskyblock.bukkit.config;

import java.util.Map;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * The OneBlock pool entries written as more than a name: a block with its states or a spawner, and a creature with a
 * name, a size and what it wears. Each is keyed by the name its phase's pool draws it under, {@code <phase>/<entry>},
 * so two phases may each have an entry of one name. A plain name in a pool needs no entry here.
 *
 * @param blocks the block entries, by the name drawn
 * @param creatures the creature entries, by the name drawn
 */
public record OneBlockEntries(Map<String, Block> blocks, Map<String, Creature> creatures) {

    /** No entry but plain names. */
    public static final OneBlockEntries NONE = new OneBlockEntries(Map.of(), Map.of());

    public OneBlockEntries {
        blocks = Map.copyOf(blocks);
        creatures = Map.copyOf(creatures);
    }

    /**
     * A block written as an entry.
     *
     * @param data the block and its states, as {@code oak_stairs[facing=north]}
     * @param spawns the creature a spawner placed here spawns, or none
     */
    public record Block(String data, @Nullable String spawns) {

        public Block {
            Objects.requireNonNull(data, "data");
        }
    }

    /**
     * A creature written as an entry.
     *
     * @param type the creature, as the server names it
     * @param name what it is called over its head, MiniMessage or a {@code @key} of the language files, or none
     * @param scale how large it is against its usual size, from 0.0625 to 16
     * @param equipment what it wears and holds, an item name by slot: head, chest, legs, feet, hand and off-hand
     */
    public record Creature(String type, @Nullable String name, double scale, Map<String, String> equipment) {

        public Creature {
            Objects.requireNonNull(type, "type");
            equipment = Map.copyOf(equipment);
            if (scale < 0.0625 || scale > 16) {
                throw new IllegalArgumentException("a creature's scale is from 0.0625 to 16, not " + scale);
            }
        }
    }
}
