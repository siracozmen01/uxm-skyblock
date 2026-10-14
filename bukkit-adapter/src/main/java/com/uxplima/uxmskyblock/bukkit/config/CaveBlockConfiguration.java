package com.uxplima.uxmskyblock.bukkit.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;

import com.uxplima.uxmskyblock.core.domain.cave.CavePlan;
import com.uxplima.uxmskyblock.core.domain.cave.OreTable;
import org.spongepowered.configurate.ConfigurationNode;

/**
 * {@code modules/caveblock.conf}: whether islands can be CaveBlock islands, the shape of the rock they
 * are enclosed in, and the rock and ores of each dimension.
 *
 * @param shell the block the rock is closed in by, below, above and on every side
 * @param palettes the rock of each dimension, by dimension id; a dimension not named uses the overworld's
 */
public record CaveBlockConfiguration(
        boolean enabled,
        CavePlan.Shape shape,
        String shell,
        Map<String, Palette> palettes,
        Map<String, Structure> structures) {

    public static final String OVERWORLD = "overworld";
    public static final String NETHER = "the_nether";
    public static final String END = "the_end";

    private static final Logger LOGGER = Logger.getLogger(CaveBlockConfiguration.class.getName());

    /**
     * The rock of one dimension.
     *
     * @param rock the block the rock is
     * @param ores the ores in the rock
     * @param deepRock the block the rock turns to deep down, or the rock itself
     * @param deepBelow how many blocks below the arrival the rock turns deep
     * @param deepOres the ores in the deep rock
     */
    public record Palette(String rock, OreTable ores, String deepRock, int deepBelow, OreTable deepOres) {

        public Palette {
            Objects.requireNonNull(rock, "rock must not be null");
            Objects.requireNonNull(ores, "ores must not be null");
            Objects.requireNonNull(deepRock, "deepRock must not be null");
            Objects.requireNonNull(deepOres, "deepOres must not be null");
        }

        /** A rock that is the same all the way down. */
        public static Palette plain(String rock, OreTable ores) {
            return new Palette(rock, ores, rock, Integer.MAX_VALUE, ores);
        }
    }

    /**
     * A kind of structure the rock holds, laid after the rock around the island.
     *
     * @param templates the templates to choose from: the server's own, a datapack's, or schematic:&lt;name&gt; for the
     *     file schematics/&lt;name&gt;.schem under the plugin's folder
     * @param count how many are laid, spread evenly around the island
     * @param distance how far from the island's centre each one's middle lies
     * @param depth how far below the block players arrive on each one's floor lies; below zero is above it
     * @param loot the loot tables its chests and barrels get, each in turn
     * @param spawner the creature its spawners spawn, by key; empty to leave them as they are
     */
    public record Structure(
            List<String> templates, int count, int distance, int depth, List<String> loot, String spawner) {

        public Structure {
            templates = List.copyOf(templates);
            loot = List.copyOf(loot);
            Objects.requireNonNull(spawner, "spawner must not be null");
            if (count < 0 || distance < 0) {
                throw new IllegalArgumentException("count and distance must not be negative");
            }
        }
    }

    /** The shipped structures: a dungeon, mineshafts, a geode and a trial chamber, each its own schematic. */
    public static final Map<String, Structure> SHIPPED_STRUCTURES = Map.of(
            "dungeon",
            new Structure(
                    List.of("schematic:cave_dungeon"), 1, 16, 10, List.of("minecraft:chests/simple_dungeon"), "zombie"),
            "mineshaft",
            new Structure(
                    List.of("schematic:cave_mineshaft"), 2, 18, 3, List.of("minecraft:chests/abandoned_mineshaft"), ""),
            "geode",
            new Structure(List.of("schematic:cave_geode"), 1, 20, 16, List.of(), ""),
            "trial_chamber",
            new Structure(
                    List.of("schematic:cave_trial_chamber"),
                    1,
                    18,
                    20,
                    List.of("minecraft:chests/trial_chambers/reward"),
                    "breeze"));

    /** The shipped structures. */
    public CaveBlockConfiguration(boolean enabled, CavePlan.Shape shape, String shell, Map<String, Palette> palettes) {
        this(enabled, shape, shell, palettes, SHIPPED_STRUCTURES);
    }

    public CaveBlockConfiguration {
        structures = Map.copyOf(structures);
        Objects.requireNonNull(shape, "shape must not be null");
        Objects.requireNonNull(shell, "shell must not be null");
        palettes = Map.copyOf(palettes);
        if (!palettes.containsKey(OVERWORLD)) {
            throw new IllegalArgumentException("the overworld's rock must be named");
        }
    }

    /** The rock of a dimension, by its id. */
    public Palette palette(String dimension) {
        Palette named = palettes.get(dimension);
        return named != null ? named : Objects.requireNonNull(palettes.get(OVERWORLD));
    }

    public static CaveBlockConfiguration defaultConfiguration() {
        return new CaveBlockConfiguration(
                true,
                CavePlan.Shape.SHIPPED,
                "BEDROCK",
                Map.of(
                        OVERWORLD,
                        new Palette(
                                "STONE",
                                ores(
                                        "COAL_ORE:0.014",
                                        "IRON_ORE:0.009",
                                        "COPPER_ORE:0.008",
                                        "GOLD_ORE:0.002",
                                        "REDSTONE_ORE:0.004",
                                        "LAPIS_ORE:0.002",
                                        "EMERALD_ORE:0.0004"),
                                "DEEPSLATE",
                                12,
                                ores(
                                        "DEEPSLATE_IRON_ORE:0.008",
                                        "DEEPSLATE_GOLD_ORE:0.004",
                                        "DEEPSLATE_REDSTONE_ORE:0.006",
                                        "DEEPSLATE_LAPIS_ORE:0.003",
                                        "DEEPSLATE_DIAMOND_ORE:0.0015")),
                        NETHER,
                        Palette.plain(
                                "NETHERRACK",
                                ores("NETHER_QUARTZ_ORE:0.016", "NETHER_GOLD_ORE:0.008", "ANCIENT_DEBRIS:0.0004")),
                        END,
                        Palette.plain("END_STONE", OreTable.NONE)));
    }

    public static CaveBlockConfiguration load(ConfigurationNode root) {
        CaveBlockConfiguration shipped = defaultConfiguration();
        CavePlan.Shape shape;
        try {
            ConfigurationNode size = root.node("size");
            ConfigurationNode room = root.node("room");
            ConfigurationNode tunnels = root.node("tunnels");
            ConfigurationNode ravines = root.node("ravines");
            CavePlan.Shape s = CavePlan.Shape.SHIPPED;
            shape = new CavePlan.Shape(
                    size.node("radius").getInt(s.radius()),
                    size.node("below").getInt(s.below()),
                    size.node("above").getInt(s.above()),
                    room.node("radius").getInt(s.room()),
                    tunnels.node("count").getInt(s.tunnels()),
                    tunnels.node("length").getInt(s.tunnelLength()),
                    ravines.node("count").getInt(s.ravines()),
                    ravines.node("length").getInt(s.ravineLength()),
                    ravines.node("depth").getInt(s.ravineDepth()));
        } catch (IllegalArgumentException e) {
            LOGGER.warning(() -> "modules/caveblock.conf: " + e.getMessage() + ". The shipped shape is used.");
            shape = CavePlan.Shape.SHIPPED;
        }
        Map<String, Palette> palettes = new java.util.HashMap<>(shipped.palettes());
        ConfigurationNode written = root.node("palettes");
        for (Map.Entry<Object, ? extends ConfigurationNode> entry :
                written.childrenMap().entrySet()) {
            String dimension = String.valueOf(entry.getKey()).trim().toLowerCase(Locale.ROOT);
            Palette fallback = shipped.palette(dimension);
            try {
                palettes.put(dimension, palette(entry.getValue(), fallback));
            } catch (IllegalArgumentException e) {
                LOGGER.warning(() -> "modules/caveblock.conf palettes." + dimension + ": " + e.getMessage()
                        + ". The shipped rock is used.");
            }
        }
        return new CaveBlockConfiguration(
                root.node("enabled").getBoolean(true),
                shape,
                root.node("shell").getString(shipped.shell()).trim().toUpperCase(Locale.ROOT),
                palettes,
                structures(root.node("structures")));
    }

    /**
     * The structures the file writes, by name, over the shipped ones: a shipped one the file leaves out stays, and
     * one written takes what it leaves out from the shipped one of its name. One that cannot be read is left out.
     */
    private static Map<String, Structure> structures(ConfigurationNode written) {
        Map<String, Structure> all = new java.util.LinkedHashMap<>(SHIPPED_STRUCTURES);
        for (Map.Entry<Object, ? extends ConfigurationNode> entry :
                written.childrenMap().entrySet()) {
            String name = String.valueOf(entry.getKey()).trim().toLowerCase(Locale.ROOT);
            ConfigurationNode node = entry.getValue();
            Structure shipped =
                    SHIPPED_STRUCTURES.getOrDefault(name, new Structure(List.of(), 1, 16, 8, List.of(), ""));
            try {
                all.put(
                        name,
                        new Structure(
                                AcidIslandConfiguration.strings(node.node("templates"), shipped.templates()),
                                node.node("count").getInt(shipped.count()),
                                node.node("distance").getInt(shipped.distance()),
                                node.node("depth").getInt(shipped.depth()),
                                AcidIslandConfiguration.strings(node.node("loot"), shipped.loot()),
                                node.node("spawner")
                                        .getString(shipped.spawner())
                                        .trim()
                                        .toLowerCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                all.remove(name);
                LOGGER.warning(() ->
                        "modules/caveblock.conf structures." + name + ": " + e.getMessage() + ". It is left out.");
            }
        }
        return all;
    }

    private static Palette palette(ConfigurationNode node, Palette fallback) {
        String rock = node.node("rock").getString(fallback.rock()).trim().toUpperCase(Locale.ROOT);
        OreTable ores = oresOf(node.node("ores"), fallback.ores());
        if (node.node("deep-rock").virtual() && fallback.deepBelow() == Integer.MAX_VALUE) {
            return Palette.plain(rock, ores);
        }
        return new Palette(
                rock,
                ores,
                node.node("deep-rock").getString(fallback.deepRock()).trim().toUpperCase(Locale.ROOT),
                node.node("deep-below").getInt(fallback.deepBelow()),
                oresOf(node.node("deep-ores"), fallback.deepOres()));
    }

    private static OreTable oresOf(ConfigurationNode node, OreTable fallback) {
        if (node.virtual()) {
            return fallback;
        }
        List<OreTable.Ore> read = new ArrayList<>();
        for (ConfigurationNode child : node.childrenList()) {
            read.add(OreTable.Ore.parse(child.getString("")));
        }
        return new OreTable(read);
    }

    private static OreTable ores(String... written) {
        List<OreTable.Ore> read = new ArrayList<>();
        for (String line : written) {
            read.add(OreTable.Ore.parse(line));
        }
        return new OreTable(read);
    }
}
