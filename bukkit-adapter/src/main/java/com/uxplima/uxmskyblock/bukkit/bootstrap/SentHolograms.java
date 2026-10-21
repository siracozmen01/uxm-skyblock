package com.uxplima.uxmskyblock.bukkit.bootstrap;

import java.util.Objects;

import org.bukkit.plugin.java.JavaPlugin;

import com.uxplima.uxmlib.packet.display.HologramPackets;
import com.uxplima.uxmlib.packet.display.PacketHologramClicks;
import com.uxplima.uxmlib.packet.display.ServerHologramPackets;
import com.uxplima.uxmlib.scheduler.PaperScheduler;
import com.uxplima.uxmlib.scheduler.Scheduler;

/**
 * What a hologram a player only reads is sent with: the scheduler its frames run on, the packets, and the router that
 * answers a click on one. A router answers only the holograms registered with it, so each feature may hold its own.
 */
record SentHolograms(Scheduler regions, HologramPackets packets, PacketHologramClicks clicks) {

    SentHolograms {
        Objects.requireNonNull(regions, "regions must not be null");
        Objects.requireNonNull(packets, "packets must not be null");
        Objects.requireNonNull(clicks, "clicks must not be null");
    }

    /** The set for {@code plugin}. Its click router answers nothing until it is registered as a listener. */
    static SentHolograms of(JavaPlugin plugin) {
        Scheduler regions = new PaperScheduler(plugin);
        return new SentHolograms(regions, ServerHologramPackets.orNone(plugin), new PacketHologramClicks(regions));
    }
}
