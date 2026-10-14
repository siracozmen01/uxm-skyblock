package com.uxplima.uxmskyblock.bukkit.config;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;

import com.uxplima.uxmskyblock.core.domain.hazard.PoseidonRules;
import org.spongepowered.configurate.ConfigurationNode;

/**
 * {@code modules/poseidon.conf}: whether islands can be Poseidon islands, the ocean they are made at
 * the bottom of, and how the air hurts the players who live there.
 *
 * @param waterEffects the effects the water gives, each written {@code name:amplifier:seconds}
 * @param wrecks the wrecks and ruins a preset can lay, by name; each is the action {@code uxm:<name>}
 */
public record PoseidonConfiguration(
        boolean enabled,
        Ocean ocean,
        PoseidonRules rules,
        List<String> waterEffects,
        Map<String, Wreck> wrecks,
        List<String> chapters,
        Boss boss,
        Coral coral) {

    private static final Logger LOGGER = Logger.getLogger(PoseidonConfiguration.class.getName());

    /**
     * The ocean a Poseidon island lies at the bottom of.
     *
     * @param above how many blocks of water stand over the block players arrive on
     * @param depth how many blocks of water lie under it, down to the floor
     * @param radius how far from the island's centre the ocean reaches; keep it inside half the grid spacing
     * @param floor the block the ocean stands on; one that falls, such as sand, is replaced by sandstone
     */
    public record Ocean(int above, int depth, int radius, String floor) {

        public static final Ocean SHIPPED = new Ocean(24, 8, 64, "SANDSTONE");

        public Ocean {
            Objects.requireNonNull(floor, "floor must not be null");
            if (above < 2 || depth < 1 || radius < 1) {
                throw new IllegalArgumentException("the ocean needs 2 blocks above, 1 below and a radius of 1");
            }
        }
    }

    /** The effects the shipped file gives in the water: breathing, and sight a little past the beat. */
    public static final List<String> SHIPPED_WATER_EFFECTS = List.of("water_breathing:0:4", "night_vision:0:15");

    /**
     * Structures laid on the sea floor around a Poseidon island.
     *
     * @param templates the structure templates to choose from, such as {@code minecraft:shipwreck/with_mast}
     * @param count how many are laid, spread evenly around the island
     * @param distance how far from the island's centre each one lies
     * @param loot the loot tables the chests get, each in turn
     */
    public record Wreck(List<String> templates, int count, int distance, List<String> loot) {

        public Wreck {
            templates = List.copyOf(templates);
            loot = List.copyOf(loot);
            if (count < 0 || distance < 0) {
                throw new IllegalArgumentException("count and distance must not be negative");
            }
        }
    }

    /** The shipped wrecks: a shipwreck, one big ruin, and a ring of small ones. */
    public static final Map<String, Wreck> SHIPPED_WRECKS = shippedWrecks();

    /**
     * The deep boss: now and then something rises from the deep under a Poseidon island where a player swims.
     *
     * @param enabled whether it rises at all
     * @param entity the creature that rises, by its key, such as {@code elder_guardian}
     * @param health the health it rises with
     * @param checkEvery how often each player in the water on a Poseidon island is looked at
     * @param chance the chance, from 0 to 1, that it rises at one look
     * @param every the least time between two on one island
     * @param lasts how long it stays before it sinks back
     * @param loot the loot table in loot/ its slayer is given, by name; empty for none
     */
    public record Boss(
            boolean enabled,
            String entity,
            double health,
            Duration checkEvery,
            double chance,
            Duration every,
            Duration lasts,
            String loot) {

        public static final Boss SHIPPED = new Boss(
                true,
                "elder_guardian",
                160,
                Duration.ofMinutes(1),
                0.05,
                Duration.ofHours(2),
                Duration.ofMinutes(10),
                "deep_boss");

        public Boss {
            Objects.requireNonNull(entity, "entity must not be null");
            Objects.requireNonNull(loot, "loot must not be null");
            if (!(health > 0)) {
                throw new IllegalArgumentException("the health must be above 0");
            }
            if (!(chance >= 0 && chance <= 1)) {
                throw new IllegalArgumentException("the chance must be from 0 to 1");
            }
            if (checkEvery.isNegative() || checkEvery.isZero() || every.isNegative() || !lasts.isPositive()) {
                throw new IllegalArgumentException("check-every and lasts must be above 0, every not below");
            }
        }
    }

    /**
     * Coral that spreads slowly on a Poseidon island: around each player there, now and then, a living coral block
     * grows a coral or a fan of its colour into the water beside it.
     *
     * @param enabled whether coral spreads at all
     * @param checkEvery how often each player on a Poseidon island is looked around
     * @param samples how many blocks around them are looked at, each time
     * @param radius how far from them a block may be
     * @param chance the chance, from 0 to 1, that a coral block looked at grows
     */
    public record Coral(boolean enabled, Duration checkEvery, int samples, int radius, double chance) {

        public static final Coral SHIPPED = new Coral(true, Duration.ofSeconds(30), 24, 12, 0.25);

        public Coral {
            if (!checkEvery.isPositive() || samples < 0 || radius < 1 || !(chance >= 0 && chance <= 1)) {
                throw new IllegalArgumentException(
                        "check-every and radius must be above 0, samples not below 0, the chance from 0 to 1");
            }
        }
    }

    /** The wreck group a monument start lays, from the monument block of the file. */
    public static final String MONUMENT = "monument";

    /** The story missions whose chapters the lore book holds, in their order. */
    public static final List<String> SHIPPED_CHAPTERS =
            List.of("poseidon_story_1", "poseidon_story_2", "poseidon_story_3", "poseidon_story_4", "poseidon_story_5");

    public PoseidonConfiguration {
        Objects.requireNonNull(boss, "boss must not be null");
        Objects.requireNonNull(coral, "coral must not be null");
        // A mission is known by its id in lower case, as the missions file reads it.
        chapters = chapters.stream()
                .map(chapter -> chapter.trim().toLowerCase(java.util.Locale.ROOT))
                .toList();
        Objects.requireNonNull(ocean, "ocean must not be null");
        Objects.requireNonNull(rules, "rules must not be null");
        waterEffects = List.copyOf(waterEffects);
        wrecks = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(wrecks));
    }

    /** The shipped ocean, hazard and wrecks, with Poseidon on or off. */
    public PoseidonConfiguration(boolean enabled, Ocean ocean) {
        this(enabled, ocean, PoseidonRules.shipped(), SHIPPED_WATER_EFFECTS, SHIPPED_WRECKS);
    }

    /** The shipped chapters of the story and the shipped boss. */
    public PoseidonConfiguration(
            boolean enabled, Ocean ocean, PoseidonRules rules, List<String> waterEffects, Map<String, Wreck> wrecks) {
        this(enabled, ocean, rules, waterEffects, wrecks, SHIPPED_CHAPTERS, Boss.SHIPPED, Coral.SHIPPED);
    }

    private static Map<String, Wreck> shippedWrecks() {
        Map<String, Wreck> wrecks = new LinkedHashMap<>();
        wrecks.put(
                "shipwreck",
                new Wreck(
                        List.of(
                                "minecraft:shipwreck/rightsideup_full",
                                "minecraft:shipwreck/sideways_full",
                                "minecraft:shipwreck/upsidedown_full",
                                "minecraft:shipwreck/with_mast"),
                        1,
                        22,
                        List.of(
                                "minecraft:chests/shipwreck_supply",
                                "minecraft:chests/shipwreck_treasure",
                                "minecraft:chests/shipwreck_map")));
        wrecks.put(
                "ruin",
                new Wreck(
                        List.of(
                                "minecraft:underwater_ruin/big_warm_4",
                                "minecraft:underwater_ruin/big_warm_5",
                                "minecraft:underwater_ruin/big_warm_6",
                                "minecraft:underwater_ruin/big_warm_7"),
                        1,
                        40,
                        List.of("minecraft:chests/underwater_ruin_big")));
        wrecks.put(
                "ruins",
                new Wreck(
                        List.of(
                                "minecraft:underwater_ruin/warm_1",
                                "minecraft:underwater_ruin/warm_2",
                                "minecraft:underwater_ruin/warm_3",
                                "minecraft:underwater_ruin/warm_4",
                                "minecraft:underwater_ruin/warm_5",
                                "minecraft:underwater_ruin/warm_6",
                                "minecraft:underwater_ruin/warm_7",
                                "minecraft:underwater_ruin/warm_8"),
                        5,
                        44,
                        List.of("minecraft:chests/underwater_ruin_small")));
        wrecks.put(
                MONUMENT,
                new Wreck(List.of("schematic:monument"), 1, 0, List.of("minecraft:chests/underwater_ruin_big")));
        return wrecks;
    }

    public static PoseidonConfiguration defaultConfiguration() {
        return new PoseidonConfiguration(true, Ocean.SHIPPED);
    }

    public static PoseidonConfiguration load(ConfigurationNode root) {
        Ocean ocean;
        ConfigurationNode node = root.node("ocean");
        try {
            ocean = new Ocean(
                    node.node("above").getInt(Ocean.SHIPPED.above()),
                    node.node("depth").getInt(Ocean.SHIPPED.depth()),
                    node.node("radius").getInt(Ocean.SHIPPED.radius()),
                    node.node("floor").getString(Ocean.SHIPPED.floor()).trim());
        } catch (IllegalArgumentException e) {
            LOGGER.warning(() -> "modules/poseidon.conf ocean: " + e.getMessage() + ". The shipped ocean is used.");
            ocean = Ocean.SHIPPED;
        }
        PoseidonRules shipped = PoseidonRules.shipped();
        ConfigurationNode hazard = root.node("hazard");
        PoseidonRules rules;
        try {
            rules = new PoseidonRules(
                    hazard.node("dry-damage").getDouble(shipped.dryDamage()),
                    hazard.node("sun-damage").getDouble(shipped.sunDamage()),
                    hazard.node("still-damage").getDouble(shipped.stillDamage()),
                    AcidIslandConfiguration.durationOf(
                            hazard.node("still-after").getString(""), shipped.stillAfter()),
                    hazard.node("still-reach").getDouble(shipped.stillReach()),
                    hazard.node("rain-is-wet").getBoolean(shipped.rainIsWet()),
                    AcidIslandConfiguration.durationOf(
                            hazard.node("check-every").getString(""), shipped.checkEvery()));
        } catch (IllegalArgumentException e) {
            LOGGER.warning(() -> "modules/poseidon.conf hazard: " + e.getMessage() + ". The shipped numbers are used.");
            rules = shipped;
        }
        return new PoseidonConfiguration(
                root.node("enabled").getBoolean(true),
                ocean,
                rules,
                AcidIslandConfiguration.strings(hazard.node("water-effects"), SHIPPED_WATER_EFFECTS),
                withMonument(wrecks(root.node("wrecks")), root.node("wrecks"), root.node("monument")),
                AcidIslandConfiguration.strings(root.node("lore", "chapters"), SHIPPED_CHAPTERS),
                boss(root.node("boss")),
                coral(root.node("coral")));
    }

    private static Coral coral(ConfigurationNode node) {
        Coral shipped = Coral.SHIPPED;
        try {
            return new Coral(
                    node.node("enabled").getBoolean(shipped.enabled()),
                    AcidIslandConfiguration.durationOf(node.node("check-every").getString(""), shipped.checkEvery()),
                    node.node("samples").getInt(shipped.samples()),
                    node.node("radius").getInt(shipped.radius()),
                    node.node("chance").getDouble(shipped.chance()));
        } catch (IllegalArgumentException e) {
            LOGGER.warning(() -> "modules/poseidon.conf coral: " + e.getMessage() + ". The shipped growth is used.");
            return shipped;
        }
    }

    private static Boss boss(ConfigurationNode node) {
        Boss shipped = Boss.SHIPPED;
        try {
            return new Boss(
                    node.node("enabled").getBoolean(shipped.enabled()),
                    node.node("entity").getString(shipped.entity()).trim().toLowerCase(java.util.Locale.ROOT),
                    node.node("health").getDouble(shipped.health()),
                    AcidIslandConfiguration.durationOf(node.node("check-every").getString(""), shipped.checkEvery()),
                    node.node("chance").getDouble(shipped.chance()),
                    AcidIslandConfiguration.durationOf(node.node("every").getString(""), shipped.every()),
                    AcidIslandConfiguration.durationOf(node.node("lasts").getString(""), shipped.lasts()),
                    node.node("loot").getString(shipped.loot()).trim());
        } catch (IllegalArgumentException e) {
            LOGGER.warning(() -> "modules/poseidon.conf boss: " + e.getMessage() + ". The shipped boss is used.");
            return shipped;
        }
    }

    /**
     * The wrecks with the temple a monument start lays, read from its own block so a file written before it gains
     * the block with the release. A wreck group the operator named monument wins.
     */
    private static Map<String, Wreck> withMonument(
            Map<String, Wreck> wrecks, ConfigurationNode written, ConfigurationNode node) {
        if (!written.node(MONUMENT).virtual()) {
            return wrecks;
        }
        Map<String, Wreck> all = new LinkedHashMap<>(wrecks);
        Wreck shipped = Objects.requireNonNull(SHIPPED_WRECKS.get(MONUMENT), "the shipped monument");
        try {
            all.put(
                    MONUMENT,
                    new Wreck(
                            AcidIslandConfiguration.strings(node.node("templates"), shipped.templates()),
                            node.node("count").getInt(shipped.count()),
                            node.node("distance").getInt(shipped.distance()),
                            AcidIslandConfiguration.strings(node.node("loot"), shipped.loot())));
        } catch (IllegalArgumentException e) {
            LOGGER.warning(() -> "modules/poseidon.conf monument: " + e.getMessage() + ". The shipped temple is used.");
            all.put(MONUMENT, shipped);
        }
        return all;
    }

    private static Map<String, Wreck> wrecks(ConfigurationNode node) {
        return wrecks(node, SHIPPED_WRECKS, "modules/poseidon.conf wrecks");
    }

    /**
     * The groups of templates {@code node} writes, each by name: templates, count, distance and loot. A group the
     * shipped ones name takes what it leaves out from there; one that cannot be read is left out, said.
     *
     * @param where the file and the place in it, for what is said
     */
    public static Map<String, Wreck> wrecks(ConfigurationNode node, Map<String, Wreck> shippedWrecks, String where) {
        if (node.virtual() || !node.isMap()) {
            return shippedWrecks;
        }
        Map<String, Wreck> wrecks = new LinkedHashMap<>();
        for (Map.Entry<Object, ? extends ConfigurationNode> entry :
                node.childrenMap().entrySet()) {
            String name = String.valueOf(entry.getKey()).trim();
            ConfigurationNode written = entry.getValue();
            Wreck shipped = shippedWrecks.getOrDefault(name, new Wreck(List.of(), 1, 24, List.of()));
            try {
                wrecks.put(
                        name,
                        new Wreck(
                                AcidIslandConfiguration.strings(written.node("templates"), shipped.templates()),
                                written.node("count").getInt(shipped.count()),
                                written.node("distance").getInt(shipped.distance()),
                                AcidIslandConfiguration.strings(written.node("loot"), shipped.loot())));
            } catch (IllegalArgumentException e) {
                LOGGER.warning(() -> where + " " + name + ": " + e.getMessage() + ". It is left out.");
            }
        }
        return wrecks;
    }
}
