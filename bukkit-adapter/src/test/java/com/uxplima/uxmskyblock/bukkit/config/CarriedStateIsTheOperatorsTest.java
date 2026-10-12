package com.uxplima.uxmskyblock.bukkit.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

/** Which worlds share what a player carries, and what is kept apart, is the operator's to write. */
class CarriedStateIsTheOperatorsTest {

    private static CarriedStateConfiguration read(String hocon) throws Exception {
        return CarriedStateConfiguration.load(HoconConfigurationLoader.builder().buildAndLoadString(hocon));
    }

    @Test
    @DisplayName("The shipped file reads as the shipped settings: every preset world its own group, everything apart")
    void theShippedFile() throws Exception {
        String shipped =
                Files.readString(Path.of("src/main/resources/modules/player-state.conf"), StandardCharsets.UTF_8);

        assertThat(read(shipped)).isEqualTo(CarriedStateConfiguration.defaultConfiguration());
        assertThat(CarriedStateConfiguration.SHIPPED.groupOf("boxed", List.of("world", "boxed")))
                .contains("boxed");
        assertThat(CarriedStateConfiguration.SHIPPED.groupOf("skyblock_nether", List.of("world", "boxed")))
                .isEmpty();
    }

    @Test
    @DisplayName(
            "Named groups share their worlds, a world named twice stays in the first, and a switch turns a part off")
    void namedGroups() throws Exception {
        CarriedStateConfiguration file = read("""
                groups {
                  main = ["world", "boxed"]
                  side = ["boxed", "realms", " "]
                  empty = []
                }
                keeps { ender-chest = false, advancements = false }
                """);

        assertThat(file.groups()).containsOnlyKeys("main", "side");
        assertThat(file.groups().get("side"))
                .describedAs("boxed stays in main alone")
                .containsExactly("realms");
        assertThat(file.groupOf("boxed", List.of())).contains("main");
        assertThat(file.groupOf("realms", List.of())).contains("side");
        assertThat(file.groupOf("world", List.of("world")))
                .describedAs("named groups, not the presets")
                .contains("main");
        assertThat(file.groupOf("elsewhere", List.of("elsewhere"))).isEmpty();
        assertThat(file.keeps().enderChest()).isFalse();
        assertThat(file.keeps().advancements()).isFalse();
        assertThat(file.keeps().inventory()).isTrue();
    }
}
