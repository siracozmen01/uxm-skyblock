package com.uxplima.uxmskyblock.bukkit.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;

import org.bukkit.permissions.Permissible;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

/** How a player picks the island they start with is the operator's, written in {@code modules/presets.conf}. */
class PresetChoicesTest {

    private static PresetConfiguration read(String hocon) throws Exception {
        return PresetConfiguration.load(HoconConfigurationLoader.builder().buildAndLoadString(hocon));
    }

    @Test
    @DisplayName("The shipped file opens the window for both, and draws every kind it ships with an item of its own")
    void theShippedFile() throws Exception {
        ConfigurationNode root = HoconConfigurationLoader.builder()
                .path(Path.of("src/main/resources/modules/presets.conf"))
                .build()
                .load();
        PresetConfiguration presets = PresetConfiguration.load(root);
        PresetChoices choices = presets.choices();

        assertThat(choices.whenNoIsland()).isEqualTo(PresetChoices.WhenNoIsland.MENU);
        assertThat(choices.createAsks()).isTrue();
        assertThat(presets.presets()).hasSizeGreaterThanOrEqualTo(3);
        assertThat(presets.presets())
                .extracting(preset -> choices.lookOf(preset.id()).icon())
                .doesNotHaveDuplicates();
        assertThat(choices.lookOf("oneblock").icon()).isEqualTo("GRASS_BLOCK");
        assertThat(choices.ordered(presets.presets()))
                .extracting(preset -> preset.id())
                .describedAs("in the order the file names, which the format alone does not keep")
                .startsWith("classic", "desert", "nether", "cave", "oneblock");
        assertThat(choices.lookOf("classic").permission()).isEmpty();
    }

    @Test
    @DisplayName("The shipped file offers the skyblock islands and no other mode")
    void theShippedFileOffersSkyblock() throws Exception {
        ConfigurationNode root = HoconConfigurationLoader.builder()
                .path(Path.of("src/main/resources/modules/presets.conf"))
                .build()
                .load();
        PresetConfiguration presets = PresetConfiguration.load(root);
        PresetChoices choices = presets.choices();

        assertThat(choices.ordered(presets.presets()).stream().filter(choices::offers))
                .extracting(preset -> preset.id())
                .containsExactly("classic", "desert", "nether", "cave");
        assertThat(presets.defaultId()).isEqualTo("classic");
    }

    @Test
    @DisplayName("A server of one mode offers its kinds, and a default of another mode gives way to the first of them")
    void oneModeOffersItsKinds() throws Exception {
        PresetConfiguration presets = read("""
                presets {
                    modes = ["OneBlock", "nonsense"]
                    default = "classic"
                    entries {
                        classic { order = 1 }
                        oneblock { mode = "oneblock", order = 2 }
                        oneblock_desert { mode = "oneblock", order = 3 }
                    }
                }
                """);
        PresetChoices choices = presets.choices();

        assertThat(presets.presets().stream().filter(choices::offers))
                .extracting(preset -> preset.id())
                .containsExactlyInAnyOrder("oneblock", "oneblock_desert");
        assertThat(presets.defaultId()).isEqualTo("oneblock");
    }

    @Test
    @DisplayName("All, no line, or modes no kind plays offer every mode, so a player always has an island to start")
    void everyModeWhenNothingNarrowsIt() throws Exception {
        String entries = "entries { classic {}, oneblock { mode = oneblock } }";
        for (String modes : List.of("modes = [oneblock, all]", "", "modes = [boxed]")) {
            PresetConfiguration presets = read("presets { " + modes + "\n" + entries + " }");

            assertThat(presets.presets()).describedAs(modes).allMatch(presets.choices()::offers);
        }
    }

    @Test
    @DisplayName("A server whose offered mode it cannot start offers the kinds it can")
    void anUnstartableModeOffersTheRest() throws Exception {
        PresetConfiguration startable = read("""
                        presets {
                            modes = [oneblock]
                            entries { classic {}, oneblock { mode = oneblock, start = ["uxm:oneblock"] } }
                        }
                        """).startableWith(actions -> !actions.contains("uxm:oneblock"));

        assertThat(startable.presets()).allMatch(startable.choices()::offers);
        assertThat(startable.defaultId()).isEqualTo("classic");
    }

    @Test
    @DisplayName("An operator can have a player with no island start at once, and /is create make the default")
    void startAtOnce() throws Exception {
        PresetChoices choices = read("""
                presets {
                    when-no-island = "Create"
                    create-without-type = "default"
                    entries { classic { icon = "DIRT", permission = "myserver.classic" } }
                }
                """).choices();

        assertThat(choices.whenNoIsland()).isEqualTo(PresetChoices.WhenNoIsland.CREATE);
        assertThat(choices.createAsks()).isFalse();
        assertThat(choices.lookOf("classic")).isEqualTo(new PresetChoices.Look("DIRT", "myserver.classic"));
        assertThat(read("presets.when-no-island = help").choices().whenNoIsland())
                .isEqualTo(PresetChoices.WhenNoIsland.HELP);
    }

    @Test
    @DisplayName("A word the file gets wrong opens the window, and a file with no presets still reads the choices")
    void aWrongWordOpensTheWindow() throws Exception {
        PresetChoices choices = read("""
                presets {
                    when-no-island = "sometimes"
                    create-without-type = "maybe"
                }
                """).choices();

        assertThat(choices.whenNoIsland()).isEqualTo(PresetChoices.WhenNoIsland.MENU);
        assertThat(choices.createAsks()).isTrue();
        assertThat(read("presets.when-no-island = help").presets()).isNotEmpty();
    }

    @Test
    @DisplayName("A kind with no node is open to everyone, and one with a node only to whoever holds it")
    void whoMayStartAKind() {
        PresetChoices choices = new PresetChoices(
                java.util.Map.of("nether", new PresetChoices.Look("NETHERRACK", "myserver.nether")),
                PresetChoices.WhenNoIsland.MENU,
                true);
        Permissible holder = mock(Permissible.class);
        Permissible stranger = mock(Permissible.class);
        when(holder.hasPermission("myserver.nether")).thenReturn(true);

        assertThat(choices.allows(stranger, "classic")).isTrue();
        assertThat(choices.allows(stranger, "nether")).isFalse();
        assertThat(choices.allows(holder, "nether")).isTrue();
        assertThat(choices.lookOf("desert").icon()).isEqualTo("SAND");
    }

    @Test
    @DisplayName("The kinds a server can start keep the operator's choices")
    void startableKeepsTheChoices() throws Exception {
        PresetConfiguration presets = read("""
                presets {
                    when-no-island = "help"
                    entries { classic { start = ["uxm:platform"] }, odd { start = ["nobody:provides"] } }
                }
                """);

        PresetConfiguration startable = presets.startableWith(actions -> !actions.contains("nobody:provides"));

        assertThat(startable.presets()).extracting(preset -> preset.id()).containsExactly("classic");
        assertThat(startable.choices().whenNoIsland()).isEqualTo(PresetChoices.WhenNoIsland.HELP);
    }
}
