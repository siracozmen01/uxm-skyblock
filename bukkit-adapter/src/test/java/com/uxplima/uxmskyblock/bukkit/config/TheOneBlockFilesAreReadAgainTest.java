package com.uxplima.uxmskyblock.bukkit.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.uxplima.uxmskyblock.bukkit.bootstrap.SkyblockReloader;
import com.uxplima.uxmskyblock.bukkit.i18n.MessageProvider;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.domain.oneblock.OneBlockPhase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

/**
 * The OneBlock files are read again on an operator's reload, and every line of them that cannot be read as written
 * is said with the file it is in and the line it stands on, at the start as at a reload.
 */
class TheOneBlockFilesAreReadAgainTest extends MockBukkitHarness {

    @TempDir
    Path dataDir;

    private void write(String path, String text) throws Exception {
        Path file = dataDir.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private void files() throws Exception {
        write("modules/oneblock.conf", """
                # The phases.
                phases = [
                    "plains",
                    "cave",
                    "ghost"
                ]
                creature-warning = -3
                """);
        write("oneblock/phases/plains.conf", """
                # Plains. NOPE is a block of another server, and a comment is no line.
                length = 40
                blocks {
                    DIRT = 4
                    NOPE = 1
                }
                """);
        write("oneblock/phases/cave.conf", """
                preview = "A dark cave"
                length = 30
                blocks { STONE = 1 }
                biome = "moonbase"
                """);
    }

    @Test
    @DisplayName("Each line not read as written is said with its file and line, and the rest is read")
    void placed() throws Exception {
        files();

        OneBlockLoadReport.Read read = OneBlockLoadReport.read(dataDir);

        assertThat(read.config())
                .get()
                .satisfies(config -> assertThat(config.phases().phases())
                        .extracting(OneBlockPhase::key)
                        .containsExactly("plains", "cave"));
        assertThat(read.problems())
                .anySatisfy(line -> assertThat(line)
                        .startsWith("oneblock/phases/plains.conf:5: ")
                        .contains("NOPE"))
                .anySatisfy(line -> assertThat(line)
                        .startsWith("oneblock/phases/cave.conf:4: ")
                        .contains("moonbase"))
                .anySatisfy(line ->
                        assertThat(line).startsWith("modules/oneblock.conf:5: ").contains("ghost"))
                .anySatisfy(line ->
                        assertThat(line).startsWith("modules/oneblock.conf:7: ").contains("-3"))
                .hasSize(4);
    }

    @Test
    @DisplayName("A module file that will not parse gives nothing to go on with, and says why")
    void unparsed() throws Exception {
        write("modules/oneblock.conf", "phases = [ \"plains\"\n");

        OneBlockLoadReport.Read read = OneBlockLoadReport.read(dataDir);

        assertThat(read.config()).isEmpty();
        assertThat(read.problems()).singleElement().asString().startsWith("modules/oneblock.conf: ");
    }

    @Test
    @DisplayName("The start says the same lines, placed the same way, and nothing more")
    void atTheStart() throws Exception {
        files();
        OneBlockConfiguration.Loaded loaded = OneBlockConfiguration.loadReporting(
                HoconConfigurationLoader.builder()
                        .path(dataDir.resolve("modules/oneblock.conf"))
                        .build()
                        .load(),
                dataDir.resolve("oneblock/phases"));

        assertThat(OneBlockLoadReport.say(dataDir, loaded))
                .isEqualTo(OneBlockLoadReport.read(dataDir).problems());
        assertThat(OneBlockConfiguration.load(HoconConfigurationLoader.builder()
                                .path(dataDir.resolve("modules/oneblock.conf"))
                                .build()
                                .load())
                        .phases()
                        .phases())
                .describedAs("a load that is not reporting still reads")
                .isNotEmpty();
    }

    @Test
    @DisplayName("A reload reads each mode's files and adds what they could not read to its report")
    void reloaded() throws Exception {
        SkyblockReloader.ReloadReport report = new SkyblockReloader(
                        new MessageProvider("en"),
                        dataDir,
                        null,
                        List.of(files -> List.of("oneblock/phases/plains.conf:5: NOPE"), files -> {
                            throw new IllegalStateException("cave.conf is gone");
                        }))
                .reload();

        assertThat(report.failures()).containsExactly("oneblock/phases/plains.conf:5: NOPE", "cave.conf is gone");
        assertThat(report.clean()).isFalse();
    }
}
