package com.uxplima.uxmskyblock.bukkit.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.logging.Logger;

import com.uxplima.uxmskyblock.core.domain.hazard.AcidRules;
import org.spongepowered.configurate.ConfigurationNode;

/**
 * {@code modules/acidisland.conf}: whether islands can be AcidIsland islands, the sea around them, how
 * much the sea and the rain hurt, and how water is made clean.
 *
 * @param waterProtection the effects, by name, that keep a player safe in the sea
 * @param waterEffects the effects the sea gives, each written {@code name:amplifier:seconds}
 * @param seaLife what the sea does to items and creatures in it
 * @param armour the full sets of armour that keep the acid off
 * @param cleanWater what a bottle of clean water does for whoever drinks it
 * @param look the biome laid over the sea and the colours of the plugin's own
 */
public record AcidIslandConfiguration(
        boolean enabled,
        Sea sea,
        AcidRules rules,
        List<String> waterProtection,
        List<String> waterEffects,
        Purification purification,
        SeaLife seaLife,
        Armour armour,
        CleanWater cleanWater,
        Look look) {

    private static final Logger LOGGER = Logger.getLogger(AcidIslandConfiguration.class.getName());

    /**
     * The sea an island is made in.
     *
     * @param belowIsland how many blocks below the island's start height the surface stands
     * @param depth how many blocks of water, above the floor
     * @param radius how far from the island's centre the sea reaches; keep it inside half the grid spacing
     * @param floor the block the sea stands on; one that falls, such as sand, is replaced by sandstone
     * @param vents the sulfur vents on the floor
     * @param geyserChance the chance that one column of the floor is a geyser, which throws a swimmer up
     */
    public record Sea(int belowIsland, int depth, int radius, String floor, Vents vents, double geyserChance) {

        public static final Sea SHIPPED = new Sea(2, 4, 64, "SANDSTONE", Vents.SHIPPED, 0.004);

        public Sea {
            Objects.requireNonNull(floor, "floor must not be null");
            Objects.requireNonNull(vents, "vents must not be null");
            if (depth < 1 || radius < 1 || belowIsland < 0) {
                throw new IllegalArgumentException("the sea needs a depth and a radius of at least 1");
            }
            if (geyserChance < 0 || geyserChance + vents.chance() > 1) {
                throw new IllegalArgumentException("the vent and geyser chances must be between 0 and 1 together");
            }
        }

        /** A sea with no vents and no geysers. */
        public Sea(int belowIsland, int depth, int radius, String floor) {
            this(belowIsland, depth, radius, floor, Vents.NONE, 0);
        }
    }

    /**
     * Sulfur vents: magma on the sea floor that pulls a swimmer down, and fumes that reach whoever is
     * near one.
     *
     * @param chance the chance that one column of the floor is a vent
     * @param reach how many blocks across the fumes of a vent reach, sideways
     * @param effects the effects the fumes give, each written {@code name:amplifier:seconds}
     */
    public record Vents(double chance, int reach, List<String> effects) {

        public static final Vents NONE = new Vents(0, 0, List.of());
        public static final Vents SHIPPED = new Vents(0.004, 3, List.of("nausea:0:6", "poison:1:3"));

        public Vents {
            effects = List.copyOf(effects);
            if (chance < 0 || chance > 1 || reach < 0 || reach > 8) {
                throw new IllegalArgumentException("a vent's chance is between 0 and 1 and its reach between 0 and 8");
            }
        }
    }

    /**
     * What the sea does to what lies and swims in it, around each player on an AcidIsland island at each check.
     *
     * @param reach how many blocks around a player are looked at, 0 for none
     * @param itemsDissolve whether an item lying in the sea dissolves
     * @param itemsAfter how long an item lies in the sea before it dissolves
     * @param creaturesBurn whether a creature in the sea is hurt
     * @param creatureDamage the health taken from a creature in the sea at each check
     * @param spared the creatures, by type, the sea does not hurt
     */
    public record SeaLife(
            int reach,
            boolean itemsDissolve,
            Duration itemsAfter,
            boolean creaturesBurn,
            double creatureDamage,
            List<String> spared) {

        public static final SeaLife SHIPPED = new SeaLife(
                32, true, Duration.ofSeconds(10), true, 2.0, List.of("drowned", "guardian", "elder_guardian"));

        public static final SeaLife NONE = new SeaLife(0, false, Duration.ofSeconds(10), false, 0, List.of());

        public SeaLife {
            Objects.requireNonNull(itemsAfter, "itemsAfter must not be null");
            spared = List.copyOf(spared);
            if (reach < 0 || reach > 64) {
                throw new IllegalArgumentException("the reach around a player is between 0 and 64 blocks");
            }
            if (itemsAfter.isNegative() || creatureDamage < 0) {
                throw new IllegalArgumentException("a wait and a damage are never below 0");
            }
        }
    }

    /**
     * Full sets of armour that keep the acid off a player wearing all four pieces.
     *
     * @param sets the materials of the sets, as the start of each piece's name: {@code netherite} for
     *     {@code netherite_helmet} and the rest; none keeps nothing off
     * @param sea whether such a set keeps the sea off
     * @param rain whether such a set keeps the rain off
     * @param wear how much durability each piece loses at each check it keeps acid off, 0 for none
     */
    public record Armour(List<String> sets, boolean sea, boolean rain, int wear) {

        public static final Armour SHIPPED = new Armour(List.of("netherite"), true, true, 1);

        public static final Armour NONE = new Armour(List.of(), false, false, 0);

        public Armour {
            sets = sets.stream()
                    .map(set -> set.trim().toLowerCase(java.util.Locale.ROOT).replace("minecraft:", ""))
                    .filter(set -> !set.isEmpty())
                    .toList();
            if (wear < 0) {
                throw new IllegalArgumentException("wear is never below 0: " + wear);
            }
        }
    }

    /**
     * What a bottle of clean water drunk on an AcidIsland island does.
     *
     * @param heals the health it gives back, 0 for none; a heart is 2
     * @param effects the effects it gives, each written {@code name:amplifier:seconds}
     */
    public record CleanWater(double heals, List<String> effects) {

        public static final CleanWater SHIPPED = new CleanWater(4.0, List.of());

        public static final CleanWater NONE = new CleanWater(0, List.of());

        public CleanWater {
            effects = List.copyOf(effects);
            if (heals < 0 || heals > 1024) {
                throw new IllegalArgumentException("clean water heals from 0 to 1024: " + heals);
            }
        }
    }

    /**
     * How the sea looks: the biome painted over it, from its floor to the sky, and the colours of the plugin's own
     * sulfur sea biome, which a data pack the plugin writes at start carries.
     *
     * @param biome the biome painted over the sea, as a key; blank leaves the biome the world made
     * @param water the colour of the water, written {@code #rrggbb}
     * @param waterFog the colour seen under the water
     * @param fog the colour of the fog above it
     * @param sky the colour of the sky above it
     */
    public record Look(String biome, String water, String waterFog, String fog, String sky) {

        /** The key of the plugin's own sulfur sea biome. */
        public static final String SULFUR_SEA = "uxmskyblock:sulfur_sea";

        public static final Look SHIPPED = new Look(SULFUR_SEA, "#ffe66d", "#565f6b", "#fff3a8", "#e8ecf5");

        public Look {
            biome = biome.strip().toLowerCase(java.util.Locale.ROOT);
            water = colour("water", water);
            waterFog = colour("water-fog", waterFog);
            fog = colour("fog", fog);
            sky = colour("sky", sky);
            if (!biome.isEmpty() && org.bukkit.NamespacedKey.fromString(biome) == null) {
                throw new IllegalArgumentException("the biome \"" + biome + "\" is no key");
            }
        }

        private static String colour(String name, String written) {
            String colour = written.strip().toLowerCase(java.util.Locale.ROOT);
            if (!colour.matches("#[0-9a-f]{6}")) {
                throw new IllegalArgumentException(name + " is written #rrggbb, not \"" + written + "\"");
            }
            return colour;
        }

        /** Whether the sea is painted at all. */
        public boolean paints() {
            return !biome.isEmpty();
        }
    }

    /** How acid water is made clean. Rain a cauldron catches is clean already. */
    public record Purification(boolean furnace, boolean brewingWithCoal) {}

    public AcidIslandConfiguration {
        Objects.requireNonNull(sea, "sea must not be null");
        Objects.requireNonNull(rules, "rules must not be null");
        Objects.requireNonNull(purification, "purification must not be null");
        Objects.requireNonNull(seaLife, "seaLife must not be null");
        Objects.requireNonNull(armour, "armour must not be null");
        Objects.requireNonNull(cleanWater, "cleanWater must not be null");
        Objects.requireNonNull(look, "look must not be null");
        waterProtection = List.copyOf(waterProtection);
        waterEffects = List.copyOf(waterEffects);
    }

    public static AcidIslandConfiguration defaultConfiguration() {
        return new AcidIslandConfiguration(
                true,
                Sea.SHIPPED,
                AcidRules.shipped(),
                List.of("water_breathing"),
                List.of("poison:0:3"),
                new Purification(true, true),
                SeaLife.SHIPPED,
                Armour.SHIPPED,
                CleanWater.SHIPPED,
                Look.SHIPPED);
    }

    public static AcidIslandConfiguration load(ConfigurationNode root) {
        AcidIslandConfiguration shipped = defaultConfiguration();
        Sea sea;
        ConfigurationNode seaNode = root.node("sea");
        try {
            ConfigurationNode ventNode = seaNode.node("vents");
            Vents vents = new Vents(
                    ventNode.node("chance").getDouble(Vents.SHIPPED.chance()),
                    ventNode.node("reach").getInt(Vents.SHIPPED.reach()),
                    strings(ventNode.node("effects"), Vents.SHIPPED.effects()));
            sea = new Sea(
                    seaNode.node("below-island").getInt(Sea.SHIPPED.belowIsland()),
                    seaNode.node("depth").getInt(Sea.SHIPPED.depth()),
                    seaNode.node("radius").getInt(Sea.SHIPPED.radius()),
                    seaNode.node("floor").getString(Sea.SHIPPED.floor()).trim(),
                    vents,
                    seaNode.node("geysers", "chance").getDouble(Sea.SHIPPED.geyserChance()));
        } catch (IllegalArgumentException e) {
            LOGGER.warning(() -> "modules/acidisland.conf sea: " + e.getMessage() + ". The shipped sea is used.");
            sea = Sea.SHIPPED;
        }
        ConfigurationNode hazard = root.node("hazard");
        AcidRules rules;
        try {
            rules = new AcidRules(
                    hazard.node("water-damage").getDouble(shipped.rules().waterDamage()),
                    hazard.node("rain-damage").getDouble(shipped.rules().rainDamage()),
                    hazard.node("helmet-blocks-rain").getBoolean(shipped.rules().helmetBlocksRain()),
                    durationOf(
                            hazard.node("check-every").getString(""),
                            shipped.rules().checkEvery()));
        } catch (IllegalArgumentException e) {
            LOGGER.warning(
                    () -> "modules/acidisland.conf hazard: " + e.getMessage() + ". The shipped numbers are used.");
            rules = shipped.rules();
        }
        ConfigurationNode purification = root.node("purification");
        return new AcidIslandConfiguration(
                root.node("enabled").getBoolean(true),
                sea,
                rules,
                strings(hazard.node("water-protection"), shipped.waterProtection()),
                strings(hazard.node("water-effects"), shipped.waterEffects()),
                new Purification(
                        purification.node("furnace").getBoolean(true),
                        purification.node("brewing-with-coal").getBoolean(true)),
                seaLife(root.node("sea-life")),
                armour(hazard.node("armour")),
                cleanWater(root.node("clean-water")),
                look(seaNode.node("look")));
    }

    /** How the sea looks, as {@code sea look} writes it; what cannot be read is the shipped, said. */
    public static Look look(ConfigurationNode node) {
        Look shipped = Look.SHIPPED;
        try {
            return new Look(
                    node.node("biome").getString(shipped.biome()),
                    node.node("water").getString(shipped.water()),
                    node.node("water-fog").getString(shipped.waterFog()),
                    node.node("fog").getString(shipped.fog()),
                    node.node("sky").getString(shipped.sky()));
        } catch (IllegalArgumentException e) {
            LOGGER.warning(() -> "modules/acidisland.conf sea look: " + e.getMessage() + ". The shipped look is used.");
            return shipped;
        }
    }

    /** What clean water does, as {@code clean-water} writes it; what cannot be read is the shipped. */
    private static CleanWater cleanWater(ConfigurationNode node) {
        try {
            return new CleanWater(
                    node.node("heals").getDouble(CleanWater.SHIPPED.heals()),
                    strings(node.node("effects"), CleanWater.SHIPPED.effects()));
        } catch (IllegalArgumentException e) {
            LOGGER.warning(
                    () -> "modules/acidisland.conf clean-water: " + e.getMessage() + ". The shipped one is used.");
            return CleanWater.SHIPPED;
        }
    }

    /** The armour that keeps the acid off, as {@code hazard armour} writes it; what cannot be read is the shipped. */
    private static Armour armour(ConfigurationNode node) {
        Armour shipped = Armour.SHIPPED;
        try {
            return new Armour(
                    strings(node.node("sets"), shipped.sets()),
                    node.node("sea").getBoolean(shipped.sea()),
                    node.node("rain").getBoolean(shipped.rain()),
                    node.node("wear").getInt(shipped.wear()));
        } catch (IllegalArgumentException e) {
            LOGGER.warning(
                    () -> "modules/acidisland.conf hazard armour: " + e.getMessage() + ". The shipped armour is used.");
            return shipped;
        }
    }

    /** What the sea does to items and creatures, as {@code sea-life} writes it; what cannot be read is the shipped. */
    private static SeaLife seaLife(ConfigurationNode node) {
        SeaLife shipped = SeaLife.SHIPPED;
        try {
            ConfigurationNode items = node.node("items");
            ConfigurationNode creatures = node.node("creatures");
            return new SeaLife(
                    node.node("reach").getInt(shipped.reach()),
                    items.node("dissolve").getBoolean(shipped.itemsDissolve()),
                    durationOf(items.node("after").getString(""), shipped.itemsAfter()),
                    creatures.node("burn").getBoolean(shipped.creaturesBurn()),
                    creatures.node("damage").getDouble(shipped.creatureDamage()),
                    strings(creatures.node("spared"), shipped.spared()));
        } catch (IllegalArgumentException e) {
            LOGGER.warning(() -> "modules/acidisland.conf sea-life: " + e.getMessage() + ". The shipped one is used.");
            return shipped;
        }
    }

    /** The strings of a list, or the fallback when the list is not written at all. */
    static List<String> strings(ConfigurationNode node, List<String> fallback) {
        if (node.virtual()) {
            return fallback;
        }
        List<String> read = new ArrayList<>();
        for (ConfigurationNode child : node.childrenList()) {
            String value = child.getString("").trim();
            if (!value.isEmpty()) {
                read.add(value);
            }
        }
        return read;
    }

    /** A span written as {@code 500ms}, {@code 2s}, {@code 5m}, {@code 1h} or a plain number of seconds. */
    static Duration durationOf(String written, Duration fallback) {
        String raw = written.trim().toLowerCase(java.util.Locale.ROOT);
        if (raw.isEmpty()) {
            return fallback;
        }
        try {
            if (raw.endsWith("ms")) {
                return Duration.ofMillis(
                        Long.parseLong(raw.substring(0, raw.length() - 2).trim()));
            }
            if (raw.endsWith("s")) {
                return Duration.ofMillis(Math.round(
                        Double.parseDouble(raw.substring(0, raw.length() - 1).trim()) * 1000));
            }
            if (raw.endsWith("m")) {
                return Duration.ofMillis(Math.round(
                        Double.parseDouble(raw.substring(0, raw.length() - 1).trim()) * 60_000));
            }
            if (raw.endsWith("h")) {
                return Duration.ofMillis(Math.round(
                        Double.parseDouble(raw.substring(0, raw.length() - 1).trim()) * 3_600_000));
            }
            return Duration.ofMillis(Math.round(Double.parseDouble(raw) * 1000));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
