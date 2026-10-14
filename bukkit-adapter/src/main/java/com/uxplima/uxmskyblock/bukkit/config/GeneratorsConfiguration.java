package com.uxplima.uxmskyblock.bukkit.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import org.bukkit.Material;

import org.spongepowered.configurate.ConfigurationNode;

/**
 * Immutable configuration holder for cobblestone and stone generator ore generation rates by tier.
 */
public record GeneratorsConfiguration(boolean enabled, Map<Integer, Map<Material, Double>> tierRates) {

    public static final boolean DEFAULT_ENABLED = true;

    public GeneratorsConfiguration {
        Map<Integer, Map<Material, Double>> copy = new LinkedHashMap<>();
        if (tierRates != null) {
            for (Map.Entry<Integer, Map<Material, Double>> entry : tierRates.entrySet()) {
                copy.put(entry.getKey(), Collections.unmodifiableMap(new LinkedHashMap<>(entry.getValue())));
            }
        }
        tierRates = Collections.unmodifiableMap(copy);
    }

    /**
     * Determines which material should generate for the given tier based on the normalized random value [0.0, 1.0).
     *
     * @param tier generator tier level (0-based or 1-based)
     * @param randomValue random roll value in [0.0, 1.0)
     * @return selected Material
     */
    public Material roll(int tier, double randomValue) {
        return roll(tier, randomValue, 1.0);
    }

    /** The blocks a generator forms of its own, which an ore generator booster never makes likelier. */
    private static final java.util.Set<Material> FORMED =
            java.util.EnumSet.of(Material.COBBLESTONE, Material.STONE, Material.BASALT);

    /**
     * As {@link #roll(int, double)}, with every ore's weight times {@code oreBoost}: an ore generator booster turns
     * up the ores more often, and the stone the generator forms of its own as often as ever.
     */
    public Material roll(int tier, double randomValue, double oreBoost) {
        Map<Material, Double> written = ratesFor(tier);
        if (written == null) {
            return Material.COBBLESTONE;
        }
        double boost = Math.max(1.0, oreBoost);
        Map<Material, Double> rates = new java.util.LinkedHashMap<>();
        written.forEach((block, weight) -> rates.put(block, FORMED.contains(block) ? weight : weight * boost));

        double totalWeight = 0.0;
        for (double w : rates.values()) {
            if (w > 0.0) {
                totalWeight += w;
            }
        }
        if (totalWeight <= 0.0) {
            return Material.COBBLESTONE;
        }

        double target = Math.max(0.0, Math.min(0.999999, randomValue)) * totalWeight;
        double cumulative = 0.0;
        for (Map.Entry<Material, Double> entry : rates.entrySet()) {
            if (entry.getValue() <= 0.0) {
                continue;
            }
            cumulative += entry.getValue();
            if (target < cumulative) {
                return entry.getKey();
            }
        }

        return Material.COBBLESTONE;
    }

    /**
     * What a generator of {@code tier} turns up, each block with its share in percent, in the order the file
     * writes them: the same rates {@link #roll} draws from, so a window that shows them shows what the
     * generator does. A tier with no rates turns up cobblestone and nothing else.
     */
    public Map<Material, Double> shares(int tier) {
        Map<Material, Double> rates = ratesFor(tier);
        double total = rates == null
                ? 0.0
                : rates.values().stream()
                        .filter(w -> w > 0.0)
                        .mapToDouble(Double::doubleValue)
                        .sum();
        if (rates == null || total <= 0.0) {
            return Map.of(Material.COBBLESTONE, 100.0);
        }
        Map<Material, Double> shares = new LinkedHashMap<>();
        rates.forEach((material, weight) -> {
            if (weight > 0.0) {
                shares.put(material, weight / total * 100.0);
            }
        });
        return Collections.unmodifiableMap(shares);
    }

    /**
     * The rates of the highest tier the file writes at or below {@code tier}, or of the lowest one
     * when it writes none that low.
     *
     * <p>A tier the file does not write used to fall to tier 0, which is plain cobblestone. An island
     * that had bought the top generator tier and whose operator then took that tier out of the file
     * lost every ore it paid for, rather than keeping the best tier still there.
     */
    private @org.jspecify.annotations.Nullable Map<Material, Double> ratesFor(int tier) {
        Map<Material, Double> exact = tierRates.get(tier);
        if (exact != null && !exact.isEmpty()) {
            return exact;
        }
        Integer below = null;
        Integer lowest = null;
        for (Map.Entry<Integer, Map<Material, Double>> entry : tierRates.entrySet()) {
            if (entry.getValue().isEmpty()) {
                continue;
            }
            int written = entry.getKey();
            if (written <= tier && (below == null || written > below)) {
                below = written;
            }
            if (lowest == null || written < lowest) {
                lowest = written;
            }
        }
        Integer chosen = below != null ? below : lowest;
        return chosen == null ? null : tierRates.get(chosen);
    }

    public static GeneratorsConfiguration defaultConfiguration() {
        Map<Integer, Map<Material, Double>> rates = new LinkedHashMap<>();

        // Tier 0 (Base / un-upgraded)
        rates.put(0, Map.of(Material.COBBLESTONE, 1.0));

        // Tier 1
        Map<Material, Double> t1 = new LinkedHashMap<>();
        t1.put(Material.COBBLESTONE, 0.70);
        t1.put(Material.COAL_ORE, 0.20);
        t1.put(Material.IRON_ORE, 0.10);
        rates.put(1, t1);

        // Tier 2
        Map<Material, Double> t2 = new LinkedHashMap<>();
        t2.put(Material.COBBLESTONE, 0.55);
        t2.put(Material.COAL_ORE, 0.20);
        t2.put(Material.IRON_ORE, 0.15);
        t2.put(Material.GOLD_ORE, 0.06);
        t2.put(Material.LAPIS_ORE, 0.04);
        rates.put(2, t2);

        // Tier 3
        Map<Material, Double> t3 = new LinkedHashMap<>();
        t3.put(Material.COBBLESTONE, 0.40);
        t3.put(Material.IRON_ORE, 0.20);
        t3.put(Material.GOLD_ORE, 0.15);
        t3.put(Material.REDSTONE_ORE, 0.10);
        t3.put(Material.LAPIS_ORE, 0.10);
        t3.put(Material.DIAMOND_ORE, 0.05);
        rates.put(3, t3);

        // Tier 4
        Map<Material, Double> t4 = new LinkedHashMap<>();
        t4.put(Material.COBBLESTONE, 0.30);
        t4.put(Material.IRON_ORE, 0.18);
        t4.put(Material.GOLD_ORE, 0.15);
        t4.put(Material.REDSTONE_ORE, 0.12);
        t4.put(Material.DIAMOND_ORE, 0.10);
        t4.put(Material.EMERALD_ORE, 0.10);
        t4.put(Material.ANCIENT_DEBRIS, 0.05);
        rates.put(4, t4);

        return new GeneratorsConfiguration(DEFAULT_ENABLED, rates);
    }

    public static GeneratorsConfiguration load(ConfigurationNode root) {
        return fromNode(root);
    }

    public static GeneratorsConfiguration fromNode(ConfigurationNode root) {
        Objects.requireNonNull(root, "root configuration node must not be null");

        boolean enabled = root.node("enabled").getBoolean(DEFAULT_ENABLED);
        ConfigurationNode tiersNode = root.node("tiers");
        if (tiersNode.virtual() || !tiersNode.isMap()) {
            return defaultConfiguration();
        }

        Map<Integer, Map<Material, Double>> rates = new LinkedHashMap<>();
        for (Map.Entry<Object, ? extends ConfigurationNode> entry :
                tiersNode.childrenMap().entrySet()) {
            try {
                int tierNum = Integer.parseInt(String.valueOf(entry.getKey()));
                ConfigurationNode tierNode = entry.getValue();
                Map<Material, Double> matRates = new LinkedHashMap<>();

                ConfigurationNode ratesNode = tierNode.node("rates");
                if (!ratesNode.virtual() && ratesNode.isMap()) {
                    for (Map.Entry<Object, ? extends ConfigurationNode> rEntry :
                            ratesNode.childrenMap().entrySet()) {
                        String matName = String.valueOf(rEntry.getKey()).toUpperCase(Locale.ROOT);
                        Material mat = Material.matchMaterial(matName);
                        if (mat != null) {
                            matRates.put(mat, rEntry.getValue().getDouble(0.0));
                        }
                    }
                }
                rates.put(tierNum, matRates);
            } catch (NumberFormatException ignored) {
                // Skip invalid tier keys
            }
        }

        if (rates.isEmpty()) {
            return defaultConfiguration();
        }

        return new GeneratorsConfiguration(enabled, rates);
    }
}
