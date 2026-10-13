package dev.lone.itemsadder.api;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Location;

import org.jspecify.annotations.Nullable;

/** A stand in for ItemsAdder's block API, written for the tests: it knows one block and records each placing. */
public final class CustomBlock {

    public static final List<String> PLACED = new ArrayList<>();

    private CustomBlock() {}

    public static @Nullable CustomBlock place(String namespacedId, Location location) {
        if (!namespacedId.equals("myitems:ruby_ore")) {
            return null;
        }
        PLACED.add(namespacedId + " " + location.getBlockX() + "," + location.getBlockY() + "," + location.getBlockZ());
        return new CustomBlock();
    }
}
