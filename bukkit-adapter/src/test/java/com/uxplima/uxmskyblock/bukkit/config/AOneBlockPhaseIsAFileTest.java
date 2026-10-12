package com.uxplima.uxmskyblock.bukkit.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.domain.oneblock.OneBlockPhase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

/**
 * A OneBlock phase is a file of its own, listed by name in {@code modules/oneblock.conf}, and a file from before,
 * with its phases written inside the list, is moved into the files once and kept as it was beside itself.
 */
class AOneBlockPhaseIsAFileTest extends MockBukkitHarness {

    @TempDir
    Path dir;

    private static ConfigurationNode read(Path file) throws Exception {
        return HoconConfigurationLoader.builder().path(file).build().load();
    }

    @Test
    @DisplayName(
            "A listed name with no file, and one that reaches out of the folder, are left out; an unknown icon is replaced")
    void listedNames() throws Exception {
        Path folder = Files.createDirectories(dir.resolve("phases"));
        Files.writeString(folder.resolve("meadow.conf"), """
                icon = "NOT_AN_ITEM"
                length = 10
                blocks { DIRT = 1 }
                """);
        Files.writeString(dir.resolve("escape.conf"), "length = 5\nblocks { SAND = 1 }\n");
        Files.writeString(folder.resolve("cave.conf"), """
                icon = "stone"
                length = 20
                blocks { STONE = 1 }
                """);

        OneBlockConfiguration config = OneBlockConfiguration.load(
                HoconConfigurationLoader.builder()
                        .buildAndLoadString("phases = [\"meadow\", \"gone\", \"../escape\", \"cave\"]"),
                folder);

        assertThat(config.phases().phases()).extracting(OneBlockPhase::key).containsExactly("meadow", "cave");
        assertThat(config.iconOf("meadow")).isEqualTo(OneBlockConfiguration.DEFAULT_ICON);
        assertThat(config.iconOf("cave")).isEqualTo("STONE");
        assertThat(config.phases().phases().get(1).blocks()).isEqualTo(20);
    }

    @Test
    @DisplayName("Phases written in the list move into a file each, once, and the file as it was is kept")
    void movedOnce() throws Exception {
        Path file = dir.resolve("oneblock.conf");
        String old = """
                # What an island does after its last phase.
                after-last-phase = "repeat"
                phases = [
                    { name = "meadow", length = 10, blocks { DIRT = 2, GRASS_BLOCK = 1 }, creature-chance = 0.5, creatures { cow = 1 } }
                    { name = "Bad Name", length = 5, blocks { SAND = 1 } }
                    { name = "cave", length = 20, blocks { STONE = 1 } }
                ]
                """;
        Files.writeString(file, old);
        Path folder = dir.resolve("phases");
        Files.createDirectories(folder);
        Files.writeString(folder.resolve("cave.conf"), "length = 99\nblocks { COBBLESTONE = 1 }\n");
        OneBlockConfiguration before = OneBlockConfiguration.load(read(file));

        assertThat(OneBlockPhaseFiles.moveIntoFiles(file, folder))
                .describedAs("cave had its file")
                .isEqualTo(1);

        assertThat(Files.readString(dir.resolve("oneblock.conf.bak"))).isEqualTo(old);
        ConfigurationNode root = read(file);
        assertThat(root.node("after-last-phase").getString()).isEqualTo("repeat");
        assertThat(root.node("phases").childrenList().get(0).getString()).isEqualTo("meadow");
        assertThat(root.node("phases").childrenList().get(1).isMap())
                .describedAs("a name no file can have stays in the list")
                .isTrue();
        assertThat(root.node("phases").childrenList().get(2).getString()).isEqualTo("cave");
        ConfigurationNode meadow = read(folder.resolve("meadow.conf"));
        assertThat(meadow.node("name").virtual())
                .describedAs("the name is the file's")
                .isTrue();
        assertThat(meadow.node("length").getLong()).isEqualTo(10);

        OneBlockConfiguration after = OneBlockConfiguration.load(root, folder);
        assertThat(after.phases().phases())
                .extracting(OneBlockPhase::key)
                .containsExactly("meadow", "Bad Name", "cave");
        OneBlockPhase moved = after.phases().phases().getFirst();
        OneBlockPhase was = before.phases().phases().getFirst();
        assertThat(moved.blockPool().weights()).isEqualTo(was.blockPool().weights());
        assertThat(moved.creaturePool().weights()).isEqualTo(was.creaturePool().weights());
        assertThat(moved.creatureChance()).isEqualTo(was.creatureChance());
        assertThat(after.phases().phases().get(2).blocks())
                .describedAs("the file that was there is kept")
                .isEqualTo(99);

        String once = Files.readString(file);
        assertThat(OneBlockPhaseFiles.moveIntoFiles(file, folder)).isEqualTo(0);
        assertThat(OneBlockPhaseFiles.moveIntoFiles(dir.resolve("oneblock.conf"), folder))
                .isEqualTo(0);
        assertThat(Files.readString(file))
                .describedAs("a file of names is left alone")
                .isEqualTo(once);
        assertThat(Files.readString(dir.resolve("oneblock.conf.bak")))
                .describedAs("and the file as it was first stays kept")
                .isEqualTo(old);
    }
}
