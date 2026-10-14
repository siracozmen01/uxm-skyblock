package com.uxplima.uxmskyblock.bukkit.spatial;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SpatialIslandIndexTest {

    private IslandStoragePort storagePort;
    private SchedulerPort schedulerPort;
    private SpatialIslandIndex index;

    @BeforeEach
    void setUp() {
        storagePort = mock(IslandStoragePort.class);
        schedulerPort = mock(SchedulerPort.class);
        index = new SpatialIslandIndex(storagePort, schedulerPort);
    }

    @Test
    @DisplayName("indexIsland maps all overlapping chunks to the island")
    void indexIslandMapsChunks() {
        IslandId islandId = IslandId.of(UUID.randomUUID());
        IslandBounds bounds = IslandBounds.fromCenterAndRadius(0, 0, 32);
        Island island = Island.create(
                islandId, bounds, new PlayerUuid(UUID.randomUUID()), new ProfileId(UUID.randomUUID()), Instant.now());

        index.indexIsland(island, "skyblock_world");

        assertThat(index.totalIndexedIslands()).isEqualTo(1);
        assertThat(index.totalIndexedChunks()).isGreaterThan(1);

        Optional<Island> found = index.findIslandAt("skyblock_world", 10, 10);
        assertThat(found).isPresent();
        assertThat(found.get().id()).isEqualTo(islandId);

        // Verification: storagePort was NOT called during cache hit
        verify(storagePort, never()).findIslandByLocation(anyString(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("findIslandAt returns empty for coordinates outside island bounds")
    void findIslandAtReturnsEmptyOutsideBounds() {
        IslandId islandId = IslandId.of(UUID.randomUUID());
        IslandBounds bounds = IslandBounds.fromCenterAndRadius(0, 0, 16);
        Island island = Island.create(
                islandId, bounds, new PlayerUuid(UUID.randomUUID()), new ProfileId(UUID.randomUUID()), Instant.now());

        index.indexIsland(island, "skyblock_world");

        Optional<Island> found = index.findIslandAt("skyblock_world", 1000, 1000);
        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("removeIsland purges all chunk mappings and island entry")
    void removeIslandPurgesChunks() {
        IslandId islandId = IslandId.of(UUID.randomUUID());
        IslandBounds bounds = IslandBounds.fromCenterAndRadius(0, 0, 32);
        Island island = Island.create(
                islandId, bounds, new PlayerUuid(UUID.randomUUID()), new ProfileId(UUID.randomUUID()), Instant.now());

        index.indexIsland(island, "skyblock_world");
        index.removeIsland(islandId);

        assertThat(index.totalIndexedIslands()).isZero();
        assertThat(index.totalIndexedChunks()).isZero();
        assertThat(index.findIslandAt("skyblock_world", 0, 0)).isEmpty();
    }

    @Test
    @DisplayName("An island indexed in its overworld and its Nether is found in both, each world kept apart")
    void anIslandStandsInEveryWorldItIsIndexedIn() {
        IslandId islandId = IslandId.of(UUID.randomUUID());
        Island wide = Island.create(
                islandId,
                IslandBounds.fromCenterAndRadius(0, 0, 64),
                new PlayerUuid(UUID.randomUUID()),
                new ProfileId(UUID.randomUUID()),
                Instant.now());
        Island narrow = new Island(
                islandId,
                IslandBounds.fromCenterAndRadius(0, 0, 16),
                wide.ownerPlayerUuid(),
                wide.ownerProfileId(),
                wide.members(),
                wide.roles(),
                wide.flags(),
                wide.createdAt(),
                wide.lifecycle(),
                wide.residencyState(),
                wide.economicState(),
                wide.administrativeState(),
                wide.freezeReason());

        index.indexIsland(wide, "skyblock_world");
        index.indexIsland(wide, "skyblock_nether");

        assertThat(index.findIslandAt("skyblock_world", 10, 10)).isPresent();
        assertThat(index.findIslandAt("skyblock_nether", 10, 10))
                .describedAs("indexing the Nether keeps the overworld")
                .isPresent();
        assertThat(index.findIslandAt("skyblock_world", 60, 60)).isPresent();

        // Indexed again in the overworld, narrower: only the overworld's far chunks go.
        index.indexIsland(narrow, "skyblock_world");
        assertThat(index.findIslandAt("skyblock_world", 10, 10)).isPresent();
        assertThat(index.findIslandAt("skyblock_world", 60, 60)).isEmpty();
        index.indexIsland(wide, "skyblock_nether");
        assertThat(index.findIslandAt("skyblock_nether", 60, 60))
                .describedAs("the Nether's stay")
                .isPresent();
        verify(storagePort, never()).findIslandByLocation(eq("skyblock_world"), eq(10), eq(10));

        index.removeIsland(islandId);
        assertThat(index.totalIndexedChunks()).isZero();
        assertThat(index.findIslandAt("skyblock_nether", 10, 10)).isEmpty();
    }

    @Test
    @DisplayName(
            "A Nether world mirrors the island world: warmed with it, and a miss there is read by the island world")
    void aNetherMirrorsTheIslandWorld() {
        Island island = Island.create(
                IslandId.of(UUID.randomUUID()),
                IslandBounds.fromCenterAndRadius(0, 0, 32),
                new PlayerUuid(UUID.randomUUID()),
                new ProfileId(UUID.randomUUID()),
                Instant.now());
        Island other = Island.create(
                IslandId.of(UUID.randomUUID()),
                IslandBounds.fromCenterAndRadius(1000, 0, 32),
                new PlayerUuid(UUID.randomUUID()),
                new ProfileId(UUID.randomUUID()),
                Instant.now());
        when(storagePort.findAllByWorld("skyblock_world")).thenReturn(java.util.List.of(island));
        when(storagePort.findIslandByLocation("skyblock_world", 1000, 0)).thenReturn(Optional.of(other));
        SpatialIslandIndex offline = new SpatialIslandIndex(storagePort, null);
        offline.mirror(java.util.List.of("skyblock_world"), java.util.List.of("skyblock_nether", "skyblock_world"));

        offline.warmFromStorage("skyblock_world");
        assertThat(offline.findIslandAt("skyblock_nether", 10, 10))
                .describedAs("warmed into the mirror with its island world")
                .map(Island::id)
                .contains(island.id());
        assertThat(offline.findIslandAt("skyblock_world", 10, 10)).isPresent();

        assertThat(offline.findIslandAt("skyblock_nether", 1000, 0))
                .describedAs("a miss in the mirror is read by the island world")
                .map(Island::id)
                .contains(other.id());
        verify(storagePort, never()).findIslandByLocation(eq("skyblock_nether"), anyInt(), anyInt());
        assertThat(offline.findIslandAt("skyblock_the_end", 1000, 0))
                .describedAs("a world that mirrors nothing is read by its own name")
                .isEmpty();
        verify(storagePort).findIslandByLocation("skyblock_the_end", 1000, 0);
    }
}
