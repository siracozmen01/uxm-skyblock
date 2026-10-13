package com.uxplima.uxmskyblock.bukkit.stranger;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Openable;
import org.bukkit.block.data.Powerable;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.player.PlayerInteractEvent;

import com.uxplima.uxmskyblock.bukkit.config.StrangerRealmsConfiguration;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.stranger.NamePattern;
import org.jspecify.annotations.Nullable;

/**
 * Switches linked across the veil: a lever or a button used on a StrangerRealms island passes its power to the
 * switch at the same place on the other side, and a door, trapdoor or gate opened or shut opens or shuts the one
 * there, on the land or in the Upside Down.
 *
 * <p>A lever's or a button's power is read from the server's own change of it, so a button let go on one side lets go
 * on the other. A door's new state is read on its own region once the use has opened or shut it, and set on the
 * thread of the region that owns the same place in the other world, with the game's updates, so what the switch
 * drives there moves too. Only a block of the same kind as the one used is changed.
 */
public final class LinkedSwitches implements Listener {

    private static final Logger LOGGER = Logger.getLogger(LinkedSwitches.class.getName());

    private final Realms realms;
    private final SchedulerPort scheduler;
    private final List<NamePattern> switches = new ArrayList<>();

    public LinkedSwitches(Realms realms, SchedulerPort scheduler, StrangerRealmsConfiguration.Switches written) {
        this.realms = Objects.requireNonNull(realms, "realms must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        for (String name : written.blocks()) {
            try {
                switches.add(new NamePattern(name));
            } catch (IllegalArgumentException e) {
                LOGGER.warning(() -> "modules/strangerrealms.conf switches blocks: " + name + " is no pattern.");
            }
        }
    }

    /** A lever or a button changed its power: the one across the veil takes the same. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPower(BlockRedstoneEvent event) {
        Block block = event.getBlock();
        if (!(block.getBlockData() instanceof Powerable)
                || block.getBlockData() instanceof Openable
                || !linked(block)) {
            return;
        }
        boolean powered = event.getNewCurrent() > 0;
        across(block, data -> {
            if (data instanceof Powerable switched) {
                switched.setPowered(powered);
                return data;
            }
            return null;
        });
    }

    /** A door, trapdoor or gate used: once the use has opened or shut it, the one across the veil does the same. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onUse(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK
                || block == null
                || event.useInteractedBlock() == Event.Result.DENY
                || !(block.getBlockData() instanceof Openable)
                || !linked(block)) {
            return;
        }
        // On the block's own region, after the use has opened or shut it.
        scheduler.onRegion(block.getWorld().getName(), block.getX() >> 4, block.getZ() >> 4, () -> {
            if (!(block.getBlockData() instanceof Openable used)) {
                return;
            }
            boolean open = used.isOpen();
            across(block, data -> {
                if (data instanceof Openable door) {
                    door.setOpen(open);
                    return data;
                }
                return null;
            });
        });
    }

    private boolean linked(Block block) {
        String name = block.getType().name();
        for (NamePattern pattern : switches) {
            if (pattern.matches(name)) {
                return true;
            }
        }
        return false;
    }

    /** What a switch across the veil becomes, or nothing to leave it. */
    @FunctionalInterface
    private interface Change {
        @Nullable BlockData of(BlockData there);
    }

    /** Changes the block of the same kind at the same place on the other side, on the thread that owns it. */
    private void across(Block block, Change change) {
        String other = realms.placeOf(block.getWorld().getName(), block.getX(), block.getZ())
                .map(place -> place.otherWorld(realms.upsideDownWorld()))
                .orElse(null);
        if (other == null) {
            return;
        }
        Material kind = block.getType();
        int x = block.getX();
        int y = block.getY();
        int z = block.getZ();
        scheduler.onRegion(other, x >> 4, z >> 4, () -> {
            World world = Bukkit.getWorld(other);
            if (world == null || y < world.getMinHeight() || y >= world.getMaxHeight()) {
                return;
            }
            Block there = world.getBlockAt(x, y, z);
            if (there.getType() != kind) {
                return;
            }
            BlockData changed = change.of(there.getBlockData().clone());
            if (changed != null) {
                // With the game's updates, so what the switch drives on that side moves as well.
                there.setBlockData(changed, true);
            }
        });
    }
}
