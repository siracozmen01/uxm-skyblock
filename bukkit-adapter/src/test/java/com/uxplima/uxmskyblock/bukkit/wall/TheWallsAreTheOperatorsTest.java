package com.uxplima.uxmskyblock.bukkit.wall;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

/** The styles of a wall, which one stands around a ChunkBlock island and how high, are written in walls.conf. */
class TheWallsAreTheOperatorsTest {

    private static WallConfiguration read(String hocon) throws Exception {
        return WallConfiguration.load(HoconConfigurationLoader.builder().buildAndLoadString(hocon));
    }

    @Test
    @DisplayName("The shipped file walls a ChunkBlock island in a grey a player sees through, and ships glass")
    void theShippedFile() throws Exception {
        WallConfiguration config = WallConfiguration.load(HoconConfigurationLoader.builder()
                .path(Path.of("src/main/resources/modules/walls.conf"))
                .build()
                .load());

        WallStyle mist = config.chunkBlockStyle().orElseThrow();
        assertThat(mist.kind()).isEqualTo(WallStyle.Kind.COLOUR);
        assertThat(mist.argb() & 0xFFFFFF).isEqualTo(0x8A93A1);
        assertThat(mist.argb() >>> 24).describedAs("22 percent").isEqualTo(56);
        assertThat(config.styles().get("glass"))
                .isEqualTo(WallStyle.block("glass", "minecraft:light_gray_stained_glass"));
        assertThat(config.bottom()).isEqualTo(-64);
        assertThat(config.top()).isEqualTo(320);
        assertThat(config.slice()).isEqualTo(64);
        assertThat(config.bedrock()).isEqualTo(WallConfiguration.Bedrock.SHIPPED);
    }

    @Test
    @DisplayName("The Bedrock edge is the operator's colour, spacing and reach, and a wrong one falls back")
    void theBedrockEdge() throws Exception {
        WallConfiguration.Bedrock edge = read("""
                walls { bedrock { enabled = false, colour = "#FF0000", spacing = 0.5, reach = 20 } }
                """).bedrock();
        assertThat(edge).isEqualTo(new WallConfiguration.Bedrock(false, 0xFF0000, 0.5, 20));

        assertThat(read("walls { bedrock { colour = \"red\", reach = 30 } }").bedrock())
                .describedAs("a colour that is no #RRGGBB keeps the shipped grey, and the rest still reads")
                .isEqualTo(new WallConfiguration.Bedrock(true, 0x8A93A1, 1.0, 30));
        assertThat(read("walls { bedrock { spacing = 0.1 } }").bedrock()).isEqualTo(WallConfiguration.Bedrock.SHIPPED);
        assertThat(read("walls { bedrock { reach = 49 } }").bedrock()).isEqualTo(WallConfiguration.Bedrock.SHIPPED);
        assertThat(read("walls { bedrock { reach = 0 } }").bedrock()).isEqualTo(WallConfiguration.Bedrock.SHIPPED);
        assertThat(read("walls { bedrock { reach = 48, spacing = 0.25 } }")
                        .bedrock()
                        .reach())
                .isEqualTo(48);
        assertThat(read("walls {}").bedrock()).isEqualTo(WallConfiguration.Bedrock.SHIPPED);
    }

    @Test
    @DisplayName("An operator's style is any colour and opacity, or any block, and a wrong one is left out")
    void anOperatorsStyles() throws Exception {
        WallConfiguration config = read("""
                walls {
                    styles {
                        rose { kind = colour, colour = "#FF0080", opacity = 100 }
                        wool { kind = block, block = "minecraft:pink_wool" }
                        bad { kind = colour, colour = "pink" }
                        odd { kind = smoke }
                        empty { kind = block }
                    }
                    chunkblock = rose
                }
                """);

        assertThat(config.styles()).containsOnlyKeys("rose", "wool");
        assertThat(config.chunkBlockStyle().orElseThrow().argb()).isEqualTo(0xFFFF0080);
        assertThat(read("walls { bottom = 0 }").chunkBlockStyle())
                .describedAs("a file without styles keeps the shipped ones")
                .contains(WallConfiguration.MIST);
        assertThat(read("walls { chunkblock = \"\" }").chunkBlockStyle()).isEmpty();
        assertThat(read("walls { chunkblock = nothing }").chunkBlockStyle()).isEmpty();
    }

    @Test
    @DisplayName("Heights that make no wall fall back to the shipped ones, and opacity stays within 0 to 100")
    void wrongHeightsFallBack() throws Exception {
        WallConfiguration config = read("walls { bottom = 100, top = 50, slice = 0 }");

        assertThat(config.bottom()).isEqualTo(-64);
        assertThat(config.top()).isEqualTo(320);
        assertThat(WallStyle.colour("x", 0, 150).argb() >>> 24).isEqualTo(255);
        assertThat(WallStyle.colour("x", 0, -5).argb() >>> 24).isZero();
        assertThat(WallConfiguration.rgbOf("#00ff00")).contains(0x00FF00);
        assertThat(WallConfiguration.rgbOf("00ff00")).isEmpty();
    }
}
