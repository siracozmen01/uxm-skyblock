package com.uxplima.uxmskyblock.bukkit.stack;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import com.uxplima.uxmlib.packet.display.FakeHologramPackets;
import com.uxplima.uxmlib.packet.display.FakeRegionScheduler;
import com.uxplima.uxmlib.packet.display.HologramProbe;
import com.uxplima.uxmlib.packet.display.PacketLine;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * A stack's line is sent to whoever is near it and never spawned: drawn from the chunk's own data as the chunk loads,
 * redrawn in place as the stack grows, and taken down as the chunk unloads.
 */
class PacketStackWorldTest extends MockBukkitHarness {

    @Test
    @DisplayName("A stack's line is drawn from its chunk as it loads, follows the stack, and goes as it unloads")
    void theLineFollowsTheChunkAndTheStack() {
        World world = server.addSimpleWorld("skyblock_world");
        PlayerMock reader = createPlayer("Reader");
        reader.teleport(new Location(world, 3, 65, 3));
        Block block = world.getBlockAt(1, 64, 1);
        BlockStacks.set(block, 5);
        FakeHologramPackets packets = new FakeHologramPackets();
        FakeRegionScheduler frames = new FakeRegionScheduler();
        PacketStackWorld lines = new PacketStackWorld(Messages.bundled(), frames, packets);

        lines.onChunkLoad(new ChunkLoadEvent(block.getChunk(), false));
        frames.firstFrame();
        String five = textHeldBy(packets, reader);
        lines.show(block, 7);
        frames.tick();
        String seven = textHeldBy(packets, reader);

        assertThat(five).isEqualTo("×5");
        assertThat(seven).describedAs("redrawn in place").isEqualTo("×7");
        assertThat(world.getEntitiesByClass(TextDisplay.class)).isEmpty();

        lines.onChunkUnload(new ChunkUnloadEvent(block.getChunk()));
        assertThat(lines.showing()).isZero();
        assertThat(new HologramProbe(packets).heldBy(reader)).isEmpty();
    }

    @Test
    @DisplayName("A block of one has no line")
    void aBlockOfOneHasNoLine() {
        World world = server.addSimpleWorld("skyblock_world");
        Block block = world.getBlockAt(1, 64, 1);
        PacketStackWorld lines =
                new PacketStackWorld(Messages.bundled(), new FakeRegionScheduler(), new FakeHologramPackets());

        lines.show(block, 3);
        lines.show(block, 1);

        assertThat(lines.showing()).isZero();
    }

    private static String textHeldBy(FakeHologramPackets packets, PlayerMock viewer) {
        List<PacketLine> held = new HologramProbe(packets).heldBy(viewer);
        assertThat(held).hasSize(1);
        return PlainTextComponentSerializer.plainText().serialize(((PacketLine.Text) held.get(0)).text());
    }
}
