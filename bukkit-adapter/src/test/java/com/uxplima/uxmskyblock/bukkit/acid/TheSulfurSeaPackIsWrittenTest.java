package com.uxplima.uxmskyblock.bukkit.acid;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.uxplima.uxmskyblock.bukkit.config.AcidIslandConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The data pack of the sulfur sea biome is written in the colours the operator gave, in the shape 26.2 reads. */
class TheSulfurSeaPackIsWrittenTest {

    @TempDir
    Path folder;

    @Test
    @DisplayName(
            "The pack names its format and carries the biome in the operator's colours, raining and spawning nothing")
    void thePackIsWritten() throws Exception {
        AcidIslandConfiguration.Look look =
                new AcidIslandConfiguration.Look("uxmskyblock:sulfur_sea", "#111111", "#222222", "#333333", "#444444");

        SulfurSeaPack.write(folder, look);
        SulfurSeaPack.write(folder, look);

        JsonObject pack = read(folder.resolve("pack.mcmeta")).getAsJsonObject("pack");
        assertThat(pack.get("min_format").getAsInt()).isEqualTo(107);
        assertThat(pack.get("max_format").getAsInt()).isGreaterThan(107);
        JsonObject biome = read(folder.resolve("data/uxmskyblock/worldgen/biome/sulfur_sea.json"));
        JsonObject attributes = biome.getAsJsonObject("attributes");
        assertThat(attributes.get("minecraft:visual/sky_color").getAsString()).isEqualTo("#444444");
        assertThat(attributes.get("minecraft:visual/fog_color").getAsString()).isEqualTo("#333333");
        assertThat(attributes.get("minecraft:visual/water_fog_color").getAsString())
                .isEqualTo("#222222");
        assertThat(biome.getAsJsonObject("effects").get("water_color").getAsString())
                .isEqualTo("#111111");
        assertThat(biome.get("has_precipitation").getAsBoolean())
                .describedAs("acid rain falls")
                .isTrue();
        assertThat(biome.getAsJsonArray("features")).isEmpty();
        assertThat(biome.getAsJsonObject("spawners").get("monster").getAsJsonArray())
                .isEmpty();
        assertThat(biome.getAsJsonObject("spawners").keySet())
                .containsExactlyInAnyOrder(
                        "ambient",
                        "axolotls",
                        "creature",
                        "misc",
                        "monster",
                        "underground_water_creature",
                        "water_ambient",
                        "water_creature");
    }

    private static JsonObject read(Path file) throws Exception {
        return JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                .getAsJsonObject();
    }
}
