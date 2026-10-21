package com.uxplima.uxmskyblock.bukkit.top;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.TextDisplay;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import com.uxplima.uxmlib.packet.display.FakeHologramPackets;
import com.uxplima.uxmlib.packet.display.FakeRegionScheduler;
import com.uxplima.uxmlib.packet.display.HologramProbe;
import com.uxplima.uxmlib.packet.display.PacketLine;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.test.InlineSchedulerPort;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.leaderboard.LeaderboardCategory;
import com.uxplima.uxmskyblock.core.domain.leaderboard.LeaderboardEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * A text display is sent to each reader in their own language and never spawned: the board, the score and the line
 * around them are chosen as it is drawn, a new draw changes it in place, and taking the display away takes it down.
 */
class ATextDisplayIsSentToEachReaderTest extends MockBukkitHarness {

    @Test
    @DisplayName("Each reader reads a text display in their own language, redrawn in place and gone when taken away")
    void eachReaderReadsTheirOwn(@TempDir Path folder) {
        World world = server.addSimpleWorld("w");
        PlayerMock english = createPlayer("English");
        PlayerMock turkish = createPlayer("Turkish");
        turkish.setLocale(Locale.forLanguageTag("tr"));
        english.teleport(new Location(world, 2, 64, 2));
        turkish.teleport(new Location(world, 2, 64, 2));
        FakeHologramPackets packets = new FakeHologramPackets();
        FakeRegionScheduler frames = new FakeRegionScheduler();
        BukkitTopPainter painter = new BukkitTopPainter(Messages.bundled(), frames, packets);
        TopDisplay display = new TopDisplay("w", 0, 64, 0, TopDisplay.Kind.TEXT, LeaderboardCategory.LEVEL, 1);
        TopDisplayBeat beat = new TopDisplayBeat(
                TopDisplays.read(folder.resolve("top-displays.conf")),
                (board, rank) -> List.of(),
                island -> Optional.empty(),
                player -> Optional.empty(),
                Messages.bundled(),
                new InlineSchedulerPort(),
                painter);
        IslandId island = new IslandId(UUID.randomUUID());

        painter.paint(
                display,
                Optional.of(beat.shown(
                        LeaderboardCategory.LEVEL, new LeaderboardEntry(1, island, "Sunrise", 42, "42", true))));
        frames.firstFrame();
        String englishRead = textHeldBy(packets, english);
        String turkishRead = textHeldBy(packets, turkish);
        painter.paint(display, Optional.empty());
        frames.tick();
        String emptyRead = textHeldBy(packets, english);

        assertThat(englishRead).contains("level").contains("Sunrise").contains("Level 42");
        assertThat(turkishRead).contains("seviye").contains("Sunrise").contains("Seviye 42");
        assertThat(emptyRead).describedAs("redrawn in place").contains("Nobody yet");
        assertThat(painter.textsUp()).isEqualTo(1);
        assertThat(world.getEntitiesByClass(TextDisplay.class)).isEmpty();

        painter.clear(display);
        assertThat(painter.textsUp()).isZero();
        assertThat(new HologramProbe(packets).heldBy(english)).isEmpty();
    }

    private static String textHeldBy(FakeHologramPackets packets, PlayerMock viewer) {
        List<PacketLine> held = new HologramProbe(packets).heldBy(viewer);
        assertThat(held).hasSize(1);
        return PlainTextComponentSerializer.plainText().serialize(((PacketLine.Text) held.get(0)).text());
    }
}
