package com.uxplima.uxmskyblock.bukkit.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.logging.Logger;

import org.bukkit.Material;

import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.serialize.SerializationException;

/**
 * The Brix game mode, as {@code modules/brix.conf} writes it: islands that are creative building plots,
 * with the ground they are laid on.
 *
 * @param enabled whether islands can be made as Brix plots
 * @param ground the ground a new plot is laid on
 * @param modes the game modes on a plot
 * @param rules what cannot happen to a player on a plot
 */
public record BrixConfiguration(boolean enabled, Ground ground, Modes modes, Rules rules) {

    private static final Logger LOGGER = Logger.getLogger(BrixConfiguration.class.getName());

    public BrixConfiguration {
        Objects.requireNonNull(ground, "ground must not be null");
        Objects.requireNonNull(modes, "modes must not be null");
        Objects.requireNonNull(rules, "rules must not be null");
    }

    /**
     * What cannot happen to a player on a plot.
     *
     * @param noDamage whether nothing hurts a player on a plot, but the void below it
     * @param noHunger whether nobody goes hungry on a plot
     */
    public record Rules(boolean noDamage, boolean noHunger, boolean noSpawning) {

        public static final Rules SHIPPED = new Rules(true, true, true);

        /** Rules where no creature comes of itself. */
        public Rules(boolean noDamage, boolean noHunger) {
            this(noDamage, noHunger, true);
        }
    }

    /**
     * The game modes on a plot.
     *
     * @param build the mode of the plot's own team while they are on it
     * @param visit the mode of everybody else on the plot
     * @param keepPermission the permission that leaves a player's mode and items alone; empty for none
     */
    public record Modes(org.bukkit.GameMode build, org.bukkit.GameMode visit, String keepPermission) {

        public static final Modes SHIPPED =
                new Modes(org.bukkit.GameMode.CREATIVE, org.bukkit.GameMode.ADVENTURE, "uxmskyblock.brix.keepmode");

        public Modes {
            Objects.requireNonNull(build, "build must not be null");
            Objects.requireNonNull(visit, "visit must not be null");
            Objects.requireNonNull(keepPermission, "keepPermission must not be null");
            if (build == org.bukkit.GameMode.SPECTATOR || visit == org.bukkit.GameMode.SPECTATOR) {
                throw new IllegalArgumentException("a plot is not built on or looked at in spectator");
            }
            if (visit == org.bukkit.GameMode.CREATIVE) {
                throw new IllegalArgumentException("a visitor does not look at a plot in creative");
            }
        }
    }

    /**
     * The flat ground under a new plot.
     *
     * @param radius how far the ground reaches from the centre, each way
     * @param layers the ground's layers, top first; none lays no ground and the plot is void
     */
    public record Ground(int radius, List<Material> layers) {

        /** The widest ground a plot is laid on. */
        public static final int MAX_RADIUS = 128;

        /** The most layers a ground has. */
        public static final int MAX_LAYERS = 64;

        public static final Ground SHIPPED = new Ground(
                32, List.of(Material.GRASS_BLOCK, Material.DIRT, Material.DIRT, Material.DIRT, Material.BEDROCK));

        public Ground {
            layers = List.copyOf(layers);
            if (radius < 0 || radius > MAX_RADIUS) {
                throw new IllegalArgumentException("the radius must be from 0 to " + MAX_RADIUS);
            }
            if (layers.size() > MAX_LAYERS) {
                throw new IllegalArgumentException("a ground has at most " + MAX_LAYERS + " layers");
            }
            for (Material layer : layers) {
                if (!layer.isBlock()) {
                    throw new IllegalArgumentException(layer + " is no block");
                }
            }
        }
    }

    public static BrixConfiguration defaultConfiguration() {
        return new BrixConfiguration(true, Ground.SHIPPED, Modes.SHIPPED, Rules.SHIPPED);
    }

    public static BrixConfiguration load(ConfigurationNode root) {
        ConfigurationNode written = root.node("ground");
        Ground ground;
        try {
            ground = new Ground(
                    written.node("radius").getInt(Ground.SHIPPED.radius()),
                    written.node("layers").virtual() ? Ground.SHIPPED.layers() : layers(written.node("layers")));
        } catch (IllegalArgumentException | SerializationException e) {
            LOGGER.warning(() -> "modules/brix.conf ground: " + e.getMessage() + ". The shipped ground is used.");
            ground = Ground.SHIPPED;
        }
        ConfigurationNode mode = root.node("modes");
        Modes modes;
        try {
            modes = new Modes(
                    gameMode(mode.node("build").getString(""), Modes.SHIPPED.build()),
                    gameMode(mode.node("visit").getString(""), Modes.SHIPPED.visit()),
                    mode.node("keep-permission")
                            .getString(Modes.SHIPPED.keepPermission())
                            .trim());
        } catch (IllegalArgumentException e) {
            LOGGER.warning(() -> "modules/brix.conf modes: " + e.getMessage() + ". The shipped modes are used.");
            modes = Modes.SHIPPED;
        }
        ConfigurationNode rule = root.node("rules");
        Rules rules = new Rules(
                rule.node("no-damage").getBoolean(Rules.SHIPPED.noDamage()),
                rule.node("no-hunger").getBoolean(Rules.SHIPPED.noHunger()),
                rule.node("no-spawning").getBoolean(Rules.SHIPPED.noSpawning()));
        return new BrixConfiguration(root.node("enabled").getBoolean(true), ground, modes, rules);
    }

    private static org.bukkit.GameMode gameMode(String written, org.bukkit.GameMode shipped) {
        if (written.isBlank()) {
            return shipped;
        }
        return org.bukkit.GameMode.valueOf(written.trim().toUpperCase(Locale.ROOT));
    }

    private static List<Material> layers(ConfigurationNode node) throws SerializationException {
        List<Material> layers = new ArrayList<>();
        for (String written : node.getList(String.class, List.of())) {
            Material material = Material.matchMaterial(written.trim().toUpperCase(Locale.ROOT));
            if (material == null || !material.isBlock()) {
                throw new IllegalArgumentException(written + " is no block");
            }
            layers.add(material);
        }
        return layers;
    }
}
