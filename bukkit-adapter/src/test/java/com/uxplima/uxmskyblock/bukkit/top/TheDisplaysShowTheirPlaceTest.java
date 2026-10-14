package com.uxplima.uxmskyblock.bukkit.top;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.test.InlineSchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.leaderboard.LeaderboardCategory;
import com.uxplima.uxmskyblock.core.domain.leaderboard.LeaderboardEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** SuperiorSkyblock2 and IridiumSkyblock put the top islands on signs, heads and holograms; here only in a window. */
class TheDisplaysShowTheirPlaceTest {

    @TempDir
    @SuppressWarnings("NullAway.Init")
    Path folder;

    private final UUID ann = UUID.randomUUID();
    private final IslandId named = IslandId.of(UUID.randomUUID());
    private final IslandId anns = IslandId.of(UUID.randomUUID());
    private final List<String> asked = new ArrayList<>();
    private final List<String> painted = new ArrayList<>();
    private TopDisplays displays;
    private TopDisplayBeat beat;

    @BeforeEach
    void setUp() {
        displays = TopDisplays.read(folder.resolve("top-displays.conf"));
        Map<LeaderboardCategory, List<LeaderboardEntry>> boards = Map.of(
                LeaderboardCategory.LEVEL,
                List.of(
                        new LeaderboardEntry(1, named, "Sunrise", 42, "42", true),
                        new LeaderboardEntry(2, anns, "x", 7, "7", false)),
                LeaderboardCategory.BANK,
                List.of(new LeaderboardEntry(1, anns, "x", 123_45, "123.45", false)));
        beat = new TopDisplayBeat(
                displays,
                (board, rank) -> {
                    asked.add(board + " " + rank);
                    return boards.getOrDefault(board, List.of()).stream()
                            .filter(entry -> entry.rank() <= rank)
                            .toList();
                },
                island -> island.equals(anns) ? Optional.of(ann) : Optional.empty(),
                player -> player.equals(ann) ? Optional.of("Ann") : Optional.empty(),
                Messages.bundled(),
                new InlineSchedulerPort(),
                new TopPainter() {
                    @Override
                    public void paint(TopDisplay display, Optional<Shown> shown) {
                        painted.add(display.rank() + " " + display.board() + ": "
                                + shown.map(it -> it.island() + " / " + it.ownerName() + " / " + it.score() + " / "
                                                + it.board() + " / " + it.owner())
                                        .orElse("nobody"));
                    }

                    @Override
                    public void clear(TopDisplay display) {}
                });
    }

    @Test
    @DisplayName("Each board is read once, as deep as its deepest display, and each display draws its own place")
    void eachDisplayItsPlace() {
        displays.add(new TopDisplay("w", 0, 0, 0, TopDisplay.Kind.SIGN, LeaderboardCategory.LEVEL, 1));
        displays.add(new TopDisplay("w", 0, 0, 1, TopDisplay.Kind.HEAD, LeaderboardCategory.LEVEL, 2));
        displays.add(new TopDisplay("w", 0, 0, 2, TopDisplay.Kind.TEXT, LeaderboardCategory.LEVEL, 5));
        displays.add(new TopDisplay("w", 0, 0, 3, TopDisplay.Kind.TEXT, LeaderboardCategory.BANK, 1));

        beat.refresh();

        assertThat(asked).containsExactlyInAnyOrder("LEVEL 5", "BANK 1");
        assertThat(painted)
                .containsExactlyInAnyOrder(
                        "1 LEVEL: Sunrise /  / Level 42 / level / null",
                        "2 LEVEL: Ann's island / Ann / Level 7 / level / " + ann,
                        "5 LEVEL: nobody",
                        "1 BANK: Ann's island / Ann / 123.45 / bank / " + ann);
    }

    @Test
    @DisplayName("A display placed now is drawn from its own board at once")
    void oneDisplayAtOnce() {
        TopDisplay one = new TopDisplay("w", 0, 0, 0, TopDisplay.Kind.SIGN, LeaderboardCategory.BANK, 1);
        beat.refresh(one);
        assertThat(asked).containsExactly("BANK 1");
        assertThat(painted).hasSize(1);
    }

    @Test
    @DisplayName("A place a board skips shows nobody, not the island after it")
    void aSkippedPlaceShowsNobody() {
        TopDisplayBeat gappy = new TopDisplayBeat(
                displays,
                (board, rank) -> List.of(new LeaderboardEntry(3, named, "Sunrise", 9, "9", true)),
                island -> Optional.empty(),
                player -> Optional.empty(),
                Messages.bundled(),
                new InlineSchedulerPort(),
                new TopPainter() {
                    @Override
                    public void paint(TopDisplay display, Optional<Shown> shown) {
                        painted.add(
                                display.rank() + ": " + shown.map(Shown::island).orElse("nobody"));
                    }

                    @Override
                    public void clear(TopDisplay display) {}
                });
        gappy.refresh(new TopDisplay("w", 0, 0, 0, TopDisplay.Kind.SIGN, LeaderboardCategory.LEVEL, 2));
        assertThat(painted).containsExactly("2: nobody");
    }
}
