package com.uxplima.uxmskyblock.bukkit.chunkblock;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;

import net.kyori.adventure.text.Component;

import com.uxplima.uxmlib.packet.display.HologramAppearance;
import com.uxplima.uxmlib.packet.display.HologramPackets;
import com.uxplima.uxmlib.packet.display.PacketHologram;
import com.uxplima.uxmlib.packet.display.PacketHologramClicks;
import com.uxplima.uxmlib.scheduler.Scheduler;
import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkPos;

/**
 * The sign over a closed chunk, sent and never spawned: a line of text that turns to face whoever reads it, in their
 * own language, over a box a click lands on. The world holds no entity for it, so nothing is saved and nothing is
 * marked; the sign is held here under its world and chunk, comes down as its chunk unloads and is drawn again when
 * it loads.
 */
public final class ChunkSignHolograms implements SignPainter {

    /** How far away a sign is drawn, as a share of the distance a client draws entities at. */
    static final float VIEW_RANGE = 1.0f;

    /** The box a click lands on, in blocks. */
    static final float BOX_WIDTH = 1.2f;

    static final float BOX_HEIGHT = 2.2f;

    /** How far over the box the text floats. */
    static final double TEXT_RISE = 2.4;

    /** The look of every sign: a lit line facing its reader, seen through the wall it stands behind. */
    static final HologramAppearance LOOK = HologramAppearance.defaults()
            .withBillboard(Display.Billboard.CENTER)
            .withAlignment(TextDisplay.TextAlignment.CENTER)
            .withTextShadow(true)
            // It stands behind the wall where open ground ends, and a wall panel hides what is behind it.
            .withSeeThrough(true)
            .withViewRange(VIEW_RANGE)
            .withBrightness(15, 15);

    private final Scheduler scheduler;
    private final HologramPackets packets;
    private final PacketHologramClicks clicks;
    private final Map<Where, Standing> standing = new ConcurrentHashMap<>();

    /** A chunk of a world. */
    private record Where(String world, int x, int z) {}

    /** A sign up: whose it is, and the hologram drawing it. */
    private record Standing(String owner, PacketHologram hologram) {}

    public ChunkSignHolograms(Scheduler scheduler, HologramPackets packets, PacketHologramClicks clicks) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.packets = Objects.requireNonNull(packets, "packets must not be null");
        this.clicks = Objects.requireNonNull(clicks, "clicks must not be null");
    }

    @Override
    public void draw(
            World world,
            String owner,
            ChunkPos chunk,
            ChunkSigns.Spot spot,
            Function<Player, Component> text,
            Consumer<Player> clicked) {
        Objects.requireNonNull(world, "world must not be null");
        Objects.requireNonNull(owner, "owner must not be null");
        Objects.requireNonNull(text, "text must not be null");
        Objects.requireNonNull(clicked, "clicked must not be null");
        Where where = new Where(world.getName(), chunk.x(), chunk.z());
        takeDown(standing.remove(where));
        PacketHologram hologram = PacketHologram.show(
                packets,
                scheduler,
                new Location(world, spot.x(), spot.y() + TEXT_RISE, spot.z()),
                LOOK,
                viewer -> List.of(text.apply(viewer)));
        // The box stands on the floor of the spot, under the text that floats over it.
        hologram.setClickBox(BOX_WIDTH, BOX_HEIGHT, (float) TEXT_RISE);
        clicks.register(hologram, (player, attack) -> clicked.accept(player));
        standing.put(where, new Standing(owner, hologram));
    }

    @Override
    public int clear(Chunk chunk, String owner) {
        Objects.requireNonNull(owner, "owner must not be null");
        Where where = new Where(chunk.getWorld().getName(), chunk.getX(), chunk.getZ());
        Standing up = standing.get(where);
        if (up == null || !up.owner().equals(owner) || !standing.remove(where, up)) {
            return 0;
        }
        takeDown(up);
        return 1;
    }

    @Override
    public int unloaded(Chunk chunk) {
        Standing up = standing.remove(new Where(chunk.getWorld().getName(), chunk.getX(), chunk.getZ()));
        takeDown(up);
        return up == null ? 0 : 1;
    }

    /** How many signs are up, which a test and the doctor read. */
    public int standing() {
        return standing.size();
    }

    private void takeDown(@org.jspecify.annotations.Nullable Standing up) {
        if (up == null) {
            return;
        }
        clicks.unregister(up.hologram());
        up.hologram().remove();
    }
}
