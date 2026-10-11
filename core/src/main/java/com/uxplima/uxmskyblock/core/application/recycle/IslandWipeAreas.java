package com.uxplima.uxmskyblock.core.application.recycle;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.function.Function;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.recycle.IslandFootprint;

/**
 * Every square of every world an island is cleared over when it goes.
 *
 * <p>An island was cleared over its border, in its own world. A SkyGrid's grid reaches past the border of the island
 * a player starts with, an Upside Down is laid in the Nether, a Boxed island's box grows past its border, and water,
 * lava and trees reach over the edge. All of it stayed, and the next island in the same place found it. An island is
 * now cleared over its border and every square its game mode wrote in, each as far as the furthest of them, its
 * square in each of its other worlds as far as its border, and a margin the operator sets past that.
 */
public final class IslandWipeAreas {

    private final IslandFootprintPort footprints;
    private final Function<IslandId, OptionalInt> grownTo;
    private final int margin;

    /**
     * @param footprints where islands wrote past their borders
     * @param grownTo how far an island's game mode has grown it past its border now, as a Boxed box has
     * @param margin how far past every square the clearing goes
     */
    public IslandWipeAreas(IslandFootprintPort footprints, Function<IslandId, OptionalInt> grownTo, int margin) {
        this.footprints = Objects.requireNonNull(footprints, "footprints must not be null");
        this.grownTo = Objects.requireNonNull(grownTo, "grownTo must not be null");
        if (margin < 0) {
            throw new IllegalArgumentException("margin must not be negative: " + margin);
        }
        this.margin = margin;
    }

    /** The island's border and nothing past it, as before anything kept footprints. */
    public static IslandWipeAreas bordersOnly() {
        return new IslandWipeAreas(IslandFootprintPort.none(), island -> OptionalInt.empty(), 0);
    }

    /** One square of one world to clear. */
    public record Area(String world, IslandBounds bounds) {
        public Area {
            Objects.requireNonNull(world, "world must not be null");
            Objects.requireNonNull(bounds, "bounds must not be null");
        }
    }

    /**
     * The squares to clear for the island whose border is {@code bounds} in {@code world}: that border and every
     * footprint, one square for each world and centre, as wide as the widest of them, and the margin past it.
     */
    public List<Area> of(IslandId islandId, String world, IslandBounds bounds) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(world, "world must not be null");
        Objects.requireNonNull(bounds, "bounds must not be null");
        int grown = Math.max(bounds.radius(), grownTo.apply(islandId).orElse(0));
        Map<String, IslandFootprint> squares = new LinkedHashMap<>();
        keep(squares, new IslandFootprint(world, bounds.centerX(), bounds.centerZ(), grown));
        for (IslandFootprint footprint : footprints.of(islandId)) {
            // The island's own square in another world, its Nether or its End, reaches as far as its border here,
            // since players build there inside the same border.
            boolean sameCentre = footprint.centerX() == bounds.centerX() && footprint.centerZ() == bounds.centerZ();
            keep(squares, sameCentre ? footprint.atLeast(grown) : footprint);
        }
        List<Area> areas = new ArrayList<>(squares.size());
        for (IslandFootprint square : squares.values()) {
            areas.add(new Area(
                    square.world(),
                    IslandBounds.fromCenterAndRadius(
                            square.centerX(), square.centerZ(), Math.max(1, square.radius() + margin))));
        }
        return List.copyOf(areas);
    }

    /** Forgets the island's footprints, once its squares are cleared. */
    public void cleared(IslandId islandId) {
        footprints.forget(Objects.requireNonNull(islandId, "islandId must not be null"));
    }

    private static void keep(Map<String, IslandFootprint> squares, IslandFootprint footprint) {
        String key = footprint.world() + "@" + footprint.centerX() + "," + footprint.centerZ();
        squares.merge(key, footprint, (held, fresh) -> held.atLeast(fresh.radius()));
    }
}
