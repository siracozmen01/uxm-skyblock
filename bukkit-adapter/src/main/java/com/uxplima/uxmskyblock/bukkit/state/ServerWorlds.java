package com.uxplima.uxmskyblock.bukkit.state;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import org.bukkit.Location;
import org.bukkit.World;

/**
 * The worlds a player's group is found by, as the configuration and the islands stand: the preset worlds, the
 * server's own island world, and in a dimension world every mode shares, the home world of the island a place
 * belongs to.
 */
public final class ServerWorlds implements PlayerStates.Worlds {

    /** Whether an island in {@code world} holds the block column at {@code x}, {@code z}. */
    public interface Islands {
        boolean islandAt(String world, int x, int z);
    }

    private final Supplier<List<String>> presetWorlds;
    private final Supplier<String> islandWorld;
    private final Supplier<? extends Collection<String>> dimensionWorlds;
    private final Supplier<List<String>> islandWorlds;
    private final Islands islands;

    /**
     * @param dimensionWorlds the Nether and End worlds islands of every preset share
     * @param islandWorlds every world islands are made in, the server's own first
     */
    public ServerWorlds(
            Supplier<List<String>> presetWorlds,
            Supplier<String> islandWorld,
            Supplier<? extends Collection<String>> dimensionWorlds,
            Supplier<List<String>> islandWorlds,
            Islands islands) {
        this.presetWorlds = Objects.requireNonNull(presetWorlds, "presetWorlds must not be null");
        this.islandWorld = Objects.requireNonNull(islandWorld, "islandWorld must not be null");
        this.dimensionWorlds = Objects.requireNonNull(dimensionWorlds, "dimensionWorlds must not be null");
        this.islandWorlds = Objects.requireNonNull(islandWorlds, "islandWorlds must not be null");
        this.islands = Objects.requireNonNull(islands, "islands must not be null");
    }

    @Override
    public List<String> presetWorlds() {
        return presetWorlds.get();
    }

    @Override
    public String islandWorld() {
        return islandWorld.get();
    }

    @Override
    public Optional<String> homeOf(Location place) {
        World world = place.getWorld();
        if (world == null || !dimensionWorlds.get().contains(world.getName())) {
            return Optional.empty();
        }
        // A dimension island stands where its island does, and no two islands share a centre in any world.
        for (String home : islandWorlds.get()) {
            if (islands.islandAt(home, place.getBlockX(), place.getBlockZ())) {
                return Optional.of(home);
            }
        }
        return Optional.empty();
    }
}
