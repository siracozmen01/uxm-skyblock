package com.uxplima.uxmskyblock.bukkit.stack;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.ItemStack;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import com.uxplima.uxmlib.packet.display.HologramAppearance;
import com.uxplima.uxmlib.packet.display.HologramPackets;
import com.uxplima.uxmlib.packet.display.PacketHologram;
import com.uxplima.uxmlib.scheduler.Scheduler;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import org.jspecify.annotations.Nullable;

/**
 * A stack's line is sent to whoever is near it, in their own language, and never spawned. The size lives in the
 * chunk's own data, so a line is drawn from it as the chunk loads and taken down as it unloads; the world holds no
 * entity for it to save, lose or find again. The block given back drops where the stack stands.
 */
public final class PacketStackWorld implements StackWorld, Listener {

    private static final double LINE_RISE = 1.25;

    private static final HologramAppearance LOOK = HologramAppearance.defaults()
            .withBillboard(Display.Billboard.CENTER)
            .withAlignment(TextDisplay.TextAlignment.CENTER);

    private final Messages messages;
    private final Scheduler scheduler;
    private final HologramPackets packets;
    private final Map<Where, Line> lines = new ConcurrentHashMap<>();

    /** A block of a world. */
    private record Where(String world, int x, int y, int z) {

        boolean in(Chunk chunk) {
            return world.equals(chunk.getWorld().getName()) && x >> 4 == chunk.getX() && z >> 4 == chunk.getZ();
        }
    }

    /** One line up: the size it says, which a change sets and every frame reads, and the hologram saying it. */
    private static final class Line {

        private volatile int size;
        private @Nullable PacketHologram hologram;

        private Line(int size) {
            this.size = size;
        }

        private void takeDown() {
            PacketHologram shown = hologram;
            if (shown != null) {
                shown.remove();
            }
        }
    }

    public PacketStackWorld(Messages messages, Scheduler scheduler, HologramPackets packets) {
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.packets = Objects.requireNonNull(packets, "packets must not be null");
    }

    @Override
    public void giveBack(Block block, Player breaker) {
        if (!block.getDrops(breaker.getInventory().getItemInMainHand(), breaker).isEmpty()) {
            block.getWorld().dropItemNaturally(block.getLocation().add(0.5, 0.5, 0.5), new ItemStack(block.getType()));
        }
    }

    /** Shows {@code size} on the line above {@code block}, or takes the line away for a block of one. */
    @Override
    public void show(Block block, int size) {
        show(block.getWorld(), block.getX(), block.getY(), block.getZ(), size);
    }

    /** A chunk loads: every stack in it shows its line again. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        Chunk chunk = event.getChunk();
        BlockStacks.in(chunk).forEach((place, size) -> show(chunk.getWorld(), place.x(), place.y(), place.z(), size));
    }

    /** A chunk unloads: its lines come down with it. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnload(ChunkUnloadEvent event) {
        Chunk chunk = event.getChunk();
        lines.entrySet().removeIf(entry -> {
            if (!entry.getKey().in(chunk)) {
                return false;
            }
            entry.getValue().takeDown();
            return true;
        });
    }

    /** How many lines are up, which a test reads. */
    public int showing() {
        return lines.size();
    }

    private void show(World world, int x, int y, int z, int size) {
        Where where = new Where(world.getName(), x, y, z);
        if (size <= 1) {
            Line gone = lines.remove(where);
            if (gone != null) {
                gone.takeDown();
            }
            return;
        }
        Line held = lines.get(where);
        if (held != null) {
            held.size = size;
            return;
        }
        lines.computeIfAbsent(where, at -> lineAt(new Location(world, x + 0.5, y + LINE_RISE, z + 0.5), size));
    }

    private Line lineAt(Location above, int size) {
        Line line = new Line(size);
        line.hologram =
                PacketHologram.show(packets, scheduler, above, LOOK, viewer -> List.of(lineFor(viewer, line.size)));
        return line;
    }

    private Component lineFor(Player viewer, int size) {
        return messages.render(viewer, "stacking.line", Placeholder.unparsed("size", Integer.toString(size)));
    }
}
