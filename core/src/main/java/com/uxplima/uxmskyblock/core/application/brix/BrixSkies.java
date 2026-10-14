package com.uxplima.uxmskyblock.core.application.brix;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import com.uxplima.uxmskyblock.core.domain.brix.PlotSky;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/** The time and weather each Brix plot's team set, held in memory so a player's beat never waits on the database. */
public final class BrixSkies {

    private final BrixSkiesPort port;
    private final Map<IslandId, PlotSky> skies = new ConcurrentHashMap<>();

    public BrixSkies(BrixSkiesPort port) {
        this.port = Objects.requireNonNull(port, "port must not be null");
    }

    /** Reads every plot's sky into memory. Off the main thread, when the server starts. */
    public int prime() {
        Map<IslandId, PlotSky> all = port.findAll();
        skies.putAll(all);
        return all.size();
    }

    /** The sky of a plot: the world's when its team set none. Memory only. */
    public PlotSky of(IslandId islandId) {
        return skies.getOrDefault(islandId, PlotSky.WORLD);
    }

    /** Sets a plot's sky. Off the main thread: it writes a row. */
    public void set(IslandId islandId, PlotSky sky) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(sky, "sky must not be null");
        port.save(islandId, sky);
        if (sky.isWorld()) {
            skies.remove(islandId);
        } else {
            skies.put(islandId, sky);
        }
    }
}
