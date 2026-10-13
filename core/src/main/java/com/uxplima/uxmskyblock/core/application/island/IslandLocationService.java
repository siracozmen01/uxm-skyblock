package com.uxplima.uxmskyblock.core.application.island;

import java.util.Objects;
import java.util.Optional;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;
import com.uxplima.uxmskyblock.core.domain.island.IslandPermission;

/**
 * Application service managing island spatial locations, homes, and spawn points.
 */
public final class IslandLocationService {

    private final IslandStoragePort islandStoragePort;
    private final IslandMutationLock mutationLock;

    public IslandLocationService(IslandStoragePort islandStoragePort, IslandMutationLock mutationLock) {
        this.islandStoragePort = Objects.requireNonNull(islandStoragePort, "islandStoragePort must not be null");
        this.mutationLock = Objects.requireNonNull(mutationLock, "mutationLock must not be null");
    }

    public IslandLocationService(IslandStoragePort islandStoragePort) {
        this(islandStoragePort, new IslandMutationLock());
    }

    public Optional<IslandLocation> resolveHome(ProfileId profileId) {
        Objects.requireNonNull(profileId, "profileId must not be null");
        return islandStoragePort.findIslandIdByProfileId(profileId).flatMap(islandStoragePort::findLocationByIslandId);
    }

    public Optional<IslandId> findIslandId(ProfileId profileId) {
        Objects.requireNonNull(profileId, "profileId must not be null");
        return islandStoragePort.findIslandIdByProfileId(profileId);
    }

    /** The island itself, for the callers that need its members and its flags, not just its place. */
    public Optional<Island> findIsland(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        return islandStoragePort.findIslandById(islandId);
    }

    /** Who owns the island, for the callers that hold an island and need a profile. */
    public Optional<ProfileId> findOwnerProfileId(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        return islandStoragePort.findIslandById(islandId).map(Island::ownerProfileId);
    }

    public Optional<IslandLocation> findLocation(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        return islandStoragePort.findLocationByIslandId(islandId);
    }

    /**
     * Moves the island's spawn to {@code y} in the same column, when creation found the planned height
     * was not somewhere a player can stand. Returns whether the island was there to move.
     */
    public boolean moveSpawnHeight(IslandId islandId, double y) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        return mutationLock.inside(islandId, () -> {
            Optional<Island> island = islandStoragePort.findIslandById(islandId);
            Optional<IslandLocation> location = islandStoragePort.findLocationByIslandId(islandId);
            if (island.isEmpty() || location.isEmpty()) {
                return false;
            }
            IslandLocation current = location.get();
            islandStoragePort.saveIsland(
                    island.get(),
                    new IslandLocation(
                            islandId,
                            current.worldName(),
                            current.bounds(),
                            current.spawnX(),
                            y,
                            current.spawnZ(),
                            current.spawnYaw(),
                            current.spawnPitch()));
            return true;
        });
    }

    /**
     * Moves the island's spawn to {@code x}, {@code y}, {@code z} in its own world when it stands outside {@code keep},
     * as when a Boxed island's box moved away from it. Returns whether it moved.
     */
    public boolean moveSpawnInto(IslandId islandId, IslandBounds keep, double x, double y, double z) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(keep, "keep must not be null");
        return mutationLock.inside(islandId, () -> {
            Optional<Island> island = islandStoragePort.findIslandById(islandId);
            Optional<IslandLocation> location = islandStoragePort.findLocationByIslandId(islandId);
            if (island.isEmpty() || location.isEmpty()) {
                return false;
            }
            IslandLocation current = location.get();
            if (keep.contains((int) Math.floor(current.spawnX()), (int) Math.floor(current.spawnZ()))) {
                return false;
            }
            islandStoragePort.saveIsland(
                    island.get(),
                    new IslandLocation(
                            islandId,
                            current.worldName(),
                            current.bounds(),
                            x,
                            y,
                            z,
                            current.spawnYaw(),
                            current.spawnPitch()));
            return true;
        });
    }

    /** What became of a request to move an island's spawn. */
    public enum SpawnUpdate {
        /** The spawn is where the caller stood. */
        UPDATED,
        /** The caller belongs to no island. */
        NO_ISLAND,
        /** The caller's role may not change the island's settings. */
        NOT_ALLOWED,
        /** The spot is not on the island: another world, or outside its bounds. */
        OUTSIDE_THE_ISLAND
    }

    /**
     * Moves the island's spawn to where the caller stands, if that is on the island and the caller
     * may change it.
     *
     * <p>Nothing was checked here before. A member could stand inside somebody else's locked island,
     * or in any world at all, set that as their own island's spawn, and walk in with {@code /is home}.
     * Their friends walked in with {@code /is visit}, because a visit asks whether the island it names
     * is open, not where its spawn is. The lowest role on the island could move it too.
     */
    public SpawnUpdate updateSpawn(
            ProfileId profileId, String worldName, double x, double y, double z, float yaw, float pitch) {
        Objects.requireNonNull(profileId, "profileId must not be null");
        Objects.requireNonNull(worldName, "worldName must not be null");

        Optional<IslandId> optIslandId = islandStoragePort.findIslandIdByProfileId(profileId);
        if (optIslandId.isEmpty()) {
            return SpawnUpdate.NO_ISLAND;
        }

        IslandId islandId = optIslandId.get();
        // saveIsland writes the whole aggregate, so moving the spawn point writes the members, the
        // roles and the flags back with it. Reading them outside the lock means writing back
        // whatever they were before somebody else's change.
        return mutationLock.inside(islandId, () -> {
            Optional<Island> optIsland = islandStoragePort.findIslandById(islandId);
            Optional<IslandLocation> optLoc = islandStoragePort.findLocationByIslandId(islandId);
            if (optIsland.isEmpty() || optLoc.isEmpty()) {
                return SpawnUpdate.NO_ISLAND;
            }
            Island island = optIsland.get();
            if (!island.isOwner(profileId) && !island.hasPermission(profileId, IslandPermission.SETTINGS_MODIFY)) {
                return SpawnUpdate.NOT_ALLOWED;
            }
            IslandLocation current = optLoc.get();
            if (!current.worldName().equals(worldName)
                    || !current.bounds().contains((int) Math.floor(x), (int) Math.floor(z))) {
                return SpawnUpdate.OUTSIDE_THE_ISLAND;
            }

            IslandLocation updatedLocation =
                    new IslandLocation(islandId, worldName, current.bounds(), x, y, z, yaw, pitch);

            islandStoragePort.saveIsland(island, updatedLocation);
            return SpawnUpdate.UPDATED;
        });
    }
}
