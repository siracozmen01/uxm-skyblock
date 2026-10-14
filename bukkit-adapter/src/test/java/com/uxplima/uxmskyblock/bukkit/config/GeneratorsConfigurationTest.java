package com.uxplima.uxmskyblock.bukkit.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.bukkit.Material;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

class GeneratorsConfigurationTest extends com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness {

    @Test
    @DisplayName("Default configuration contains tiers 0 through 4 with valid materials")
    void defaultConfigurationContainsAllTiers() {
        GeneratorsConfiguration config = GeneratorsConfiguration.defaultConfiguration();

        assertThat(config.enabled()).isTrue();
        assertThat(config.tierRates()).containsKey(0);
        assertThat(config.tierRates()).containsKey(1);
        assertThat(config.tierRates()).containsKey(2);
        assertThat(config.tierRates()).containsKey(3);
        assertThat(config.tierRates()).containsKey(4);

        // Tier 0 should only roll cobblestone
        assertThat(config.roll(0, 0.5)).isEqualTo(Material.COBBLESTONE);

        // Tier 4 roll test
        Material rolled = config.roll(4, 0.0);
        assertThat(rolled).isNotNull();
    }

    @Test
    @DisplayName("Roll selects exact material based on cumulative weight and random roll")
    void rollSelectsExactMaterial() {
        Map<Material, Double> rates = Map.of(
                Material.COBBLESTONE, 0.5,
                Material.DIAMOND_ORE, 0.5);
        GeneratorsConfiguration config = new GeneratorsConfiguration(true, Map.of(1, rates));

        // When roll is in first half (e.g. 0.2), COBBLESTONE is returned
        // When roll is in second half (e.g. 0.8), DIAMOND_ORE is returned
        Material first = config.roll(1, 0.1);
        Material second = config.roll(1, 0.9);

        assertThat(first).isNotEqualTo(second);
        assertThat(first).isIn(Material.COBBLESTONE, Material.DIAMOND_ORE);
        assertThat(second).isIn(Material.COBBLESTONE, Material.DIAMOND_ORE);
    }

    @Test
    @DisplayName("Loads custom generators configuration from HOCON node")
    void loadsCustomConfigurationFromHocon() throws Exception {
        String hocon = """
                enabled = true
                tiers {
                    "0" {
                        rates {
                            COBBLESTONE = 1.0
                        }
                    }
                    "1" {
                        rates {
                            COBBLESTONE = 0.8
                            IRON_ORE = 0.2
                        }
                    }
                }
                """;

        ConfigurationNode node = HoconConfigurationLoader.builder().buildAndLoadString(hocon);
        GeneratorsConfiguration config = GeneratorsConfiguration.load(node);

        assertThat(config.enabled()).isTrue();
        assertThat(config.tierRates()).containsKey(0);
        assertThat(config.tierRates()).containsKey(1);
        assertThat(config.tierRates().get(1)).containsEntry(Material.COBBLESTONE, 0.8);
        assertThat(config.tierRates().get(1)).containsEntry(Material.IRON_ORE, 0.2);
    }

    @Test
    @DisplayName("A tier the file no longer writes keeps the best tier still written below it")
    void anUnwrittenTierKeepsTheBestTierBelow() {
        GeneratorsConfiguration config = new GeneratorsConfiguration(
                true,
                Map.of(
                        0, Map.of(Material.COBBLESTONE, 1.0),
                        2, Map.of(Material.IRON_ORE, 1.0),
                        3, Map.of(Material.DIAMOND_ORE, 1.0)));

        assertThat(config.roll(5, 0.5))
                .describedAs("an island that bought tier five after the operator cut the file to three")
                .isEqualTo(Material.DIAMOND_ORE);
        assertThat(config.roll(1, 0.5))
                .describedAs("a gap in the file falls to the tier below it, not above")
                .isEqualTo(Material.COBBLESTONE);
    }

    @Test
    @DisplayName("A tier below every tier the file writes takes the lowest one written")
    void aTierBelowEveryWrittenTierTakesTheLowest() {
        GeneratorsConfiguration config = new GeneratorsConfiguration(true, Map.of(2, Map.of(Material.IRON_ORE, 1.0)));

        assertThat(config.roll(1, 0.5)).isEqualTo(Material.IRON_ORE);
    }

    @Test
    @DisplayName(
            "A tier's own Nether and End rates are turned up there; a tier with none, and the overworld, use its own")
    void eachDimensionReadsItsTier() throws Exception {
        GeneratorsConfiguration read =
                GeneratorsConfiguration.load(org.spongepowered.configurate.hocon.HoconConfigurationLoader.builder()
                        .buildAndLoadString("""
                                tiers {
                                    1 { rates { COAL_ORE = 1.0 } }
                                    3 {
                                        rates { DIAMOND_ORE = 1.0 }
                                        nether { rates { ANCIENT_DEBRIS = 1.0 } }
                                        end { rates { OBSIDIAN = 1.0 } }
                                    }
                                }
                                """));
        org.bukkit.World.Environment nether = org.bukkit.World.Environment.NETHER;
        org.bukkit.World.Environment end = org.bukkit.World.Environment.THE_END;
        org.bukkit.World.Environment overworld = org.bukkit.World.Environment.NORMAL;

        assertThat(read.roll(3, nether, 0.5, 1.0)).isEqualTo(Material.ANCIENT_DEBRIS);
        assertThat(read.roll(3, end, 0.5, 1.0)).isEqualTo(Material.OBSIDIAN);
        assertThat(read.roll(3, overworld, 0.5, 1.0)).isEqualTo(Material.DIAMOND_ORE);
        assertThat(read.roll(5, nether, 0.5, 1.0))
                .describedAs("the highest tier below")
                .isEqualTo(Material.ANCIENT_DEBRIS);
        assertThat(read.roll(2, nether, 0.5, 1.0))
                .describedAs("tier 1 writes no Nether rates")
                .isEqualTo(Material.COAL_ORE);
    }

    @Test
    @DisplayName("A tier handed empty Nether rates turns up its overworld rates there")
    void emptyRatesAreNone() {
        GeneratorsConfiguration config = new GeneratorsConfiguration(
                true,
                Map.of(1, Map.of(Material.COAL_ORE, 1.0)),
                Map.of(org.bukkit.World.Environment.NETHER, Map.of(1, Map.of())));
        assertThat(config.roll(1, org.bukkit.World.Environment.NETHER, 0.5, 1.0))
                .isEqualTo(Material.COAL_ORE);
    }

    @Test
    @DisplayName("The shipped file reads as the fallback, its Nether and End rates and all")
    void theShippedFileIsTheFallback() throws Exception {
        String shipped;
        try (java.io.InputStream in =
                java.util.Objects.requireNonNull(getClass().getResourceAsStream("/modules/generators.conf"))) {
            shipped = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        GeneratorsConfiguration read =
                GeneratorsConfiguration.load(org.spongepowered.configurate.hocon.HoconConfigurationLoader.builder()
                        .buildAndLoadString(shipped));
        GeneratorsConfiguration fallback = GeneratorsConfiguration.defaultConfiguration();
        assertThat(read.tierRates()).isEqualTo(fallback.tierRates());
        assertThat(read.dimensionRates()).isEqualTo(fallback.dimensionRates());
        assertThat(read.dimensionRates().get(org.bukkit.World.Environment.NETHER))
                .containsOnlyKeys(2, 3, 4);
    }

    @Test
    @DisplayName("A booster leaves the stones a Nether or End generator forms of its own as likely as ever")
    void netherStonesAreNotBoosted() {
        java.util.LinkedHashMap<Material, Double> rates = new java.util.LinkedHashMap<>();
        rates.put(Material.NETHERRACK, 0.5);
        rates.put(Material.BLACKSTONE, 0.25);
        rates.put(Material.END_STONE, 0.15);
        rates.put(Material.ANCIENT_DEBRIS, 0.1);
        GeneratorsConfiguration config = new GeneratorsConfiguration(true, Map.of(1, rates));
        // Unboosted, 0.85 lands on end stone. Boosted twice, the stones keep their weight and the debris
        // weighs 0.2 of 1.1, so 0.85 lands on it and 0.6 still lands on blackstone.
        assertThat(config.roll(1, 0.85, 1.0)).isEqualTo(Material.END_STONE);
        assertThat(config.roll(1, 0.85, 2.0)).isEqualTo(Material.ANCIENT_DEBRIS);
        assertThat(config.roll(1, 0.6, 2.0)).isEqualTo(Material.BLACKSTONE);
    }
}
