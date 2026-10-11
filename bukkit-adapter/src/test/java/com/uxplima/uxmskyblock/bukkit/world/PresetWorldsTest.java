package com.uxplima.uxmskyblock.bukkit.world;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import com.uxplima.uxmskyblock.core.domain.biome.IslandBiome;
import com.uxplima.uxmskyblock.core.domain.gamemode.GameModeType;
import com.uxplima.uxmskyblock.core.domain.preset.StartTemplateBundle;
import com.uxplima.uxmskyblock.core.domain.preset.StarterPreset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A kind of island is offered only where its world is loaded.
 *
 * <p>Boxed and StrangerRealms are made in worlds of their own, and a Folia server opens no world but its own, so on
 * Folia they can never be made. They were offered all the same, and the player who picked one was refused.
 */
class PresetWorldsTest {

    private static StarterPreset kind(String id, String world) {
        return new StarterPreset(
                id,
                id,
                id,
                "schematics/" + id + ".schem",
                IslandBiome.PLAINS,
                GameModeType.SKYBLOCK,
                List.of(StarterPreset.PLATFORM),
                StartTemplateBundle.shipped(),
                world);
    }

    private static final List<StarterPreset> KINDS =
            List.of(kind("classic", ""), kind("boxed", "boxed"), kind("oneblock", ""), kind("realms", "realms"));

    @Test
    @DisplayName("Only the kinds whose world is loaded are offered, in their order")
    void onlyTheKindsWithAWorld() {
        PresetWorlds worlds = new PresetWorlds("skyblock", Set.of("skyblock")::contains, true);

        assertThat(worlds.playable(KINDS)).extracting(StarterPreset::id).containsExactly("classic", "oneblock");
        assertThat(new PresetWorlds("skyblock", Set.of("skyblock", "boxed", "realms")::contains, false).playable(KINDS))
                .describedAs("on a server that loaded their worlds, every kind")
                .hasSize(4);
    }

    @Test
    @DisplayName("The console is told once per kind that is off, why, and on Folia that Folia is the reason")
    void theConsoleIsTold() {
        List<String> folia = new PresetWorlds("skyblock", Set.of("skyblock")::contains, true).notes(KINDS);
        List<String> paper = new PresetWorlds("skyblock", Set.of("skyblock")::contains, false).notes(KINDS);

        assertThat(folia).hasSize(2);
        assertThat(folia.get(0)).contains("'boxed'").contains("Folia").contains("Paper or Leaf");
        assertThat(paper.get(1))
                .contains("'realms'")
                .contains("modules/presets.conf")
                .doesNotContain("Folia");
        assertThat(new PresetWorlds("skyblock", name -> true, true).notes(KINDS))
                .describedAs("nothing to say when every world is there")
                .isEmpty();
    }
}
