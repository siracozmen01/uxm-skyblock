package com.uxplima.uxmskyblock.bukkit.menu;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.bukkit.Material;

import com.uxplima.uxmskyblock.bukkit.config.GeneratorsConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.UpgradesConfiguration;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.domain.upgrade.UpgradeDefinition;
import com.uxplima.uxmskyblock.core.domain.upgrade.UpgradeId;
import com.uxplima.uxmskyblock.core.domain.upgrade.UpgradeTier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** Each upgrade says what it does on the tier an island stands on, what the next tier does, and its price. */
class UpgradeWordsTest extends MockBukkitHarness {

    private final Map<UpgradeId, UpgradeDefinition> definitions =
            UpgradesConfiguration.defaultConfiguration().definitions();
    private PlayerMock reader;
    private UpgradeWords words;

    @BeforeEach
    void setUp() {
        reader = createPlayer("Reader");
        words = new UpgradeWords(Messages.bundled(), () -> definitions, GeneratorsConfiguration.defaultConfiguration());
    }

    @Test
    @DisplayName("An island on a tier reads what it has, what the next tier gives and what that costs")
    void aTierAndTheNext() {
        Map<String, String> values = words.values(reader, Map.of(UpgradeId.MEMBERS, 2, UpgradeId.WARPS, 1));

        assertThat(values)
                .containsEntry("member_limit_now", "Up to 8 members")
                .containsEntry("member_limit_next", "Up to 12 members")
                .containsEntry("member_limit_cost", "1,000.00")
                .containsEntry("member_limit_max", "4")
                .containsEntry("warp_slots_now", "Up to 4 warps");
    }

    @Test
    @DisplayName("An island that bought nothing has nothing yet, unless the first tier is free")
    void nothingYet() {
        Map<String, String> values = words.values(reader, Map.of());

        assertThat(values.get("crop_growth_now")).isEqualTo("Nothing yet");
        assertThat(values.get("crop_growth_next")).isEqualTo("Crops grow 1.1× as fast");
        assertThat(values.get("island_size_now")).isEqualTo("50×50 blocks");
    }

    @Test
    @DisplayName("The highest tier says so where the next tier and its price would be")
    void theHighestTier() {
        Map<String, String> values = words.values(reader, Map.of(UpgradeId.SPAWNER_RATES, 3));

        assertThat(values)
                .containsEntry("spawner_rates_now", "Spawners work 1.5× as fast")
                .containsEntry("spawner_rates_next", "Highest tier reached")
                .containsEntry("spawner_rates_cost", "Highest tier reached");
    }

    @Test
    @DisplayName(
            "The ore generator names every block it turns up with its share, the likeliest first, in the reader's words")
    void theGeneratorNamesItsOres() {
        assertThat(words.values(reader, Map.of(UpgradeId.ORE_GENERATOR, 2)))
                .containsEntry(
                        "ore_generator_now", "Cobblestone 55%, Coal ore 20%, Iron ore 15%, Gold ore 6%, Lapis ore 4%")
                .containsEntry(
                        "ore_generator_next",
                        "Cobblestone 40%, Iron ore 20%, Gold ore 15%, Redstone ore 10%, Lapis ore 10%, Diamond ore 5%");

        java.util.Map<Material, Double> rarestFirst = new java.util.LinkedHashMap<>();
        rarestFirst.put(Material.COAL_ORE, 1.0);
        rarestFirst.put(Material.STONE, 3.0);
        UpgradeWords written = new UpgradeWords(
                Messages.bundled(), () -> definitions, new GeneratorsConfiguration(true, Map.of(1, rarestFirst)));
        assertThat(written.values(reader, Map.of(UpgradeId.ORE_GENERATOR, 1)))
                .containsEntry("ore_generator_now", "Stone 75%, Coal ore 25%");

        reader.setLocale(Locale.forLanguageTag("tr"));
        assertThat(words.values(reader, Map.of()).get("ore_generator_now")).isEqualTo("Kırık taş 100%");
    }

    @Test
    @DisplayName("An upgrade the operator added with no words of its own lists its properties as they are named")
    void anUpgradeWithNoWords() {
        UpgradeId hoppers = UpgradeId.of("hopper_limit");
        Map<UpgradeId, UpgradeDefinition> withHoppers = Map.of(
                hoppers,
                new UpgradeDefinition(
                        hoppers,
                        "Hoppers",
                        List.of(
                                new UpgradeTier(1, 100L, "PRIMARY", Map.of("limit", 16.0)),
                                new UpgradeTier(2, 200L, "PRIMARY", Map.of("limit", 32.0)))));
        UpgradeWords custom =
                new UpgradeWords(Messages.bundled(), () -> withHoppers, GeneratorsConfiguration.defaultConfiguration());

        assertThat(custom.values(reader, Map.of(hoppers, 1)))
                .containsEntry("hopper_limit_now", "limit 16")
                .containsEntry("hopper_limit_next", "limit 32")
                .containsEntry("hopper_limit_cost", "2.00");
    }

    @Test
    @DisplayName("A generator's shares are its weights out of their sum, whatever they add up to")
    void sharesAreOutOfTheSum() {
        GeneratorsConfiguration odd = new GeneratorsConfiguration(
                true, Map.of(1, new java.util.LinkedHashMap<>(Map.of(Material.STONE, 3.0, Material.COAL_ORE, 1.0))));

        assertThat(odd.shares(1)).containsEntry(Material.STONE, 75.0).containsEntry(Material.COAL_ORE, 25.0);
        assertThat(odd.shares(7))
                .describedAs("a tier above the file reads the highest one")
                .containsKey(Material.STONE);
        assertThat(UpgradeWords.percent(12.345)).isEqualTo("12.3");
        assertThat(UpgradeWords.number(25.0)).isEqualTo("25");
    }
}
