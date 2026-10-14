package com.uxplima.uxmskyblock.bukkit.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

class VisitorsConfigurationTest {

    @Test
    @DisplayName("The shipped visitors.conf reads as the fallback, and none written blocks nothing")
    void theShippedFileIsTheFallback() throws Exception {
        String shipped;
        try (InputStream in = Objects.requireNonNull(getClass().getResourceAsStream("/modules/visitors.conf"))) {
            shipped = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(VisitorsConfiguration.load(HoconConfigurationLoader.builder().buildAndLoadString(shipped)))
                .isEqualTo(VisitorsConfiguration.defaultConfiguration());
        assertThat(VisitorsConfiguration.load(HoconConfigurationLoader.builder()
                                .buildAndLoadString("visitors { blocked-commands = [] }"))
                        .blockedCommands())
                .isEmpty();
    }

    @Test
    @DisplayName("A command is blocked by its name, with the words after it, in any case, with or without its prefix")
    void whatIsBlocked() {
        VisitorsConfiguration rules = new VisitorsConfiguration(List.of(" /SetHome ", "home  set", ""));
        assertThat(rules.blockedCommands()).containsExactly("sethome", "home set");

        assertThat(rules.blocking("/sethome")).contains("sethome");
        assertThat(rules.blocking("/SETHOME base")).contains("sethome");
        assertThat(rules.blocking("/essentials:sethome base")).contains("sethome");
        assertThat(rules.blocking("/home   set   farm")).contains("home set");
        assertThat(rules.blocking("/home")).isEmpty();
        assertThat(rules.blocking("/home farm")).isEmpty();
        assertThat(rules.blocking("/sethomes")).isEmpty();
        assertThat(rules.blocking("/is home")).isEmpty();
    }
}
