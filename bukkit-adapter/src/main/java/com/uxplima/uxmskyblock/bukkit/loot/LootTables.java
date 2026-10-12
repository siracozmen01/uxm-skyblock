package com.uxplima.uxmskyblock.bukkit.loot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.Stream;

import org.bukkit.Material;

import com.uxplima.uxmlib.item.ItemConfig;
import com.uxplima.uxmskyblock.bukkit.config.EffectsConfiguration;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

/**
 * Every loot table under {@code loot/}, one a file, named by the file. A file that cannot be read is left out
 * with the reason, and the others are read all the same.
 *
 * <pre>{@code
 * rolls = "2-4"                          # how many times the table is rolled
 * rarities {                             # leave it out for one rarity, common
 *   common    { weight = 80 }
 *   rare      { weight = 18, effects = "loot-rare" }      # an entry of effects.conf, fired for the finder
 *   legendary { weight = 2,  effects = "loot-legendary" }
 * }
 * entries = [
 *   { weight = 10, amount = "2-6", item { material = IRON_INGOT } }
 *   { rarity = rare, slot = 13, item { material = DIAMOND_SWORD, enchants { sharpness = 3 } } }
 *   { rarity = legendary, actions = ["[give-money] 500"] }
 *   { weight = 3, vanilla = "minecraft:chests/simple_dungeon" }
 * ]
 * }</pre>
 *
 * <p>An entry names its rarity, or is common, and weighs one unless it says otherwise. An item is written as any
 * item is in a menu or a shop file; its amount is the entry's, a number or a span.
 */
public final class LootTables {

    private final Map<String, LootTable> tables;
    private final List<String> problems;

    private LootTables(Map<String, LootTable> tables, List<String> problems) {
        this.tables = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(tables));
        this.problems = List.copyOf(problems);
    }

    /** No tables at all. */
    public static LootTables none() {
        return new LootTables(Map.of(), List.of());
    }

    /** These tables, each by its name. */
    public static LootTables of(LootTable... tables) {
        Map<String, LootTable> byName = new LinkedHashMap<>();
        for (LootTable table : tables) {
            byName.put(table.id().toLowerCase(Locale.ROOT), table);
        }
        return new LootTables(byName, List.of());
    }

    /** Every {@code .conf} file in {@code folder}; a folder that is not there holds no tables. */
    public static LootTables read(Path folder) {
        Map<String, LootTable> tables = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        if (!Files.isDirectory(folder)) {
            return new LootTables(tables, problems);
        }
        List<Path> files;
        try (Stream<Path> listed = Files.list(folder)) {
            files = listed.filter(file -> file.getFileName().toString().endsWith(".conf"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return new LootTables(tables, List.of("loot/ could not be listed: " + e.getMessage()));
        }
        for (Path file : files) {
            String name = file.getFileName().toString();
            String id = name.substring(0, name.length() - ".conf".length()).toLowerCase(Locale.ROOT);
            try {
                ConfigurationNode root =
                        HoconConfigurationLoader.builder().path(file).build().load();
                tables.put(id, table(id, root));
            } catch (IOException | RuntimeException e) {
                problems.add("loot/" + name + " is left out: " + e.getMessage());
            }
        }
        return new LootTables(tables, problems);
    }

    /** The table read from {@code root}, named {@code id}. */
    public static LootTable table(String id, ConfigurationNode root) {
        Span rolls = Span.parse(root.node("rolls").getString("1"));
        Map<String, Rarity> rarities = new LinkedHashMap<>();
        ConfigurationNode written = root.node("rarities");
        if (written.virtual() || written.childrenMap().isEmpty()) {
            rarities.put(Rarity.COMMON, new Rarity(Rarity.COMMON, 1, ""));
        } else {
            for (Map.Entry<Object, ? extends ConfigurationNode> rarity :
                    written.childrenMap().entrySet()) {
                String name = String.valueOf(rarity.getKey()).toLowerCase(Locale.ROOT);
                rarities.put(
                        name,
                        new Rarity(
                                name,
                                rarity.getValue().node("weight").getInt(1),
                                rarity.getValue().node("effects").getString("")));
            }
        }
        List<LootEntry> entries = new ArrayList<>();
        List<? extends ConfigurationNode> listed = root.node("entries").childrenList();
        for (int i = 0; i < listed.size(); i++) {
            entries.add(entry(id, i + 1, listed.get(i)));
        }
        return new LootTable(id, rolls, rarities, entries);
    }

    /**
     * Refuses a material the game does not know. An item another plugin owns, named with its own namespace, is
     * only asked for when it is first made, as that plugin may not be up yet.
     */
    private static void checkMaterial(ConfigurationNode spec) {
        String raw = spec.node("material").getString();
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("item config requires a 'material'");
        }
        boolean foreign = raw.indexOf(':') >= 0 && !raw.toLowerCase(Locale.ROOT).startsWith("minecraft:");
        if (!foreign && Material.matchMaterial(raw) == null) {
            throw new IllegalArgumentException("unknown material: " + raw);
        }
    }

    private static LootEntry entry(String table, int number, ConfigurationNode node) {
        String rarity = node.node("rarity").getString(Rarity.COMMON).toLowerCase(Locale.ROOT);
        int weight = node.node("weight").getInt(1);
        boolean item = !node.node("item").virtual();
        boolean actions = !node.node("actions").virtual();
        boolean vanilla = !node.node("vanilla").virtual();
        if ((item ? 1 : 0) + (actions ? 1 : 0) + (vanilla ? 1 : 0) != 1) {
            throw new IllegalArgumentException(
                    "entry " + number + " must be exactly one of an item, actions or a vanilla table");
        }
        if (item) {
            ConfigurationNode slot = node.node("slot");
            ConfigurationNode spec = node.node("item").copy();
            checkMaterial(spec);
            return new LootEntry.Item(
                    rarity,
                    weight,
                    () -> ItemConfig.load(spec).build(),
                    Span.parse(node.node("amount").getString("1")),
                    slot.virtual() ? OptionalInt.empty() : OptionalInt.of(slot.getInt()));
        }
        if (actions) {
            List<String> lines = new ArrayList<>();
            for (ConfigurationNode line : node.node("actions").childrenList()) {
                String text = line.getString();
                if (text != null && !text.isBlank()) {
                    lines.add(text);
                }
            }
            return new LootEntry.Actions(
                    rarity, weight, lines, EffectsConfiguration.parseAll("loot " + table + " entry " + number, lines));
        }
        String key = node.node("vanilla").getString("");
        if (key.isBlank()) {
            throw new IllegalArgumentException("entry " + number + " names no vanilla table");
        }
        return new LootEntry.Vanilla(rarity, weight, key);
    }

    /** The table named {@code id}, as its file is named. */
    public Optional<LootTable> named(String id) {
        return Optional.ofNullable(tables.get(id.toLowerCase(Locale.ROOT)));
    }

    /** Every table, by name. */
    public Map<String, LootTable> all() {
        return tables;
    }

    /** Why each file left out was left out. */
    public List<String> problems() {
        return problems;
    }
}
