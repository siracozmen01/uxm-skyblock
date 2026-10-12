package com.uxplima.uxmskyblock.bukkit.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.domain.oneblock.OneBlockPhase;
import com.uxplima.uxmskyblock.core.domain.oneblock.OneBlockPhases;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

/** The OneBlock phases come out of the operator's file, and a bad name costs one entry, not the mode. */
class OneBlockConfigurationTest extends MockBukkitHarness {

    @Test
    @DisplayName("Every phase the plugin ships loads, and every block and creature in it is one the server knows")
    void theShippedFileLoadsWhole() throws Exception {
        String shipped;
        try (InputStream in =
                Objects.requireNonNull(getClass().getClassLoader().getResourceAsStream("modules/oneblock.conf"))) {
            shipped = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        ConfigurationNode root = root(shipped);
        java.nio.file.Path folder = java.nio.file.Path.of("src/main/resources/oneblock/phases");

        OneBlockConfiguration config = OneBlockConfiguration.load(root, folder);

        assertThat(config.enabled()).isTrue();
        assertThat(config.phases().afterTheLast()).isEqualTo(OneBlockPhases.AfterTheLast.REPEAT);
        assertThat(config.phases().phases())
                .extracting(OneBlockPhase::key)
                .containsExactly("plains", "underground", "snow", "ocean", "jungle", "nether", "end");
        assertThat(config.phases().phases())
                .extracting(OneBlockPhase::key)
                .describedAs("every shipped phase file is one the plugin unpacks")
                .containsExactlyElementsOf(OneBlockPhaseFiles.SHIPPED);
        for (OneBlockPhase phase : config.phases().phases()) {
            ConfigurationNode written = org.spongepowered.configurate.hocon.HoconConfigurationLoader.builder()
                    .path(folder.resolve(phase.key() + ".conf"))
                    .build()
                    .load();
            assertThat(phase.blockPool().weights())
                    .describedAs("%s: every block written is one the server knows", phase.key())
                    .hasSameSizeAs(written.node("blocks").childrenMap());
            assertThat(phase.creaturePool().weights())
                    .describedAs("%s: every creature written is one the server can spawn", phase.key())
                    .hasSameSizeAs(written.node("creatures").childrenMap());
            assertThat(phase.blocks()).isEqualTo(written.node("length").getLong());
            assertThat(config.iconOf(phase.key()))
                    .describedAs("%s: its icon is the one written", phase.key())
                    .isEqualTo(written.node("icon").getString());
            for (String event : OneBlockLists.EVENTS) {
                assertThat(config.listsOf(phase.key(), event).isPresent())
                        .describedAs("%s: its %s lists are read as written", phase.key(), event)
                        .isEqualTo(!written.node(event).virtual());
            }
        }
        assertThat(config.listsOf("plains", "first-finished"))
                .describedAs("the shipped example")
                .isPresent();
        assertThat(config.phases().phases().stream().map(phase -> config.iconOf(phase.key())))
                .describedAs("each shipped phase has an icon of its own")
                .doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("An unknown name, a phase with no known block and a bad length are left out, and the rest stays")
    void badEntriesAreLeftOut() throws Exception {
        OneBlockConfiguration config = OneBlockConfiguration.load(root("""
                after-last-phase = "sideways"
                phases = [
                    { name = first, length = 10, blocks { DIRT = 1, NOT_A_BLOCK = 5 }, creatures { unicorn = 1, cow = 1 } }
                    { name = ghost, length = 10, blocks { NOT_A_BLOCK = 1 } }
                    { name = broken, length = 0, blocks { STONE = 1 } }
                    { length = 5, blocks { STONE = 1 } }
                    { name = last, length = 5, blocks { STONE = 1 } }
                ]
                """));

        assertThat(config.phases().afterTheLast()).isEqualTo(OneBlockPhases.AfterTheLast.STAY);
        assertThat(config.phases().phases()).extracting(OneBlockPhase::key).containsExactly("first", "last");
        OneBlockPhase first = config.phases().phases().getFirst();
        assertThat(first.blockPool().weights()).containsOnlyKeys("DIRT");
        assertThat(first.creaturePool().weights()).containsOnlyKeys("COW");
    }

    @Test
    @DisplayName("A file with nothing usable falls back to the built in phase, keeping the operator's switch")
    void nothingUsableFallsBack() throws Exception {
        OneBlockConfiguration config = OneBlockConfiguration.load(
                root("enabled = false\nphases = [ { name = ghost, length = 5, blocks { AIR = 1 } } ]"));

        assertThat(config.enabled()).isFalse();
        assertThat(config.phases())
                .isEqualTo(OneBlockConfiguration.defaultConfiguration().phases());
    }

    private static ConfigurationNode root(String hocon) throws Exception {
        return HoconConfigurationLoader.builder().buildAndLoadString(hocon);
    }
}
