package com.uxplima.uxmskyblock.bukkit.acid;

import java.util.Objects;
import java.util.logging.Logger;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;

import com.uxplima.uxmskyblock.bukkit.config.AcidIslandConfiguration;

/**
 * The sea between islands: each chunk the server makes in its world is laid with the acid sea as it loads, on the
 * thread that owns it, so the islands there stand in one sea that runs on as far as players go. A chunk the world
 * made before is left as it is.
 */
public final class AcidOpenSea implements Listener {

    private static final Logger LOGGER = Logger.getLogger(AcidOpenSea.class.getName());

    private final AcidSeaStart sea;
    private final AcidIslandConfiguration.Between between;

    public AcidOpenSea(AcidSeaStart sea, AcidIslandConfiguration.Between between) {
        this.sea = Objects.requireNonNull(sea, "sea must not be null");
        this.between = Objects.requireNonNull(between, "between must not be null");
        if (between.enabled()) {
            LOGGER.info(() -> "The acid sea runs on between the islands of " + between.world() + ", at "
                    + between.surface() + ", in each chunk the world makes from now.");
        }
    }

    /** Lays the sea over a chunk its world has just made, before the tide looks at it. */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onChunkLoad(ChunkLoadEvent event) {
        if (event.isNewChunk() && between.runsThrough(event.getWorld().getName())) {
            sea.layOpen(
                    event.getWorld(), event.getChunk().getX(), event.getChunk().getZ(), between.surface());
        }
    }
}
