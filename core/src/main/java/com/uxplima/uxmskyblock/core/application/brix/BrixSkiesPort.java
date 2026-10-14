package com.uxplima.uxmskyblock.core.application.brix;

import java.util.Map;

import com.uxplima.uxmskyblock.core.domain.brix.PlotSky;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/** Where the time and weather each Brix plot's team set are kept. */
public interface BrixSkiesPort {

    /** Every plot whose sky is not its world's. */
    Map<IslandId, PlotSky> findAll();

    /** Keeps a plot's sky; the world's own sky keeps nothing. */
    void save(IslandId islandId, PlotSky sky);
}
