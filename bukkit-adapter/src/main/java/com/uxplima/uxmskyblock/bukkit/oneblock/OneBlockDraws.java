package com.uxplima.uxmskyblock.bukkit.oneblock;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

import com.uxplima.uxmskyblock.bukkit.config.OneBlockEntries;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.loot.LootContainers;
import com.uxplima.uxmskyblock.bukkit.loot.LootTables;
import com.uxplima.uxmskyblock.bukkit.loot.Rarity;
import org.jspecify.annotations.Nullable;

/**
 * Puts what a phase drew into the world: a block by its name or its states, a spawner of a creature, and a creature
 * with its name, size and equipment. Everything runs on the thread that owns the block.
 */
public final class OneBlockDraws {

    private static final Map<String, EquipmentSlot> SLOTS = Map.of(
            "head", EquipmentSlot.HEAD,
            "chest", EquipmentSlot.CHEST,
            "legs", EquipmentSlot.LEGS,
            "feet", EquipmentSlot.FEET,
            "hand", EquipmentSlot.HAND,
            "off-hand", EquipmentSlot.OFF_HAND);

    /** Plain names only, as before entries could be written. */
    public static final OneBlockDraws PLAIN = new OneBlockDraws(() -> OneBlockEntries.NONE, null);

    private static final java.util.logging.Logger LOGGER =
            java.util.logging.Logger.getLogger(OneBlockDraws.class.getName());

    private final Supplier<OneBlockEntries> entries;
    private final @Nullable Messages messages;
    private final Supplier<LootTables> tables;

    /** @param messages reads a creature's {@code @key} name in the breaker's language, or none for plain names only */
    public OneBlockDraws(Supplier<OneBlockEntries> entries, @Nullable Messages messages) {
        this(entries, messages, LootTables::none);
    }

    /** @param tables the loot tables of loot/, which a drawn entry's rarity is read from */
    public OneBlockDraws(Supplier<OneBlockEntries> entries, @Nullable Messages messages, Supplier<LootTables> tables) {
        this.entries = Objects.requireNonNull(entries, "entries must not be null");
        this.messages = messages;
        this.tables = Objects.requireNonNull(tables, "tables must not be null");
    }

    /**
     * Gives the container laid at x, y, z as {@code drawn} in {@code phase} the loot table it is drawn with, filled
     * when a player first opens it. A block that holds nothing is given nothing.
     *
     * @return the entry of modules/effects.conf the drawn entry's rarity fires for the breaker, or none
     */
    public @Nullable String fill(World world, int x, int y, int z, String drawn, String phase) {
        String table = entries.get().tableFor(drawn, phase);
        if (table == null
                || !LootContainers.give(
                        world.getBlockAt(x, y, z),
                        table,
                        java.util.concurrent.ThreadLocalRandom.current().nextLong())) {
            return null;
        }
        OneBlockEntries.Block entry = entries.get().blocks().get(drawn);
        String rarity = entry == null ? null : entry.rarity();
        if (rarity == null || rarity.isBlank()) {
            return null;
        }
        Rarity found = tables.get()
                .named(table)
                .map(read -> read.rarities().get(rarity))
                .orElse(null);
        if (found == null) {
            LOGGER.warning(() -> "OneBlock draws '" + drawn + "' with the rarity '" + rarity
                    + "', which the loot table '" + table + "' does not name, so nothing fires.");
            return null;
        }
        return found.effects().isBlank() ? null : found.effects();
    }

    /** Sets the block at x, y, z to what was drawn: an entry, a block with its states, or a plain name. */
    public void lay(World world, int x, int y, int z, String drawn) {
        Block block = world.getBlockAt(x, y, z);
        OneBlockEntries.Block entry = entries.get().blocks().get(drawn);
        String data = entry == null ? drawn : entry.data();
        if (data.indexOf('[') >= 0) {
            try {
                block.setBlockData(Bukkit.createBlockData(data.toLowerCase(Locale.ROOT)), false);
            } catch (IllegalArgumentException unknown) {
                block.setType(OneBlockStart.blockOf(data.substring(0, data.indexOf('['))));
            }
        } else {
            block.setType(OneBlockStart.blockOf(data));
        }
        String spawns = entry == null ? null : entry.spawns();
        if (spawns != null && block.getState() instanceof CreatureSpawner spawner) {
            EntityType type = typeOf(spawns);
            if (type != null) {
                spawner.setSpawnedType(type);
                spawner.update(true, false);
            }
        }
    }

    /** What was drawn, as an operator reads it: the block's name. */
    public String named(String drawn) {
        OneBlockEntries.Block entry = entries.get().blocks().get(drawn);
        String data = entry == null ? drawn : entry.data();
        int states = data.indexOf('[');
        return OneBlockStart.blockOf(states < 0 ? data : data.substring(0, states))
                .name()
                .toLowerCase(Locale.ROOT);
    }

    /**
     * Brings the creature drawn onto the block at {@code at}: an entry with its name, size and equipment, or a plain
     * creature. A name written as {@code @key} is read in the language of {@code breaker}.
     */
    public @Nullable Entity spawn(Location at, String drawn, @Nullable Player breaker) {
        World world = Objects.requireNonNull(at.getWorld(), "at must be in a world");
        OneBlockEntries.Creature entry = entries.get().creatures().get(drawn);
        EntityType type = typeOf(entry == null ? drawn : entry.type());
        if (type == null || !type.isSpawnable()) {
            return null;
        }
        Entity spawned = world.spawnEntity(at, type);
        if (entry == null || !(spawned instanceof LivingEntity living)) {
            return spawned;
        }
        String name = entry.name();
        if (name != null) {
            living.customName(nameOf(name, breaker));
            living.setCustomNameVisible(true);
        }
        AttributeInstance scale = living.getAttribute(Attribute.SCALE);
        if (scale != null && entry.scale() != 1) {
            scale.setBaseValue(entry.scale());
        }
        EntityEquipment equipment = living.getEquipment();
        if (equipment != null) {
            entry.equipment().forEach((slot, item) -> {
                EquipmentSlot to = SLOTS.get(slot);
                Material material = Material.matchMaterial(item);
                if (to != null && material != null) {
                    equipment.setItem(to, new ItemStack(material));
                    // What it wears is the operator's to show, never a drop to farm.
                    equipment.setDropChance(to, 0f);
                }
            });
        }
        return spawned;
    }

    private Component nameOf(String written, @Nullable Player breaker) {
        Messages catalogue = messages;
        if (written.startsWith("@") && catalogue != null) {
            Audience reader = breaker == null ? Audience.empty() : breaker;
            return catalogue.renderPlain(reader, written.substring(1));
        }
        return MiniMessage.miniMessage().deserialize(written);
    }

    /** Whether the creature drawn is one that attacks, so its island is told it comes. */
    public boolean hostile(String drawn) {
        EntityType type = typeOf(creatureType(drawn));
        Class<? extends Entity> made = type == null ? null : type.getEntityClass();
        return made != null && org.bukkit.entity.Enemy.class.isAssignableFrom(made);
    }

    /**
     * The creature drawn as a player reads it: its entry's name in {@code reader}'s language, or its kind, which the
     * player's own game reads in their language.
     */
    public Component creatureName(String drawn, @Nullable Player reader) {
        OneBlockEntries.Creature entry = entries.get().creatures().get(drawn);
        String name = entry == null ? null : entry.name();
        if (name != null) {
            return nameOf(name, reader);
        }
        EntityType type = typeOf(creatureType(drawn));
        return type == null ? Component.text(plainKind(drawn)) : Component.translatable(type.translationKey());
    }

    /** The creature drawn as an operator's line reads it: its entry's plain name, or its kind, as {@code cave spider}. */
    public String plainCreatureName(String drawn) {
        OneBlockEntries.Creature entry = entries.get().creatures().get(drawn);
        String name = entry == null ? null : entry.name();
        return name != null && !name.startsWith("@")
                ? net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                        .serialize(MiniMessage.miniMessage().deserialize(name))
                : plainKind(drawn);
    }

    private String plainKind(String drawn) {
        String type = creatureType(drawn);
        return type.substring(type.indexOf(':') + 1).replace('_', ' ').toLowerCase(Locale.ROOT);
    }

    private String creatureType(String drawn) {
        OneBlockEntries.Creature entry = entries.get().creatures().get(drawn);
        return entry == null ? drawn : entry.type();
    }

    private static @Nullable EntityType typeOf(String name) {
        NamespacedKey key = NamespacedKey.fromString(name.toLowerCase(Locale.ROOT));
        return key == null ? null : Registry.ENTITY_TYPE.get(key);
    }
}
