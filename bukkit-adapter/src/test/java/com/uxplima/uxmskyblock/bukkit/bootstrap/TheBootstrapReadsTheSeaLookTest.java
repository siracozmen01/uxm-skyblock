package com.uxplima.uxmskyblock.bukkit.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import com.uxplima.uxmskyblock.bukkit.config.AcidIslandConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Before the server reads its data packs, the sea's look is read from the operator's file, or shipped when none. */
class TheBootstrapReadsTheSeaLookTest {

    @TempDir
    Path folder;

    @Test
    @DisplayName("The look is the operator's when the file is there and the shipped one when it is not yet")
    void theLookIsRead() throws Exception {
        Path file = folder.resolve("acidisland.conf");
        assertThat(UxMSkyblockBootstrap.lookIn(file)).isEqualTo(AcidIslandConfiguration.Look.SHIPPED);

        Files.writeString(file, "sea { look { water = \"#123456\" } }\n");
        assertThat(UxMSkyblockBootstrap.lookIn(file).water()).isEqualTo("#123456");
        assertThat(UxMSkyblockBootstrap.lookIn(file).sky()).isEqualTo(AcidIslandConfiguration.Look.SHIPPED.sky());
    }
}
