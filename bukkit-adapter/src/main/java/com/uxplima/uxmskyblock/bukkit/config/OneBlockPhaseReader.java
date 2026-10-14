package com.uxplima.uxmskyblock.bukkit.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;

import com.uxplima.uxmskyblock.bukkit.outside.OutsideContent;
import com.uxplima.uxmskyblock.core.domain.requirement.Requirement;
import com.uxplima.uxmskyblock.core.domain.requirement.RequirementLines;
import org.spongepowered.configurate.ConfigurationNode;

/**
 * How one OneBlock phase file is read: its blocks, creatures, biome, gate, display and lists, each line checked and every unreadable one reported. Split out of {@link OneBlockConfiguration}, whose services and words it uses, so neither file is over the size the
 * standards draw a line at.
 */
final class OneBlockPhaseReader {

    private OneBlockPhaseReader() {}

    /**
     * The block weights under {@code node}. A name is the server's own, or a block with its states as
     * {@code oak_stairs[facing=north]}; an entry written as an object is put in {@code entries} under the name it is
     * drawn by, {@code <phase>/<entry>}. Only what the server knows is kept.
     */
    static Map<String, Double> blocks(
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
                if (known == null || (spawns != null && OneBlockConfiguration.creatureName(spawns) == null)) {
                    OneBlockConfiguration.warn(
                            () -> "OneBlock phase '" + phase + "' has the entry '" + written + "', whose block '"
                                    + data + "'" + (spawns == null ? "" : " or creature '" + spawns + "'")
                                    + " the server does not know, and it is left out.");
                    continue;
                }
                String drawn = phase + "/" + written;
                entries.put(
                        drawn,
                        new OneBlockEntries.Block(
                                known,
                                spawns == null ? null : OneBlockConfiguration.creatureName(spawns),
                                value.node("loot").getString(),
                                value.node("rarity").getString()));
                weights.merge(drawn, value.node("weight").getDouble(1), Double::sum);
                continue;
            }
            if (outside(phase, written, OutsideContent.Kind.BLOCK, value, weights)) {
                continue;
            }
            String known = blockData(written);
            if (known == null) {
                OneBlockConfiguration.warn(() -> "OneBlock phase '" + phase + "' names '" + written
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
    static Map<String, Double> creatures(
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
            if (outside(phase, written, OutsideContent.Kind.CREATURE, value, weights)) {
                continue;
            }
            String known = OneBlockConfiguration.creatureName(written);
            if (known == null) {
                OneBlockConfiguration.warn(() -> "OneBlock phase '" + phase + "' names '" + written
                        + "', which is not a creature that can be spawned, and it is left out.");
                continue;
            }
            weights.merge(known, value.getDouble(0), Double::sum);
        }
        return weights;
    }
    /** The creature an object entry writes, or nothing, with the reason, when it cannot be. */
    static OneBlockEntries.@org.jspecify.annotations.Nullable Creature creature(
            String phase, String written, ConfigurationNode value) {
        String type = OneBlockConfiguration.creatureName(value.node("type").getString(written));
        if (type == null) {
            OneBlockConfiguration.warn(
                    () -> "OneBlock phase '" + phase + "' has the creature '" + written + "', whose type '"
                            + value.node("type").getString(written) + "' cannot be spawned, and it is left out.");
            return null;
        }
        Map<String, String> equipment = new LinkedHashMap<>();
        for (Map.Entry<Object, ? extends ConfigurationNode> worn :
                value.node("equipment").childrenMap().entrySet()) {
            String slot = String.valueOf(worn.getKey()).toLowerCase(Locale.ROOT);
            Material item = Material.matchMaterial(worn.getValue().getString(""));
            if (!OneBlockConfiguration.SLOTS.contains(slot) || item == null || item.isAir()) {
                OneBlockConfiguration.warn(() -> "OneBlock phase '" + phase + "', creature '" + written + "': '"
                        + worn.getKey()
                        + " = " + worn.getValue().getString("") + "' is no slot of " + OneBlockConfiguration.SLOTS
                        + " with an item the server knows, and it is left out.");
                continue;
            }
            equipment.put(slot, item.name());
        }
        try {
            return new OneBlockEntries.Creature(
                    type, value.node("name").getString(), value.node("scale").getDouble(1), equipment);
        } catch (IllegalArgumentException wrong) {
            OneBlockConfiguration.warn(() -> "OneBlock phase '" + phase + "', creature '" + written + "': "
                    + wrong.getMessage() + ". It is left out.");
            return null;
        }
    }
    /**
     * The biome a phase file names, over the area it names: {@code island}, or left out, for the whole island, or a
     * number of blocks around the island's block. A biome the server does not know is left out with a warning, and
     * an area that is neither is read as the whole island with one.
     */
    static @org.jspecify.annotations.Nullable OneBlockBiome biome(String phase, String written, String area) {
        if (written.isBlank()) {
            return null;
        }
        NamespacedKey key = NamespacedKey.fromString(written.strip().toLowerCase(Locale.ROOT));
        org.bukkit.block.Biome biome = key == null
                ? null
                : io.papermc.paper.registry.RegistryAccess.registryAccess()
                        .getRegistry(io.papermc.paper.registry.RegistryKey.BIOME)
                        .get(key);
        if (biome == null) {
            OneBlockConfiguration.warn(() -> "OneBlock phase '" + phase + "' names the biome '" + written
                    + "', which the server does not know, so the phase leaves the biome as it is.");
            return null;
        }
        String around = area.strip().toLowerCase(Locale.ROOT);
        if (around.isEmpty() || around.equals("island")) {
            return new OneBlockBiome(biome, OneBlockBiome.ISLAND);
        }
        try {
            int radius = Integer.parseInt(around);
            if (radius >= 0) {
                return new OneBlockBiome(biome, radius);
            }
        } catch (NumberFormatException notANumber) {
            // Said below with the value that is no area.
        }
        OneBlockConfiguration.warn(() -> "OneBlock phase '" + phase + "' has biome-area '" + area
                + "', which is neither island nor a number of blocks, so the whole island takes the biome.");
        return new OneBlockBiome(biome, OneBlockBiome.ISLAND);
    }
    /**
     * Reads {@code written} when it names another plugin's block or creature: kept under its plugin's word while the
     * plugin is on this server, left out with one warning when it is not.
     *
     * @return whether the name was another plugin's, read or left out
     */
    static boolean outside(
            String phase,
            String written,
            OutsideContent.Kind kind,
            ConfigurationNode value,
            Map<String, Double> weights) {
        OutsideContent.Named named = OutsideContent.named(written, kind).orElse(null);
        if (named == null) {
            return false;
        }
        if (named.present()) {
            weights.merge(named.written(), value.getDouble(0), Double::sum);
        } else {
            OneBlockConfiguration.warn(() -> "OneBlock phase '" + phase + "' names '" + written + "', and "
                    + named.plugin() + " is not on this server, so it is left out.");
        }
        return true;
    }
    /**
     * The block {@code written} names, as the server writes it: a plain name, or a name and its states as
     * {@code minecraft:oak_stairs[facing=north]}. Nothing when the server knows no such block or state.
     */
    static @org.jspecify.annotations.Nullable String blockData(String written) {
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

    static @org.jspecify.annotations.Nullable String blockName(String written) {
        Material material = Material.matchMaterial(written);
        return material != null && material.isBlock() && !material.isAir() ? material.name() : null;
    }
    /** A creature a moment names: another plugin's while it is on this server, or one the server knows. */
    static @org.jspecify.annotations.Nullable String drawableCreature(String written) {
        var named = OutsideContent.named(written, OutsideContent.Kind.CREATURE);
        if (named.isPresent()) {
            return named.get().present() ? named.get().written() : null;
        }
        return OneBlockConfiguration.creatureName(written);
    }
    /** A block a moment names: another plugin's while it is on this server, or one the server knows. */
    static @org.jspecify.annotations.Nullable String drawableBlock(String written) {
        var named = OutsideContent.named(written, OutsideContent.Kind.BLOCK);
        if (named.isPresent()) {
            return named.get().present() ? named.get().written() : null;
        }
        return blockData(written);
    }
    /** The icon written, or the default when it is no name the server knows or names air. */
    static String icon(String phase, String written) {
        Material material = Material.matchMaterial(written);
        if (material == null || material.isAir()) {
            OneBlockConfiguration.warn(() -> "OneBlock phase '" + phase + "' has the icon '" + written
                    + "', which the server does not know, so " + OneBlockConfiguration.DEFAULT_ICON + " is used.");
            return OneBlockConfiguration.DEFAULT_ICON;
        }
        return material.name();
    }
    /**
     * What the phase writes to stand before it: {@code wait}, a time as {@code 5m}, and {@code requires}, a list of
     * requirement lines. A line that cannot be read, a wait line, which {@code wait} says instead, and a points or a
     * ladder level line, which only ChunkBlock keeps, are left out with a warning.
     */
    static OneBlockGate gate(String phase, ConfigurationNode node) {
        Duration wait = Duration.ZERO;
        String written = node.node("wait").getString("").strip();
        if (!written.isEmpty()) {
            Optional<Duration> read = RequirementLines.duration(written);
            if (read.isPresent()) {
                wait = read.get();
            } else {
                OneBlockConfiguration.warn(() -> "OneBlock phase '" + phase + "' waits '" + written
                        + "', which is no time such as 5m or 1h30m, so it waits for nothing.");
            }
        }
        List<Requirement> requires = new ArrayList<>();
        for (ConfigurationNode line : node.node("requires").childrenList()) {
            String text = line.getString("");
            Optional<Requirement> read = RequirementLines.parse(text);
            if (read.isEmpty()
                    || read.get() instanceof Requirement.Wait
                    || read.get() instanceof Requirement.Points
                    || (read.get() instanceof Requirement.Level level && level.level() == Requirement.Level.LADDER)) {
                OneBlockConfiguration.warn(() -> "OneBlock phase '" + phase + "' requires '" + text
                        + "', which it cannot ask, and the line is left out. A wait is written as wait = \"5m\".");
                continue;
            }
            requires.add(read.get());
        }
        return new OneBlockGate(wait, requires);
    }
    /**
     * Reads {@code progress}: {@code bar}, {@code linger} and {@code line}. A value that cannot be read is said in
     * the console, and the shipped one is used for it.
     */
    static OneBlockDisplay display(ConfigurationNode node) {
        OneBlockDisplay shipped = OneBlockDisplay.SHIPPED;
        String bar = node.node("bar").getString(shipped.bar().key());
        OneBlockDisplay.Bar shown = OneBlockDisplay.Bar.named(bar).orElseGet(() -> {
            OneBlockConfiguration.warn(
                    () -> "progress.bar is '" + bar + "', which is none of bossbar, actionbar and none, so "
                            + shipped.bar().key() + " is used.");
            return shipped.bar();
        });
        String linger = node.node("linger").getString("").strip();
        Duration stays = linger.isEmpty()
                ? shipped.linger()
                : RequirementLines.duration(linger)
                        .filter(time -> !time.isZero())
                        .orElseGet(() -> {
                            OneBlockConfiguration.warn(
                                    () -> "progress.linger is '" + linger + "', which is no time, so "
                                            + shipped.linger().toSeconds() + " seconds is used.");
                            return shipped.linger();
                        });
        return new OneBlockDisplay(shown, stays, node.node("line").getBoolean(shipped.line()));
    }
    /** What the phase writes to run when it begins, finishes and first finishes; a line that cannot be is skipped. */
    static Map<String, OneBlockLists> lists(String phase, ConfigurationNode node) {
        Map<String, OneBlockLists> byEvent = new LinkedHashMap<>();
        for (String event : OneBlockLists.EVENTS) {
            ConfigurationNode written = node.node(event);
            if (written.virtual()) {
                continue;
            }
            try {
                byEvent.put(
                        event,
                        new OneBlockLists(
                                EffectsConfiguration.parseAll(
                                        "oneblock phase " + phase + ", " + event + ".breaker",
                                        written.node("breaker").getList(String.class, List.of())),
                                EffectsConfiguration.parseAll(
                                        "oneblock phase " + phase + ", " + event + ".members",
                                        written.node("members").getList(String.class, List.of())),
                                event.equals(OneBlockLists.LUCKY) ? chance(phase, written.node("chance")) : 1));
            } catch (org.spongepowered.configurate.serialize.SerializationException wrong) {
                OneBlockConfiguration.warn(() -> "OneBlock phase '" + phase + "', " + event + " cannot be read: "
                        + wrong.getMessage() + ". The shipped list in modules/effects.conf runs instead.");
            }
        }
        return byEvent;
    }
    /**
     * How often a break of {@code phase} is lucky, as its file writes {@code lucky.chance}, from 0 to 1. A chance left
     * out, or one that is no chance, is said in the console and read as never.
     */
    static double chance(String phase, ConfigurationNode node) {
        double chance = node.getDouble(-1);
        if (chance >= 0 && chance <= 1) {
            return chance;
        }
        OneBlockConfiguration.warn(() -> "OneBlock phase '" + phase + "' has a lucky break with chance '" + node.raw()
                + "', which is no chance from 0 to 1, so no break of it is lucky.");
        return 0;
    }
}
