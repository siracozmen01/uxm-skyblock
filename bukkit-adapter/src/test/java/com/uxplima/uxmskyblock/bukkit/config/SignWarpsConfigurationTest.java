package com.uxplima.uxmskyblock.bukkit.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

class SignWarpsConfigurationTest {

    @Test
    @DisplayName("The shipped warps.conf reads as the fallback; a blank header keeps the shipped one")
    void shipped() throws Exception {
        String shipped;
        try (InputStream in = Objects.requireNonNull(getClass().getResourceAsStream("/modules/warps.conf"))) {
            shipped = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(SignWarpsConfiguration.load(
                        HoconConfigurationLoader.builder().buildAndLoadString(shipped)))
                .isEqualTo(SignWarpsConfiguration.defaultConfiguration());
        assertThat(SignWarpsConfiguration.load(HoconConfigurationLoader.builder()
                        .buildAndLoadString("warps { sign { enabled = false, header = \"  \" } }")))
                .isEqualTo(new SignWarpsConfiguration(false, "[islandwarp]"));
    }

    @Test
    @DisplayName("The header marks a sign in any case and with spaces round it, and only while it is on")
    void marks() {
        SignWarpsConfiguration on = new SignWarpsConfiguration(true, " [Go] ");
        assertThat(on.marks("[go]")).isTrue();
        assertThat(on.marks("  [GO] ")).isTrue();
        assertThat(on.marks("[go]!")).isFalse();
        assertThat(new SignWarpsConfiguration(false, "[go]").marks("[go]")).isFalse();
    }
}
