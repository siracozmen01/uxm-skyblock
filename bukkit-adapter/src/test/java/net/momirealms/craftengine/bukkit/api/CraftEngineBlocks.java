package net.momirealms.craftengine.bukkit.api;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Location;

import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.UpdateOption;

/** A stand in for CraftEngine's block API, written for the tests: it records each placing with what it was given. */
public final class CraftEngineBlocks {

    public static final List<String> PLACED = new ArrayList<>();

    private CraftEngineBlocks() {}

    public static boolean place(Location location, Key block, UpdateOption option, boolean playSound) {
        if (block.value().endsWith(":nothing")) {
            return false;
        }
        PLACED.add(block.value() + " " + location.getBlockX() + " " + (option == UpdateOption.UPDATE_ALL) + " "
                + playSound);
        return true;
    }
}
