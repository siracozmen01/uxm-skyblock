package com.uxplima.uxmskyblock.bukkit.oneblock;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;

import net.kyori.adventure.text.Component;

import com.uxplima.uxmlib.packet.display.HologramAppearance;
import com.uxplima.uxmlib.packet.display.HologramPackets;
import com.uxplima.uxmlib.packet.display.PacketHologram;
import com.uxplima.uxmlib.scheduler.Scheduler;
import org.jspecify.annotations.Nullable;

/**
 * A line over a block, sent to whoever is near it in their own words and never spawned. The words are asked of each
 * viewer on every frame, so a countdown counts down and a progress line moves without anything resending it, and the
 * world holds no entity to save, lose to a chunk unload or leave behind after a restart.
 */
public final class SentLines implements OneBlockPauses.Lines {

    private static final HologramAppearance LOOK = HologramAppearance.defaults()
            .withBillboard(Display.Billboard.CENTER)
            .withAlignment(TextDisplay.TextAlignment.CENTER);

    private final Scheduler scheduler;
    private final HologramPackets packets;

    public SentLines(Scheduler scheduler, HologramPackets packets) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.packets = Objects.requireNonNull(packets, "packets must not be null");
    }

    @Override
    public OneBlockPauses.Shown show(World world, Location at, Function<Player, Component> line) {
        Objects.requireNonNull(at, "at must not be null");
        Said said = new Said(Objects.requireNonNull(line, "line must not be null"), scheduler, at.clone());
        said.hologram = PacketHologram.show(packets, scheduler, at, LOOK, viewer -> List.of(said.line.apply(viewer)));
        return said;
    }

    /**
     * One line up: the words it says, which a change swaps and every frame reads, and the hologram saying them. It
     * is taken down on the thread that owns its block, since a line may be taken away from any thread, such as when
     * its island is deleted.
     */
    private static final class Said implements OneBlockPauses.Shown {

        private final Scheduler scheduler;
        private final Location at;
        private volatile Function<Player, Component> line;
        private volatile boolean removed;
        private @Nullable PacketHologram hologram;

        private Said(Function<Player, Component> line, Scheduler scheduler, Location at) {
            this.line = line;
            this.scheduler = scheduler;
            this.at = at;
        }

        @Override
        public void say(Function<Player, Component> next) {
            this.line = Objects.requireNonNull(next, "next must not be null");
        }

        @Override
        public void remove() {
            removed = true;
            PacketHologram shown = hologram;
            if (shown != null) {
                var unused = scheduler.region(at, shown::remove);
            }
        }

        @Override
        public boolean showing() {
            return !removed;
        }
    }
}
