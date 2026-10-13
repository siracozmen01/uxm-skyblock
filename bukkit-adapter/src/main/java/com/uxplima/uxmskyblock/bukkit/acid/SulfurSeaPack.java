package com.uxplima.uxmskyblock.bukkit.acid;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.uxplima.uxmskyblock.bukkit.config.AcidIslandConfiguration;

/**
 * The data pack that carries the plugin's own sulfur sea biome, written in the colours {@code sea look} of
 * {@code modules/acidisland.conf} gives, so the server knows the biome before its worlds load.
 *
 * <p>The biome is a sky island's: nothing generates in it and nothing spawns from it. It rains there, because the
 * acid rain needs rain to fall.
 */
public final class SulfurSeaPack {

    /** The name the server knows the pack by. */
    public static final String ID = "sulfur_sea";

    /** The oldest data pack format the pack is written for, the one of 26.2. */
    static final int FORMAT = 107;

    private SulfurSeaPack() {}

    /** Writes the pack into {@code folder}, over what an earlier start wrote there. */
    public static void write(Path folder, AcidIslandConfiguration.Look look) throws IOException {
        Files.createDirectories(folder);
        Files.writeString(folder.resolve("pack.mcmeta"), mcmeta(), StandardCharsets.UTF_8);
        Path biomes = folder.resolve("data/uxmskyblock/worldgen/biome");
        Files.createDirectories(biomes);
        Files.writeString(biomes.resolve("sulfur_sea.json"), biome(look), StandardCharsets.UTF_8);
    }

    static String mcmeta() {
        return """
                {
                  "pack": {
                    "description": "The sulfur sea of uxmSkyblock's AcidIsland islands",
                    "min_format": %d,
                    "max_format": 1000
                  }
                }
                """.formatted(FORMAT);
    }

    static String biome(AcidIslandConfiguration.Look look) {
        return """
                {
                  "has_precipitation": true,
                  "temperature": 0.8,
                  "downfall": 0.4,
                  "attributes": {
                    "minecraft:visual/sky_color": "%s",
                    "minecraft:visual/fog_color": "%s",
                    "minecraft:visual/water_fog_color": "%s"
                  },
                  "effects": {
                    "water_color": "%s"
                  },
                  "carvers": [],
                  "features": [],
                  "spawn_costs": {},
                  "spawners": {
                    "ambient": [],
                    "axolotls": [],
                    "creature": [],
                    "misc": [],
                    "monster": [],
                    "underground_water_creature": [],
                    "water_ambient": [],
                    "water_creature": []
                  }
                }
                """.formatted(look.sky(), look.fog(), look.waterFog(), look.water());
    }
}
