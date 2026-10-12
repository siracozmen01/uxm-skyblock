package com.uxplima.uxmskyblock.bukkit.config;

import java.util.List;
import java.util.Objects;
import java.util.logging.Logger;

import org.spongepowered.configurate.ConfigurationNode;

/**
 * {@code modules/strangerrealms.conf}: whether islands can be StrangerRealms islands, how the Upside
 * Down mirrors their land, and what it makes of the creatures born in it.
 */
public record StrangerRealmsConfiguration(
        boolean enabled,
        UpsideDown upsideDown,
        Mobs mobs,
        Glimmer glimmer,
        Compass compass,
        com.uxplima.uxmskyblock.core.domain.stranger.ClaimGrowth claim,
        Border border) {

    private static final Logger LOGGER = Logger.getLogger(StrangerRealmsConfiguration.class.getName());

    /**
     * The Upside Down, which takes the place of the Nether and mirrors the island's land.
     *
     * @param radius how far from the island's centre the land is mirrored, in blocks
     * @param palette how the mirror distresses what it copies, each rule written {@code FROM:TO}
     * @param lateCopy the blocks a mirror laid after the island was made copies: a name, or a name with one
     *     {@code *}. Such land may carry what players put on it, so everything else is laid as air.
     */
    public record UpsideDown(int radius, List<String> palette, List<String> lateCopy) {

        /** The land a mirror laid late copies as shipped: the ground and what grows on it, no ore, nothing made. */
        public static final List<String> NATURAL_GROUND = List.of(
                "STONE",
                "DEEPSLATE",
                "TUFF",
                "CALCITE",
                "ANDESITE",
                "DIORITE",
                "GRANITE",
                "DIRT",
                "COARSE_DIRT",
                "ROOTED_DIRT",
                "GRASS_BLOCK",
                "PODZOL",
                "MYCELIUM",
                "MUD",
                "CLAY",
                "SAND",
                "RED_SAND",
                "GRAVEL",
                "SANDSTONE",
                "RED_SANDSTONE",
                "TERRACOTTA",
                "SNOW",
                "SNOW_BLOCK",
                "ICE",
                "PACKED_ICE",
                "WATER",
                "LAVA",
                "BEDROCK",
                "*_LOG",
                "*_LEAVES",
                "SHORT_GRASS",
                "TALL_GRASS",
                "FERN",
                "LARGE_FERN",
                "DEAD_BUSH",
                "POPPY",
                "DANDELION",
                "MOSS_BLOCK",
                "SEAGRASS",
                "TALL_SEAGRASS",
                "KELP",
                "KELP_PLANT",
                "SUGAR_CANE",
                "VINE");

        public static final UpsideDown SHIPPED = new UpsideDown(
                48,
                List.of(
                        "GRASS_BLOCK:MYCELIUM",
                        "DIRT:COARSE_DIRT",
                        "DIRT_PATH:COARSE_DIRT",
                        "SHORT_GRASS:DEAD_BUSH",
                        "FERN:DEAD_BUSH",
                        "TALL_GRASS:AIR",
                        "LARGE_FERN:AIR",
                        "*_LEAVES:AIR",
                        "*_LOG:STRIPPED_DARK_OAK_LOG",
                        "*_WOOD:STRIPPED_DARK_OAK_WOOD",
                        "POPPY:WITHER_ROSE",
                        "DANDELION:WITHER_ROSE",
                        "SAND:SOUL_SAND"),
                NATURAL_GROUND);

        public UpsideDown {
            palette = List.copyOf(palette);
            lateCopy = List.copyOf(lateCopy);
            if (radius < 0) {
                throw new IllegalArgumentException("the radius must not be negative");
            }
        }

        /** The Upside Down with the shipped ground a late mirror copies. */
        public UpsideDown(int radius, List<String> palette) {
            this(radius, palette, NATURAL_GROUND);
        }
    }

    /**
     * What the Upside Down makes of a creature born in it, on a StrangerRealms island's land.
     *
     * @param reasons the ways of being born that are turned, such as {@code NATURAL} or {@code SPAWNER}
     * @param turn each rule written {@code FROM:TO}, a creature or a name with one {@code *}, turned into
     *     another creature, or into {@code NONE} to keep it from being born at all
     * @param effects what every creature born there carries, each written {@code name:amplifier:seconds}
     */
    public record Mobs(List<String> reasons, List<String> turn, List<String> effects) {

        public static final Mobs SHIPPED = new Mobs(
                List.of("NATURAL", "CHUNK_GEN", "SPAWNER"),
                List.of(
                        "PIGLIN:ZOMBIE",
                        "PIGLIN_BRUTE:VINDICATOR",
                        "ZOMBIFIED_PIGLIN:HUSK",
                        "HOGLIN:ZOGLIN",
                        "MAGMA_CUBE:SLIME",
                        "GHAST:PHANTOM",
                        "WITHER_SKELETON:STRAY",
                        "BLAZE:VEX",
                        "STRIDER:NONE"),
                List.of("speed:0:3600", "resistance:0:3600"));

        public Mobs {
            reasons = List.copyOf(reasons);
            turn = List.copyOf(turn);
            effects = List.copyOf(effects);
        }
    }

    /**
     * The glimmer: a light set on a StrangerRealms island's land shines through at the same place in
     * the Upside Down, and the other way round, while it stands.
     *
     * @param enabled whether lights glimmer across at all
     * @param lights the blocks that are lights, each a name or a name with one {@code *}
     * @param level how bright the glimmer is, from 1 to 15
     */
    public record Glimmer(boolean enabled, List<String> lights, int level) {

        public static final Glimmer SHIPPED = new Glimmer(
                true,
                List.of(
                        "TORCH",
                        "WALL_TORCH",
                        "SOUL_TORCH",
                        "SOUL_WALL_TORCH",
                        "LANTERN",
                        "SOUL_LANTERN",
                        "GLOWSTONE",
                        "SEA_LANTERN",
                        "SHROOMLIGHT",
                        "JACK_O_LANTERN",
                        "*_CANDLE",
                        "CANDLE",
                        "REDSTONE_LAMP"),
                12);

        public Glimmer {
            lights = List.copyOf(lights);
            if (level < 1 || level > 15) {
                throw new IllegalArgumentException("the level is from 1 to 15");
            }
        }
    }

    /**
     * The warped compass: held on a StrangerRealms island, it points at the nearest player on the other
     * side of the veil, at the same place on this side.
     *
     * @param enabled whether the compass can be made and points at all
     * @param checkEvery how often the compass turns to where the players are
     * @param ingredients what a crafting grid holds, in any order, to make one
     */
    public record Compass(boolean enabled, java.time.Duration checkEvery, List<String> ingredients) {

        public static final Compass SHIPPED =
                new Compass(true, java.time.Duration.ofSeconds(1), List.of("COMPASS", "WARPED_FUNGUS"));

        public Compass {
            Objects.requireNonNull(checkEvery, "checkEvery must not be null");
            ingredients = List.copyOf(ingredients);
            if (checkEvery.toMillis() < 50 || ingredients.isEmpty() || ingredients.size() > 9) {
                throw new IllegalArgumentException(
                        "check-every must be at least one tick, and the ingredients one to nine");
            }
        }
    }

    public StrangerRealmsConfiguration {
        Objects.requireNonNull(upsideDown, "upsideDown must not be null");
        Objects.requireNonNull(mobs, "mobs must not be null");
        Objects.requireNonNull(glimmer, "glimmer must not be null");
        Objects.requireNonNull(compass, "compass must not be null");
        Objects.requireNonNull(claim, "claim must not be null");
        Objects.requireNonNull(border, "border must not be null");
    }

    /**
     * The border of the StrangerRealms land, which grows as islands are made anywhere on the network.
     *
     * @param enabled whether the plugin sets the border at all
     * @param checkEvery how often the furthest island is read and the border moved
     * @param centerX where the border is centred, which is the centre of the island grid
     * @param centerZ where the border is centred, which is the centre of the island grid
     * @param rule how wide the border is for the furthest island
     * @param transition how long the border takes to move to a new width
     * @param worlds the worlds the border is set in; empty means every world only StrangerRealms
     *     presets make islands in
     */
    public record Border(
            boolean enabled,
            java.time.Duration checkEvery,
            double centerX,
            double centerZ,
            com.uxplima.uxmskyblock.core.domain.stranger.RealmBorder rule,
            java.time.Duration transition,
            List<String> worlds) {

        public static final Border SHIPPED = new Border(
                true,
                java.time.Duration.ofMinutes(1),
                0,
                0,
                com.uxplima.uxmskyblock.core.domain.stranger.RealmBorder.SHIPPED,
                java.time.Duration.ofSeconds(30),
                List.of());

        public Border {
            Objects.requireNonNull(checkEvery, "checkEvery must not be null");
            Objects.requireNonNull(rule, "rule must not be null");
            Objects.requireNonNull(transition, "transition must not be null");
            worlds = List.copyOf(worlds);
            if (checkEvery.toMillis() < 50 || transition.isNegative()) {
                throw new IllegalArgumentException(
                        "check-every must be at least one tick and the transition must not be negative");
            }
        }
    }

    public static StrangerRealmsConfiguration defaultConfiguration() {
        return new StrangerRealmsConfiguration(
                true,
                UpsideDown.SHIPPED,
                Mobs.SHIPPED,
                Glimmer.SHIPPED,
                Compass.SHIPPED,
                com.uxplima.uxmskyblock.core.domain.stranger.ClaimGrowth.SHIPPED,
                Border.SHIPPED);
    }

    public static StrangerRealmsConfiguration load(ConfigurationNode root) {
        ConfigurationNode node = root.node("upside-down");
        UpsideDown upsideDown;
        try {
            upsideDown = new UpsideDown(
                    node.node("radius").getInt(UpsideDown.SHIPPED.radius()),
                    AcidIslandConfiguration.strings(node.node("palette"), UpsideDown.SHIPPED.palette()),
                    AcidIslandConfiguration.strings(node.node("late-copy"), UpsideDown.SHIPPED.lateCopy()));
        } catch (IllegalArgumentException e) {
            LOGGER.warning(() -> "modules/strangerrealms.conf upside-down: " + e.getMessage()
                    + ". The shipped Upside Down is used.");
            upsideDown = UpsideDown.SHIPPED;
        }
        ConfigurationNode mobs = root.node("mobs");
        ConfigurationNode written = root.node("glimmer");
        Glimmer glimmer;
        try {
            glimmer = new Glimmer(
                    written.node("enabled").getBoolean(Glimmer.SHIPPED.enabled()),
                    AcidIslandConfiguration.strings(written.node("lights"), Glimmer.SHIPPED.lights()),
                    written.node("level").getInt(Glimmer.SHIPPED.level()));
        } catch (IllegalArgumentException e) {
            LOGGER.warning(
                    () -> "modules/strangerrealms.conf glimmer: " + e.getMessage() + ". The shipped glimmer is used.");
            glimmer = Glimmer.SHIPPED;
        }
        ConfigurationNode compassNode = root.node("warped-compass");
        Compass compass;
        try {
            compass = new Compass(
                    compassNode.node("enabled").getBoolean(Compass.SHIPPED.enabled()),
                    AcidIslandConfiguration.durationOf(
                            compassNode.node("check-every").getString(""), Compass.SHIPPED.checkEvery()),
                    AcidIslandConfiguration.strings(compassNode.node("ingredients"), Compass.SHIPPED.ingredients()));
        } catch (IllegalArgumentException e) {
            LOGGER.warning(() ->
                    "modules/strangerrealms.conf warped-compass: " + e.getMessage() + ". The shipped compass is used.");
            compass = Compass.SHIPPED;
        }
        return new StrangerRealmsConfiguration(
                root.node("enabled").getBoolean(true),
                upsideDown,
                new Mobs(
                        AcidIslandConfiguration.strings(mobs.node("reasons"), Mobs.SHIPPED.reasons()),
                        AcidIslandConfiguration.strings(mobs.node("turn"), Mobs.SHIPPED.turn()),
                        AcidIslandConfiguration.strings(mobs.node("effects"), Mobs.SHIPPED.effects())),
                glimmer,
                compass,
                claim(root.node("claim")),
                border(root.node("border")));
    }

    private static Border border(ConfigurationNode node) {
        Border shipped = Border.SHIPPED;
        com.uxplima.uxmskyblock.core.domain.stranger.RealmBorder rule = shipped.rule();
        try {
            return new Border(
                    node.node("enabled").getBoolean(shipped.enabled()),
                    AcidIslandConfiguration.durationOf(node.node("check-every").getString(""), shipped.checkEvery()),
                    node.node("center-x").getDouble(shipped.centerX()),
                    node.node("center-z").getDouble(shipped.centerZ()),
                    new com.uxplima.uxmskyblock.core.domain.stranger.RealmBorder(
                            node.node("margin").getInt(rule.margin()),
                            node.node("minimum").getInt(rule.minimum()),
                            node.node("maximum").getInt(rule.maximum())),
                    AcidIslandConfiguration.durationOf(node.node("transition").getString(""), shipped.transition()),
                    AcidIslandConfiguration.strings(node.node("worlds"), shipped.worlds()));
        } catch (IllegalArgumentException e) {
            LOGGER.warning(
                    () -> "modules/strangerrealms.conf border: " + e.getMessage() + ". The shipped border is used.");
            return shipped;
        }
    }

    private static com.uxplima.uxmskyblock.core.domain.stranger.ClaimGrowth claim(ConfigurationNode node) {
        com.uxplima.uxmskyblock.core.domain.stranger.ClaimGrowth shipped =
                com.uxplima.uxmskyblock.core.domain.stranger.ClaimGrowth.SHIPPED;
        try {
            return new com.uxplima.uxmskyblock.core.domain.stranger.ClaimGrowth(
                    node.node("per-member").getInt(shipped.perMember()),
                    node.node("max-radius").getInt(shipped.maxRadius()));
        } catch (IllegalArgumentException e) {
            LOGGER.warning(
                    () -> "modules/strangerrealms.conf claim: " + e.getMessage() + ". The shipped claim is used.");
            return shipped;
        }
    }
}
