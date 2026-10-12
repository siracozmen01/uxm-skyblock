package com.uxplima.uxmskyblock.bukkit.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

/** How long a look lasts, where the showrooms stand and whether there are looks at all are the operator's. */
class ALookBeforeChoosingIsTheOperatorsTest {

    private static PresetPreview read(String hocon) throws Exception {
        return PresetChoices.load(HoconConfigurationLoader.builder()
                        .buildAndLoadString(hocon)
                        .node("presets"))
                .preview();
    }

    @Test
    @DisplayName("The shipped file reads as the shipped look, and a file without the block gets it too")
    void shipped() throws Exception {
        PresetChoices file = PresetChoices.load(HoconConfigurationLoader.builder()
                .path(Path.of("src/main/resources/modules/presets.conf"))
                .build()
                .load()
                .node("presets"));

        assertThat(file.preview()).isEqualTo(PresetPreview.SHIPPED);
        assertThat(read("presets { modes = all }")).isEqualTo(PresetPreview.SHIPPED);
        assertThat(PresetPreview.SHIPPED.xOf(2)).isEqualTo(-1_000_000 + 2 * 1024);
    }

    @Test
    @DisplayName(
            "Each value is read, a time that is none keeps the shipped one, and a look that cannot stand is shipped")
    void written() throws Exception {
        PresetPreview look = read("""
                presets.preview { enabled = false, time = "1m", radius = 30, cooldown = "soon", world = " showroom ",
                  x = 5000, z = -7000, y = 80, spacing = 200 }
                """);

        assertThat(look.enabled()).isFalse();
        assertThat(look.time()).isEqualTo(Duration.ofMinutes(1));
        assertThat(look.radius()).isEqualTo(30);
        assertThat(look.cooldown()).isEqualTo(PresetPreview.SHIPPED.cooldown());
        assertThat(look.world()).isEqualTo("showroom");
        assertThat(look.xOf(1)).isEqualTo(5200);
        assertThat(look.z()).isEqualTo(-7000);
        assertThat(look.y()).isEqualTo(80);
        assertThat(read("presets.preview { radius = 40, spacing = 50 }"))
                .describedAs("showrooms closer than two radii would overlap")
                .isEqualTo(PresetPreview.SHIPPED);
        assertThat(read("presets.preview { time = \"0s\" }")).isEqualTo(PresetPreview.SHIPPED);
    }
}
