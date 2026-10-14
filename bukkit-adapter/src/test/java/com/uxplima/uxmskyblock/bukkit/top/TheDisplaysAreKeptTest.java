package com.uxplima.uxmskyblock.bukkit.top;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;

import com.uxplima.uxmskyblock.core.domain.leaderboard.LeaderboardCategory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TheDisplaysAreKeptTest {

    @TempDir
    @SuppressWarnings("NullAway.Init")
    Path folder;

    @Test
    @DisplayName("Displays are written as placed, read back after a restart, one to a block, and taken away")
    void keptAcrossARestart() {
        Path file = folder.resolve("data").resolve("top-displays.conf");
        TopDisplays first = TopDisplays.read(file);
        assertThat(first.all()).isEmpty();

        TopDisplay sign = new TopDisplay("world", 1, 64, -2, TopDisplay.Kind.SIGN, LeaderboardCategory.LEVEL, 1);
        first.add(sign);
        first.add(new TopDisplay("world", 5, 70, 5, TopDisplay.Kind.TEXT, LeaderboardCategory.BANK, 3));
        first.add(new TopDisplay("world", 1, 64, -2, TopDisplay.Kind.SIGN, LeaderboardCategory.WORTH, 2));

        TopDisplays again = TopDisplays.read(file);
        assertThat(again.all())
                .containsExactlyInAnyOrder(
                        new TopDisplay("world", 5, 70, 5, TopDisplay.Kind.TEXT, LeaderboardCategory.BANK, 3),
                        new TopDisplay("world", 1, 64, -2, TopDisplay.Kind.SIGN, LeaderboardCategory.WORTH, 2));

        assertThat(again.remove("world", 5, 71, 5))
                .describedAs("another height")
                .isEmpty();
        assertThat(again.remove("world", 5, 70, 5)).isPresent();
        assertThat(again.remove("world", 5, 70, 5)).isEmpty();
        assertThat(TopDisplays.read(file).all())
                .extracting(TopDisplay::board)
                .containsExactly(LeaderboardCategory.WORTH);
        assertThat(again.remove("world", 1, 64, -2)).isPresent();
        assertThat(TopDisplays.read(file).all()).isEmpty();
    }

    @Test
    @DisplayName("A line that cannot be read is left out and the rest are kept")
    void anUnreadableLineIsLeftOut() throws Exception {
        Path file = folder.resolve("top-displays.conf");
        Files.writeString(file, """
                displays = [
                    { world = "world", x = 1, y = 2, z = 3, kind = "head", board = "level", rank = 4 }
                    { world = "world", x = 1, y = 2, z = 4, kind = "statue", board = "level", rank = 4 }
                    { world = "world", x = 1, y = 2, z = 5, kind = "sign", board = "fame", rank = 4 }
                    { world = "world", x = 1, y = 2, z = 6, kind = "sign", board = "worth", rank = 0 }
                ]
                """);
        assertThat(TopDisplays.read(file).all())
                .containsExactly(new TopDisplay("world", 1, 2, 3, TopDisplay.Kind.HEAD, LeaderboardCategory.LEVEL, 4));
    }

    @Test
    @DisplayName("A rank is from one to the most, and a kind is named in any case")
    void ranksAndKinds() {
        assertThatThrownBy(() -> new TopDisplay("w", 0, 0, 0, TopDisplay.Kind.TEXT, LeaderboardCategory.LEVEL, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TopDisplay(
                        "w", 0, 0, 0, TopDisplay.Kind.TEXT, LeaderboardCategory.LEVEL, TopDisplay.MOST_RANK + 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new TopDisplay("w", 0, 0, 0, TopDisplay.Kind.TEXT, LeaderboardCategory.LEVEL, TopDisplay.MOST_RANK)
                        .rank())
                .isEqualTo(100);
        assertThat(TopDisplay.Kind.named(" Head ")).contains(TopDisplay.Kind.HEAD);
        assertThat(TopDisplay.Kind.named("statue")).isEmpty();
        assertThat(TopDisplay.Kind.TEXT.word()).isEqualTo("text");
    }
}
