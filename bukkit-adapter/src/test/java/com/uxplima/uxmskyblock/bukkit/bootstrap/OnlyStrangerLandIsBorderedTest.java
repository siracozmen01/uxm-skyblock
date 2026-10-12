package com.uxplima.uxmskyblock.bukkit.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;

import com.uxplima.uxmskyblock.bukkit.config.PresetConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.ServerNodeConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.StrangerRealmsConfiguration;
import com.uxplima.uxmskyblock.core.domain.biome.IslandBiome;
import com.uxplima.uxmskyblock.core.domain.gamemode.GameModeType;
import com.uxplima.uxmskyblock.core.domain.preset.StartTemplateBundle;
import com.uxplima.uxmskyblock.core.domain.preset.StarterPreset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The StrangerRealms border is set only in worlds nothing else makes islands in, so no other island is
 * ever cut off, unless the operator names the worlds.
 */
class OnlyStrangerLandIsBorderedTest {

    @Test
    @DisplayName("A world only StrangerRealms presets use is bordered, and one another preset shares is not")
    void onlyStrangerLand() {
        ConfigurationWiring configuration = configuration(List.of());

        assertThat(StrangerRealmsWiring.borderedWorlds(configuration)).containsExactly("realms");
    }

    @Test
    @DisplayName("Worlds the operator names are the ones bordered")
    void theOperatorDecides() {
        ConfigurationWiring configuration = configuration(List.of("skyblock"));

        assertThat(StrangerRealmsWiring.borderedWorlds(configuration)).containsExactly("skyblock");
    }

    private static ConfigurationWiring configuration(List<String> named) {
        ConfigurationWiring configuration = mock(ConfigurationWiring.class);
        ServerNodeConfiguration node = mock(ServerNodeConfiguration.class);
        when(node.worldName()).thenReturn("skyblock");
        when(configuration.nodeConfig()).thenReturn(node);
        when(configuration.presetConfig())
                .thenReturn(new PresetConfiguration(
                        List.of(
                                preset("classic", GameModeType.SKYBLOCK, ""),
                                preset("stranger_here", GameModeType.STRANGER_REALMS, ""),
                                preset("stranger_realms", GameModeType.STRANGER_REALMS, "realms"),
                                preset("boxed", GameModeType.BOXED, "boxed"),
                                preset("stranger_boxed", GameModeType.STRANGER_REALMS, "boxed")),
                        "classic"));
        StrangerRealmsConfiguration shipped = StrangerRealmsConfiguration.defaultConfiguration();
        StrangerRealmsConfiguration.Border border = shipped.border();
        when(configuration.strangerRealmsConfig())
                .thenReturn(new StrangerRealmsConfiguration(
                        true,
                        shipped.upsideDown(),
                        shipped.mobs(),
                        shipped.glimmer(),
                        shipped.compass(),
                        shipped.claim(),
                        new StrangerRealmsConfiguration.Border(
                                true,
                                Duration.ofMinutes(1),
                                border.centerX(),
                                border.centerZ(),
                                border.rule(),
                                border.transition(),
                                named)));
        return configuration;
    }

    private static StarterPreset preset(String id, GameModeType mode, String world) {
        return new StarterPreset(
                id,
                id,
                "",
                "schematics/" + id + ".schem",
                IslandBiome.PLAINS,
                mode,
                List.of(StarterPreset.PLATFORM),
                StartTemplateBundle.shipped(),
                world);
    }
}
