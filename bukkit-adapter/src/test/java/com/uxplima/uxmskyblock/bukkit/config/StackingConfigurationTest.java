package com.uxplima.uxmskyblock.bukkit.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import org.bukkit.Material;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

class StackingConfigurationTest {

    private static StackingConfiguration read(String hocon) throws Exception {
        return StackingConfiguration.load(HoconConfigurationLoader.builder().buildAndLoadString(hocon));
    }

    @Test
    @DisplayName("The shipped stacking.conf reads as the fallback")
    void theShippedFileIsTheFallback() throws Exception {
        String shipped;
        try (InputStream in = Objects.requireNonNull(getClass().getResourceAsStream("/modules/stacking.conf"))) {
            shipped = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(read(shipped)).isEqualTo(StackingConfiguration.defaultConfiguration());
    }

    @Test
    @DisplayName("The file names the blocks and the most; a name that is no block is left out, and off stacks nothing")
    void everyKeyIsRead() throws Exception {
        StackingConfiguration read =
                read("stacking { enabled = true, blocks = [\"gold_block\", \"NOT_A_BLOCK\", \"DIAMOND\"], most = 64 }");
        assertThat(read.blocks()).containsExactly(Material.GOLD_BLOCK);
        assertThat(read.most()).isEqualTo(64);
        assertThat(read.stacks(Material.GOLD_BLOCK)).isTrue();
        assertThat(read.stacks(Material.IRON_BLOCK)).isFalse();

        StackingConfiguration off = read("stacking { enabled = false, most = 1 }");
        assertThat(off.stacks(Material.IRON_BLOCK)).isFalse();
        assertThat(off.most()).describedAs("a stack is at least two").isEqualTo(2);
        assertThat(off.blocks())
                .isEqualTo(StackingConfiguration.defaultConfiguration().blocks());
    }
}
