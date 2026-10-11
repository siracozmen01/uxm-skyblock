package com.uxplima.uxmskyblock.core.application.boundary;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import org.jspecify.annotations.Nullable;

/**
 * Core application service managing island boundary math, spillover physics checks,
 * virtual WorldBorder synchronization, and particle perimeter projections.
 */
public final class IslandBoundaryService {

    private final @Nullable WorldBorderPacketPort worldBorderPort;
    private final Set<PlayerUuid> activePerimeterViewers = ConcurrentHashMap.newKeySet();

    /** The players who hid the border, so no island draws it for them. */
    private final Set<PlayerUuid> hiddenBorders = ConcurrentHashMap.newKeySet();

    /** The island each player stands in, so a border shown again is drawn where they are. */
    private final java.util.Map<PlayerUuid, IslandBounds> standingIn = new ConcurrentHashMap<>();

    public IslandBoundaryService(@Nullable WorldBorderPacketPort worldBorderPort) {
        this.worldBorderPort = worldBorderPort;
    }

    public IslandBoundaryService() {
        this(null);
    }

    public boolean togglePerimeter(PlayerUuid playerUuid) {
        Objects.requireNonNull(playerUuid, "playerUuid must not be null");
        if (activePerimeterViewers.contains(playerUuid)) {
            activePerimeterViewers.remove(playerUuid);
            return false;
        } else {
            activePerimeterViewers.add(playerUuid);
            return true;
        }
    }

    public void enablePerimeter(PlayerUuid playerUuid) {
        Objects.requireNonNull(playerUuid, "playerUuid must not be null");
        activePerimeterViewers.add(playerUuid);
    }

    public void disablePerimeter(PlayerUuid playerUuid) {
        Objects.requireNonNull(playerUuid, "playerUuid must not be null");
        activePerimeterViewers.remove(playerUuid);
    }

    public boolean isPerimeterActive(PlayerUuid playerUuid) {
        Objects.requireNonNull(playerUuid, "playerUuid must not be null");
        return activePerimeterViewers.contains(playerUuid);
    }

    public Set<PlayerUuid> activePerimeterViewers() {
        return Set.copyOf(activePerimeterViewers);
    }

    /**
     * Evaluates whether an interaction, fluid flow, or motion originating from (fromX, fromZ)
     * spills over the island boundary into external void or territory at (toX, toZ).
     */
    public boolean isSpillover(IslandBounds bounds, int fromX, int fromZ, int toX, int toZ) {
        Objects.requireNonNull(bounds, "bounds must not be null");
        return bounds.contains(fromX, fromZ) && !bounds.contains(toX, toZ);
    }

    /**
     * Checks if coordinates are within the given island bounds.
     */
    public boolean isWithinBounds(IslandBounds bounds, int x, int z) {
        Objects.requireNonNull(bounds, "bounds must not be null");
        return bounds.contains(x, z);
    }

    /**
     * Calculates spatial boundary perimeter points along the 4 edges of the island bounding box.
     *
     * @param bounds island bounds
     * @param y elevation level
     * @param stepSize distance in blocks between consecutive particle nodes (minimum 1)
     * @return ordered list of perimeter coordinates
     */
    public List<IslandBoundaryPoint> calculatePerimeterPoints(IslandBounds bounds, double y, int stepSize) {
        Objects.requireNonNull(bounds, "bounds must not be null");
        int step = Math.max(1, stepSize);
        List<IslandBoundaryPoint> points = new ArrayList<>();

        double minX = bounds.minX();
        double maxX = bounds.maxX() + 1.0;
        double minZ = bounds.minZ();
        double maxZ = bounds.maxZ() + 1.0;

        // North edge: (minX -> maxX, minZ)
        for (double x = minX; x <= maxX; x += step) {
            points.add(new IslandBoundaryPoint(x, y, minZ));
        }
        // East edge: (maxX, minZ -> maxZ)
        for (double z = minZ; z <= maxZ; z += step) {
            points.add(new IslandBoundaryPoint(maxX, y, z));
        }
        // South edge: (maxX -> minX, maxZ)
        for (double x = maxX; x >= minX; x -= step) {
            points.add(new IslandBoundaryPoint(x, y, maxZ));
        }
        // West edge: (minX, maxZ -> minZ)
        for (double z = maxZ; z >= minZ; z -= step) {
            points.add(new IslandBoundaryPoint(minX, y, z));
        }

        return points;
    }

    /**
     * Hides the border for a player who shows it, and shows it for one who hid it, and answers whether it is shown
     * now. {@code /is border} said the border was shown and then hidden while the wall a player saw never moved: it
     * turned a line of particles on and off, and the wall is what it hides now.
     */
    public boolean toggleBorder(PlayerUuid playerUuid) {
        Objects.requireNonNull(playerUuid, "playerUuid must not be null");
        if (hiddenBorders.remove(playerUuid)) {
            IslandBounds bounds = standingIn.get(playerUuid);
            if (bounds != null) {
                send(playerUuid, bounds);
            }
            return true;
        }
        hiddenBorders.add(playerUuid);
        if (worldBorderPort != null && standingIn.containsKey(playerUuid)) {
            worldBorderPort.resetWorldBorder(playerUuid);
        }
        return false;
    }

    /** Marks the border hidden for a player who hid it before, as they join, and draws nothing. */
    public void hideBorder(PlayerUuid playerUuid) {
        hiddenBorders.add(Objects.requireNonNull(playerUuid, "playerUuid must not be null"));
    }

    /** Whether the player hid the border. */
    public boolean isBorderHidden(PlayerUuid playerUuid) {
        return hiddenBorders.contains(Objects.requireNonNull(playerUuid, "playerUuid must not be null"));
    }

    /** Forgets a player who left: their choice is kept where the server keeps the player. */
    public void forget(PlayerUuid playerUuid) {
        Objects.requireNonNull(playerUuid, "playerUuid must not be null");
        hiddenBorders.remove(playerUuid);
        standingIn.remove(playerUuid);
        activePerimeterViewers.remove(playerUuid);
    }

    public void handlePlayerEnterIsland(PlayerUuid playerUuid, IslandBounds bounds) {
        Objects.requireNonNull(playerUuid, "playerUuid must not be null");
        Objects.requireNonNull(bounds, "bounds must not be null");
        standingIn.put(playerUuid, bounds);
        if (!hiddenBorders.contains(playerUuid)) {
            send(playerUuid, bounds);
        }
    }

    public void handlePlayerExitIsland(PlayerUuid playerUuid) {
        Objects.requireNonNull(playerUuid, "playerUuid must not be null");
        standingIn.remove(playerUuid);
        if (worldBorderPort != null && !hiddenBorders.contains(playerUuid)) {
            worldBorderPort.resetWorldBorder(playerUuid);
        }
    }

    private void send(PlayerUuid playerUuid, IslandBounds bounds) {
        if (worldBorderPort != null) {
            worldBorderPort.sendWorldBorder(playerUuid, bounds.centerX(), bounds.centerZ(), bounds.radius(), 0.0, 0L);
        }
    }

    public void handleIslandExpand(
            PlayerUuid playerUuid, IslandBounds oldBounds, IslandBounds newBounds, long transitionDurationMs) {
        Objects.requireNonNull(playerUuid, "playerUuid must not be null");
        Objects.requireNonNull(oldBounds, "oldBounds must not be null");
        Objects.requireNonNull(newBounds, "newBounds must not be null");
        standingIn.replace(playerUuid, oldBounds, newBounds);
        if (worldBorderPort != null && !hiddenBorders.contains(playerUuid)) {
            worldBorderPort.sendWorldBorder(
                    playerUuid,
                    newBounds.centerX(),
                    newBounds.centerZ(),
                    newBounds.radius(),
                    oldBounds.radius(),
                    transitionDurationMs);
        }
    }
}
