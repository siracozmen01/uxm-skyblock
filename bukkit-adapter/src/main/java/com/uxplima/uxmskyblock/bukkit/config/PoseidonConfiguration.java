package com.uxplima.uxmskyblock.bukkit.config;

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
        boolean enabled, Ocean ocean, PoseidonRules rules, List<String> waterEffects, Map<String, Wreck> wrecks) {

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

    public PoseidonConfiguration {
        Objects.requireNonNull(ocean, "ocean must not be null");
        Objects.requireNonNull(rules, "rules must not be null");
        waterEffects = List.copyOf(waterEffects);
        wrecks = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(wrecks));
    }

    /** The shipped ocean, hazard and wrecks, with Poseidon on or off. */
    public PoseidonConfiguration(boolean enabled, Ocean ocean) {
        this(enabled, ocean, PoseidonRules.shipped(), SHIPPED_WATER_EFFECTS, SHIPPED_WRECKS);
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
                wrecks(root.node("wrecks")));
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
