package com.nexomc.nexo.api;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Location;

/** A stand in for Nexo's block API, written for the tests: it records each placing. */
public final class NexoBlocks {

    public static final List<String> PLACED = new ArrayList<>();

    private NexoBlocks() {}

    public static void place(String itemId, Location location) {
        PLACED.add(itemId + " " + location.getBlockX() + "," + location.getBlockY() + "," + location.getBlockZ());
    }
}
