package com.uxplima.uxmskyblock.bukkit.oneblock;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.TextDisplay;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import com.uxplima.uxmlib.packet.display.FakeHologramPackets;
import com.uxplima.uxmlib.packet.display.FakeRegionScheduler;
import com.uxplima.uxmlib.packet.display.HologramProbe;
import com.uxplima.uxmlib.packet.display.PacketLine;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** A line over a OneBlock is sent to each reader in their own words, changes in place and never stands in the world. */
class SentLinesTest extends MockBukkitHarness {

    @Test
    @DisplayName("Each reader reads the line in their own words, a new line replaces it in place, and it comes down")
    void aLineIsSentToEachReader() {
        World world = server.addSimpleWorld("skyblock_world");
        PlayerMock ann = createPlayer("Ann");
        PlayerMock bob = createPlayer("Bob");
        ann.teleport(new Location(world, 1, 100, 1));
        bob.teleport(new Location(world, 1, 100, 1));
        FakeHologramPackets packets = new FakeHologramPackets();
        FakeRegionScheduler frames = new FakeRegionScheduler();

        OneBlockPauses.Shown shown = new SentLines(frames, packets)
                .show(world, new Location(world, 0.5, 102.2, 0.5), viewer -> Component.text("for " + viewer.getName()));
        frames.firstFrame();
        String annFirst = textHeldBy(packets, ann);
        String bobFirst = textHeldBy(packets, bob);
        shown.say(viewer -> Component.text("then " + viewer.getName()));
        frames.tick();
        String annThen = textHeldBy(packets, ann);

        assertThat(annFirst).isEqualTo("for Ann");
        assertThat(bobFirst).isEqualTo("for Bob");
        assertThat(annThen).describedAs("said again in place").isEqualTo("then Ann");
        assertThat(world.getEntitiesByClass(TextDisplay.class)).isEmpty();
        assertThat(shown.showing()).isTrue();

        shown.remove();
        assertThat(shown.showing()).isFalse();
        assertThat(new HologramProbe(packets).heldBy(ann)).isEmpty();
    }

    private static String textHeldBy(FakeHologramPackets packets, PlayerMock viewer) {
        List<PacketLine> held = new HologramProbe(packets).heldBy(viewer);
        assertThat(held).hasSize(1);
        return PlainTextComponentSerializer.plainText().serialize(((PacketLine.Text) held.get(0)).text());
    }
}
