package com.uxplima.uxmskyblock.bukkit.acid;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

import org.bukkit.Location;
import org.bukkit.World;

import com.uxplima.uxmskyblock.bukkit.config.AcidIslandConfiguration;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.core.application.acid.AcidIslandService;
import com.uxplima.uxmskyblock.core.domain.island.Island;

/**
 * Where water is acid, and at what height its sea stands: on an AcidIsland island, at the island's own sea level,
 * and, with a sea between islands, anywhere in its world, at that sea's surface. Memory only.
 */
final class AcidWaters {

    private final AcidIslandService service;
    private final IslandProtectionListener islands;
    private final AcidIslandConfiguration.Between between;

    AcidWaters(AcidIslandService service, IslandProtectionListener islands, AcidIslandConfiguration.Between between) {
        this.service = Objects.requireNonNull(service, "service must not be null");
        this.islands = Objects.requireNonNull(islands, "islands must not be null");
        this.between = Objects.requireNonNull(between, "between must not be null");
    }

    /** The surface of the acid sea at {@code at}, or nothing where the water is not acid. */
    OptionalInt surface(Location at) {
        Optional<Island> island = islands.findIslandAt(at);
        if (island.isPresent()) {
            OptionalInt level = service.seaLevel(island.get().id());
            if (level.isPresent()) {
                return level;
            }
        }
        World world = at.getWorld();
        return world != null && between.runsThrough(world.getName())
                ? OptionalInt.of(between.surface())
                : OptionalInt.empty();
    }

    /** Whether water at {@code at} is acid. */
    boolean acid(Location at) {
        return surface(at).isPresent();
    }

    /** Whether the sea runs on between the islands of {@code world}. */
    boolean openSea(World world) {
        return between.runsThrough(world.getName());
    }

    AcidIslandConfiguration.Between between() {
        return between;
    }
}
