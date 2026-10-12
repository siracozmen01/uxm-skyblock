package com.uxplima.uxmskyblock.bukkit.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.entity.EntityType;

import com.uxplima.uxmskyblock.core.domain.oneblock.OneBlockPhase;
import com.uxplima.uxmskyblock.core.domain.oneblock.OneBlockPhases;
import com.uxplima.uxmskyblock.core.domain.oneblock.WeightedPool;
import org.spongepowered.configurate.ConfigurationNode;

/**
 * The OneBlock game mode as the operator wrote it in {@code modules/oneblock.conf} and its phase files.
 *
 * <p>{@code modules/oneblock.conf} lists the phases by name, in order, and each is a file of its own,
 * {@code oneblock/phases/<name>.conf}. Every phase, its icon, its length, what the block turns into and what may
 * appear on it are the file's. A phase still written inside the list, as the files before them were, is read as it
 * stands.
 * A name the server does not know as a block or a creature is left out with a warning, so a typo
 * costs one entry and not the mode; a phase left with no block at all is left out the same way.
 *
 * @param enabled whether islands can be made as OneBlock islands at all
 * @param phases the phases, in order
 * @param saveInterval how often the breaks counted in memory are added to the stored count
 * @param icons each phase's icon, a block or item name, by the phase's name
 * @param entries the pool entries written as more than a name
 */
public record OneBlockConfiguration(
        boolean enabled,
        OneBlockPhases phases,
        java.time.Duration saveInterval,
        Map<String, String> icons,
        OneBlockEntries entries) {

    /** The icon of a phase whose file names none. */
    public static final String DEFAULT_ICON = "GRASS_BLOCK";

    /** The slots a creature's equipment is written by. */
    public static final List<String> SLOTS = List.of("head", "chest", "legs", "feet", "hand", "off-hand");

    /** The name a phase file may have: what the language files key its name by. */
    static final java.util.regex.Pattern NAME = java.util.regex.Pattern.compile("[a-z0-9_-]{1,64}");

    public OneBlockConfiguration(boolean enabled, OneBlockPhases phases, java.time.Duration saveInterval) {
        this(enabled, phases, saveInterval, Map.of(), OneBlockEntries.NONE);
    }

    /** How often counted breaks are written, when the file names no interval. */
    public static final java.time.Duration DEFAULT_SAVE_INTERVAL = java.time.Duration.ofSeconds(30);

    private static final Logger LOGGER = Logger.getLogger(OneBlockConfiguration.class.getName());

    public OneBlockConfiguration {
        Objects.requireNonNull(phases, "phases");
        Objects.requireNonNull(saveInterval, "saveInterval");
        if (saveInterval.isNegative() || saveInterval.isZero()) {
            throw new IllegalArgumentException("The save interval must be positive: " + saveInterval);
        }
        icons = Map.copyOf(icons);
        Objects.requireNonNull(entries, "entries");
    }

    /** The icon of the phase called {@code key}. */
    public String iconOf(String key) {
        return icons.getOrDefault(key, DEFAULT_ICON);
    }

    /** One phase of plain blocks, for a server whose file holds nothing usable. */
    public static OneBlockConfiguration defaultConfiguration() {
        Map<String, Double> blocks = new LinkedHashMap<>();
        blocks.put(Material.GRASS_BLOCK.name(), 5.0);
        blocks.put(Material.DIRT.name(), 3.0);
        blocks.put(Material.OAK_LOG.name(), 2.0);
        return new OneBlockConfiguration(
                true,
                new OneBlockPhases(
                        List.of(new OneBlockPhase("plains", 500, new WeightedPool(blocks), WeightedPool.empty(), 0)),
                        OneBlockPhases.AfterTheLast.STAY),
                DEFAULT_SAVE_INTERVAL);
    }

    /** The file with its phases written inside the list, and none read from files. */
    public static OneBlockConfiguration load(ConfigurationNode root) {
        return load(root, null);
    }

    /**
     * The file, each phase it names read from {@code phasesFolder}, {@code <name>.conf}. A name with no file, or a
     * file that cannot be read, leaves that phase out with the reason.
     */
    public static OneBlockConfiguration load(
            ConfigurationNode root, java.nio.file.@org.jspecify.annotations.Nullable Path phasesFolder) {
        Objects.requireNonNull(root, "root");
        if (root.virtual() || root.empty()) {
            return defaultConfiguration();
        }
        boolean enabled = root.node("enabled").getBoolean(true);
        OneBlockPhases.AfterTheLast after =
                afterTheLast(root.node("after-last-phase").getString("stay"));
        java.time.Duration saveInterval =
                saveInterval(root.node("save-interval").getString());
        List<OneBlockPhase> phases = new ArrayList<>();
        Map<String, String> icons = new LinkedHashMap<>();
        Map<String, OneBlockEntries.Block> blockEntries = new LinkedHashMap<>();
        Map<String, OneBlockEntries.Creature> creatureEntries = new LinkedHashMap<>();
        // A list, because the phases are gone through in order and a HOCON object keeps none.
        for (ConfigurationNode listed : root.node("phases").childrenList()) {
            ConfigurationNode phase = listed.isMap() ? listed : phaseFile(listed.getString(""), phasesFolder);
            if (phase == null) {
                continue;
            }
            String key = listed.isMap() ? phase.node("name").getString("") : listed.getString("");
            icons.put(key, icon(key, phase.node("icon").getString(DEFAULT_ICON)));
            Map<String, Double> blocks = blocks(key, phase.node("blocks"), blockEntries);
            if (blocks.isEmpty()) {
                LOGGER.warning(() -> "OneBlock phase '" + key + "' has no block the server knows and is left out.");
                continue;
            }
            try {
                phases.add(new OneBlockPhase(
                        key,
                        phase.node("length").getLong(0),
                        new WeightedPool(blocks),
                        new WeightedPool(creatures(key, phase.node("creatures"), creatureEntries)),
                        phase.node("creature-chance").getDouble(0)));
            } catch (IllegalArgumentException unusable) {
                LOGGER.warning(() -> "OneBlock phase '" + key + "' is left out: " + unusable.getMessage());
            }
        }
        if (phases.isEmpty()) {
            LOGGER.warning("modules/oneblock.conf holds no usable phase, so the built in one is used.");
            return new OneBlockConfiguration(enabled, defaultConfiguration().phases(), saveInterval);
        }
        return new OneBlockConfiguration(
                enabled,
                new OneBlockPhases(phases, after),
                saveInterval,
                icons,
                new OneBlockEntries(blockEntries, creatureEntries));
    }

    /** The phase file of {@code name}, or nothing, said in the console, when there is none to read. */
    private static @org.jspecify.annotations.Nullable ConfigurationNode phaseFile(
            String name, java.nio.file.@org.jspecify.annotations.Nullable Path folder) {
        if (!NAME.matcher(name).matches()) {
            LOGGER.warning(() -> "modules/oneblock.conf lists the phase '" + name
                    + "', which is no name a file can have: 1 to 64 of a-z, 0-9, _ and -. It is left out.");
            return null;
        }
        java.nio.file.Path file = folder == null ? null : folder.resolve(name + ".conf");
        if (file == null || !java.nio.file.Files.isRegularFile(file)) {
            LOGGER.warning(() -> "modules/oneblock.conf lists the phase '" + name + "', and oneblock/phases/" + name
                    + ".conf is not there. It is left out.");
            return null;
        }
        try {
            return org.spongepowered.configurate.hocon.HoconConfigurationLoader.builder()
                    .path(file)
                    .build()
                    .load();
        } catch (org.spongepowered.configurate.ConfigurateException unreadable) {
            LOGGER.warning(() -> "oneblock/phases/" + name + ".conf cannot be read (" + unreadable.getMessage()
                    + "). The phase is left out.");
            return null;
        }
    }

    /** The icon written, or the default when it is no name the server knows or names air. */
    private static String icon(String phase, String written) {
        Material material = Material.matchMaterial(written);
        if (material == null || material.isAir()) {
            LOGGER.warning(() -> "OneBlock phase '" + phase + "' has the icon '" + written
                    + "', which the server does not know, so " + DEFAULT_ICON + " is used.");
            return DEFAULT_ICON;
        }
        return material.name();
    }

    private static java.time.Duration saveInterval(@org.jspecify.annotations.Nullable String written) {
        if (written == null || written.isBlank()) {
            return DEFAULT_SAVE_INTERVAL;
        }
        try {
            java.time.Duration parsed = com.uxplima.uxmlib.common.Durations.parse(written.strip());
            if (!parsed.isNegative() && !parsed.isZero()) {
                return parsed;
            }
        } catch (RuntimeException unreadable) {
            // Said below, with the interval used instead.
        }
        LOGGER.warning(() -> "save-interval '" + written + "' is not a positive duration, so "
                + DEFAULT_SAVE_INTERVAL.toSeconds() + "s is used.");
        return DEFAULT_SAVE_INTERVAL;
    }

    private static OneBlockPhases.AfterTheLast afterTheLast(String written) {
        try {
            return OneBlockPhases.AfterTheLast.valueOf(written.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            LOGGER.warning(
                    () -> "after-last-phase '" + written + "' is neither 'stay' nor 'repeat', so 'stay' is used.");
            return OneBlockPhases.AfterTheLast.STAY;
        }
    }

    /**
     * The block weights under {@code node}. A name is the server's own, or a block with its states as
     * {@code oak_stairs[facing=north]}; an entry written as an object is put in {@code entries} under the name it is
     * drawn by, {@code <phase>/<entry>}. Only what the server knows is kept.
     */
    private static Map<String, Double> blocks(
            String phase, ConfigurationNode node, Map<String, OneBlockEntries.Block> entries) {
        Map<String, Double> weights = new LinkedHashMap<>();
        for (Map.Entry<Object, ? extends ConfigurationNode> entry :
                node.childrenMap().entrySet()) {
            String written = String.valueOf(entry.getKey());
            ConfigurationNode value = entry.getValue();
            if (value.isMap()) {
                String data = value.node("block").getString(written);
                String spawns = value.node("spawns").getString();
                String known = blockData(data);
                if (known == null || (spawns != null && creatureName(spawns) == null)) {
                    LOGGER.warning(() -> "OneBlock phase '" + phase + "' has the entry '" + written + "', whose block '"
                            + data + "'" + (spawns == null ? "" : " or creature '" + spawns + "'")
                            + " the server does not know, and it is left out.");
                    continue;
                }
                String drawn = phase + "/" + written;
                entries.put(drawn, new OneBlockEntries.Block(known, spawns == null ? null : creatureName(spawns)));
                weights.merge(drawn, value.node("weight").getDouble(1), Double::sum);
                continue;
            }
            String known = blockData(written);
            if (known == null) {
                LOGGER.warning(() -> "OneBlock phase '" + phase + "' names '" + written
                        + "', which is not a block, and it is left out.");
                continue;
            }
            weights.merge(known, value.getDouble(0), Double::sum);
        }
        return weights;
    }

    /**
     * The creature weights under {@code node}. A name is the server's own; an entry written as an object, with a
     * type, a name, a scale and equipment, is put in {@code entries} under {@code <phase>/<entry>}.
     */
    private static Map<String, Double> creatures(
            String phase, ConfigurationNode node, Map<String, OneBlockEntries.Creature> entries) {
        Map<String, Double> weights = new LinkedHashMap<>();
        for (Map.Entry<Object, ? extends ConfigurationNode> entry :
                node.childrenMap().entrySet()) {
            String written = String.valueOf(entry.getKey());
            ConfigurationNode value = entry.getValue();
            if (value.isMap()) {
                OneBlockEntries.Creature creature = creature(phase, written, value);
                if (creature == null) {
                    continue;
                }
                String drawn = phase + "/" + written;
                entries.put(drawn, creature);
                weights.merge(drawn, value.node("weight").getDouble(1), Double::sum);
                continue;
            }
            String known = creatureName(written);
            if (known == null) {
                LOGGER.warning(() -> "OneBlock phase '" + phase + "' names '" + written
                        + "', which is not a creature that can be spawned, and it is left out.");
                continue;
            }
            weights.merge(known, value.getDouble(0), Double::sum);
        }
        return weights;
    }

    /** The creature an object entry writes, or nothing, with the reason, when it cannot be. */
    private static OneBlockEntries.@org.jspecify.annotations.Nullable Creature creature(
            String phase, String written, ConfigurationNode value) {
        String type = creatureName(value.node("type").getString(written));
        if (type == null) {
            LOGGER.warning(() -> "OneBlock phase '" + phase + "' has the creature '" + written + "', whose type '"
                    + value.node("type").getString(written) + "' cannot be spawned, and it is left out.");
            return null;
        }
        Map<String, String> equipment = new LinkedHashMap<>();
        for (Map.Entry<Object, ? extends ConfigurationNode> worn :
                value.node("equipment").childrenMap().entrySet()) {
            String slot = String.valueOf(worn.getKey()).toLowerCase(Locale.ROOT);
            Material item = Material.matchMaterial(worn.getValue().getString(""));
            if (!SLOTS.contains(slot) || item == null || item.isAir()) {
                LOGGER.warning(() -> "OneBlock phase '" + phase + "', creature '" + written + "': '" + worn.getKey()
                        + " = " + worn.getValue().getString("") + "' is no slot of " + SLOTS
                        + " with an item the server knows, and it is left out.");
                continue;
            }
            equipment.put(slot, item.name());
        }
        try {
            return new OneBlockEntries.Creature(
                    type, value.node("name").getString(), value.node("scale").getDouble(1), equipment);
        } catch (IllegalArgumentException wrong) {
            LOGGER.warning(() -> "OneBlock phase '" + phase + "', creature '" + written + "': " + wrong.getMessage()
                    + ". It is left out.");
            return null;
        }
    }

    /**
     * The block {@code written} names, as the server writes it: a plain name, or a name and its states as
     * {@code minecraft:oak_stairs[facing=north]}. Nothing when the server knows no such block or state.
     */
    private static @org.jspecify.annotations.Nullable String blockData(String written) {
        int states = written.indexOf('[');
        String base = blockName(states < 0 ? written : written.substring(0, states));
        if (base == null || states < 0) {
            return base;
        }
        try {
            return org.bukkit.Bukkit.createBlockData(written.toLowerCase(Locale.ROOT))
                    .getAsString();
        } catch (IllegalArgumentException wrong) {
            return null;
        }
    }

    private static @org.jspecify.annotations.Nullable String blockName(String written) {
        Material material = Material.matchMaterial(written);
        return material != null && material.isBlock() && !material.isAir() ? material.name() : null;
    }

    private static @org.jspecify.annotations.Nullable String creatureName(String written) {
        NamespacedKey key = NamespacedKey.fromString(written.toLowerCase(Locale.ROOT));
        EntityType type = key == null ? null : Registry.ENTITY_TYPE.get(key);
        return type != null && type.isSpawnable() && type.isAlive() ? type.name() : null;
    }
}
