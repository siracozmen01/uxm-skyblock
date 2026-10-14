package com.uxplima.uxmskyblock.bukkit.config;

import java.time.Duration;
import java.util.List;
import java.util.Map;
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
 * @param buried the structures buried in the sea's floor
 * @param tide how the sea rises and falls
 * @param offerings what a vent gives back for the items it takes
 * @param between the sea that runs on between islands, in a world of their own
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
        Look look,
        Buried buried,
        Tide tide,
        Offerings offerings,
        Between between) {

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
    public record Sea(int belowIsland, int depth, int radius, String floor, Vents vents, double geyserChance, Bed bed) {

        public static final Sea SHIPPED = new Sea(2, 4, 64, "SANDSTONE", Vents.SHIPPED, 0.004, Bed.SHIPPED);

        public Sea {
            Objects.requireNonNull(floor, "floor must not be null");
            Objects.requireNonNull(vents, "vents must not be null");
            Objects.requireNonNull(bed, "bed must not be null");
            if (depth < 1 || radius < 1 || belowIsland < 0) {
                throw new IllegalArgumentException("the sea needs a depth and a radius of at least 1");
            }
            if (geyserChance < 0 || geyserChance + vents.chance() > 1) {
                throw new IllegalArgumentException("the vent and geyser chances must be between 0 and 1 together");
            }
        }

        /** A sea on a flat floor of one block. */
        public Sea(int belowIsland, int depth, int radius, String floor, Vents vents, double geyserChance) {
            this(belowIsland, depth, radius, floor, vents, geyserChance, Bed.FLAT);
        }

        /** A sea with no vents and no geysers, on a flat floor of one block. */
        public Sea(int belowIsland, int depth, int radius, String floor) {
            this(belowIsland, depth, radius, floor, Vents.NONE, 0);
        }
    }

    /**
     * The sea's floor: what it is made of, how thick it is, and how far it rises in mounds.
     *
     * @param blocks the blocks of the floor, each written {@code BLOCK weight}; each column takes one by its weight,
     *     and none takes the sea's {@code floor}
     * @param thickness how many blocks thick the floor is
     * @param mounds how many blocks the floor rises at most, in gentle mounds; always a block of water stays above
     */
    public record Bed(List<String> blocks, int thickness, int mounds) {

        public static final Bed SHIPPED =
                new Bed(List.of("SANDSTONE 6", "SMOOTH_SANDSTONE 2", "TUFF 2", "CLAY 1", "YELLOW_TERRACOTTA 1"), 3, 2);

        /** One block of the sea's floor, flat. */
        public static final Bed FLAT = new Bed(List.of(), 1, 0);

        public Bed {
            blocks = List.copyOf(blocks);
            if (thickness < 1 || thickness > 16 || mounds < 0 || mounds > 15) {
                throw new IllegalArgumentException("a floor is 1 to 16 thick and rises 0 to 15");
            }
        }
    }

    /**
     * Structures buried in the sea's floor around the island, laid as Poseidon lays its wrecks.
     *
     * @param sink how many blocks deep each sits in the floor
     * @param groups the groups of templates, by name
     */
    public record Buried(int sink, Map<String, PoseidonConfiguration.Wreck> groups) {

        public static final Buried SHIPPED = new Buried(
                2,
                Map.of(
                        "ruins",
                        new PoseidonConfiguration.Wreck(
                                List.of(
                                        "minecraft:underwater_ruin/cracked_1",
                                        "minecraft:underwater_ruin/cracked_2",
                                        "minecraft:underwater_ruin/cracked_3",
                                        "minecraft:underwater_ruin/cracked_4",
                                        "minecraft:underwater_ruin/cracked_5",
                                        "minecraft:underwater_ruin/cracked_6",
                                        "minecraft:underwater_ruin/cracked_7",
                                        "minecraft:underwater_ruin/cracked_8"),
                                4,
                                34,
                                List.of("minecraft:chests/underwater_ruin_small")),
                        "temple",
                        new PoseidonConfiguration.Wreck(
                                List.of(
                                        "minecraft:underwater_ruin/big_cracked_1",
                                        "minecraft:underwater_ruin/big_cracked_2",
                                        "minecraft:underwater_ruin/big_cracked_3",
                                        "minecraft:underwater_ruin/big_cracked_8"),
                                1,
                                48,
                                List.of("minecraft:chests/underwater_ruin_big"))));

        public static final Buried NONE = new Buried(0, Map.of());

        public Buried {
            groups = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(groups));
            if (sink < 0 || sink > 16) {
                throw new IllegalArgumentException("a structure sinks 0 to 16 blocks: " + sink);
            }
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

    /**
     * The acid tide and how often it is looked at.
     *
     * @param checkEvery how often the sea is brought to the tide's level
     */
    public record Tide(com.uxplima.uxmskyblock.core.domain.hazard.AcidTide tide, Duration checkEvery) {

        public static final Tide SHIPPED = new Tide(
                new com.uxplima.uxmskyblock.core.domain.hazard.AcidTide(2, Duration.ofMinutes(20)),
                Duration.ofSeconds(10));

        public static final Tide STILL =
                new Tide(com.uxplima.uxmskyblock.core.domain.hazard.AcidTide.STILL, Duration.ofSeconds(10));

        public Tide {
            Objects.requireNonNull(tide, "tide must not be null");
            Objects.requireNonNull(checkEvery, "checkEvery must not be null");
            if (checkEvery.toSeconds() < 1) {
                throw new IllegalArgumentException("the tide is looked at once a second at most: " + checkEvery);
            }
        }
    }

    /**
     * What a vent gives back for an offering: items thrown into the sea that sink onto it.
     *
     * @param items the items it takes, by key, as {@code minecraft:bone}
     * @param takes how many of them one offering takes
     * @param loot the loot table one offering is answered from
     */
    public record Offering(List<String> items, int takes, String loot) {

        public Offering {
            items = items.stream()
                    .map(item -> item.strip().toLowerCase(java.util.Locale.ROOT))
                    .map(item -> item.contains(":") ? item : "minecraft:" + item)
                    .toList();
            loot = loot.strip();
            if (items.isEmpty() || loot.isEmpty() || takes < 1 || takes > 64) {
                throw new IllegalArgumentException("an offering names its items, takes 1 to 64 and names a loot table");
            }
        }

        /** Whether the offering takes the item of that key. */
        public boolean takes(String key) {
            return items.contains(key);
        }
    }

    /**
     * The offerings vents answer, the first that takes an item answering it.
     *
     * @param after how long an item lies in the sea on a vent before the vent takes it
     * @param list the offerings, in order
     */
    public record Offerings(Duration after, List<Offering> list) {

        public static final Offerings SHIPPED = new Offerings(
                Duration.ofSeconds(2),
                List.of(
                        new Offering(
                                List.of("minecraft:bone", "minecraft:rotten_flesh", "minecraft:spider_eye"),
                                8,
                                "minecraft:chests/underwater_ruin_small"),
                        new Offering(List.of("minecraft:gold_ingot"), 1, "minecraft:chests/buried_treasure"),
                        new Offering(
                                List.of("minecraft:diamond", "minecraft:emerald"),
                                1,
                                "minecraft:chests/underwater_ruin_big")));

        public static final Offerings NONE = new Offerings(Duration.ofSeconds(2), List.of());

        public Offerings {
            Objects.requireNonNull(after, "after must not be null");
            list = List.copyOf(list);
            if (after.isNegative()) {
                throw new IllegalArgumentException("an offering waits no less than nothing");
            }
        }

        /** The first offering that takes the item of that key. */
        public java.util.Optional<Offering> answering(String key) {
            return list.stream().filter(offering -> offering.takes(key)).findFirst();
        }
    }

    /**
     * A sea that runs on between islands: every chunk of {@code world} the server makes is laid with the acid sea at
     * {@code surface}, so the islands of that world stand in one sea and a boat goes from island to island, and all of
     * the world's water is acid, not just the water on an island.
     *
     * @param enabled whether the sea runs on between islands
     * @param world the world it runs through, one that holds AcidIsland islands only
     * @param surface the height of its surface, the same as each island's sea there
     */
    public record Between(boolean enabled, String world, int surface) {

        public static final Between SHIPPED = new Between(false, "", 98);

        public Between {
            world = world.strip();
            if (enabled && world.isEmpty()) {
                throw new IllegalArgumentException("a sea between islands names its world");
            }
        }

        /** Whether the sea runs on between islands in the world of that name. */
        public boolean runsThrough(String name) {
            return enabled && world.equals(name);
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
        Objects.requireNonNull(buried, "buried must not be null");
        Objects.requireNonNull(tide, "tide must not be null");
        Objects.requireNonNull(offerings, "offerings must not be null");
        Objects.requireNonNull(between, "between must not be null");
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
                Look.SHIPPED,
                Buried.SHIPPED,
                Tide.SHIPPED,
                Offerings.SHIPPED,
                Between.SHIPPED);
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

    public static AcidIslandConfiguration load(ConfigurationNode root) {
        return AcidIslandReader.load(root);
    }

    /** A span written as {@code 500ms}, {@code 2s}, {@code 5m}, {@code 1h}, {@code 3d} or a plain number of seconds. */
    static Duration durationOf(String written, Duration fallback) {
        return AcidIslandValues.durationOf(written, fallback);
    }

    /** The strings of a list, or the fallback when the list is not written at all. */
    static List<String> strings(ConfigurationNode node, List<String> fallback) {
        return AcidIslandValues.strings(node, fallback);
    }
}
