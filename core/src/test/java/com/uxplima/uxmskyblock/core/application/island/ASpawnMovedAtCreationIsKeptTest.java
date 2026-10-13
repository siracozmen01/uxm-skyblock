package com.uxplima.uxmskyblock.core.application.island;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** A spawn creation had to move up or down is written back, so a home is where the player first stood. */
class ASpawnMovedAtCreationIsKeptTest {

    @Test
    @DisplayName("The spawn's height moves and nothing else about the island does")
    void onlyTheHeightMoves() {
        IslandId islandId = IslandId.of(UUID.randomUUID());
        IslandBounds bounds = IslandBounds.fromCenterAndRadius(0, 0, 50);
        Island island = Island.create(
                islandId, bounds, PlayerUuid.of(UUID.randomUUID()), ProfileId.of(UUID.randomUUID()), Instant.now());
        IslandStoragePort storage = mock(IslandStoragePort.class);
        when(storage.findIslandById(islandId)).thenReturn(Optional.of(island));
        when(storage.findLocationByIslandId(islandId))
                .thenReturn(Optional.of(new IslandLocation(islandId, "boxed", bounds, 0.5, 101.0, 0.5, 90f, 10f)));

        assertThat(new IslandLocationService(storage).moveSpawnHeight(islandId, 73.0))
                .isTrue();

        ArgumentCaptor<IslandLocation> written = ArgumentCaptor.forClass(IslandLocation.class);
        verify(storage).saveIsland(any(Island.class), written.capture());
        assertThat(written.getValue())
                .isEqualTo(new IslandLocation(islandId, "boxed", bounds, 0.5, 73.0, 0.5, 90f, 10f));
    }

    @Test
    @DisplayName("An island that is gone is not written back")
    void aGoneIslandIsNotWritten() {
        IslandStoragePort storage = mock(IslandStoragePort.class);
        IslandId islandId = IslandId.of(UUID.randomUUID());
        when(storage.findIslandById(islandId)).thenReturn(Optional.empty());
        when(storage.findLocationByIslandId(islandId)).thenReturn(Optional.empty());

        assertThat(new IslandLocationService(storage).moveSpawnHeight(islandId, 73.0))
                .isFalse();
        verify(storage, never()).saveIsland(any(Island.class), any(IslandLocation.class));
    }

    @Test
    @DisplayName("A spawn a moved box left behind follows it, and one still inside the box stays")
    void aSpawnFollowsTheBox() {
        IslandId islandId = IslandId.of(UUID.randomUUID());
        IslandBounds bounds = IslandBounds.fromCenterAndRadius(0, 0, 50);
        Island island = Island.create(
                islandId, bounds, PlayerUuid.of(UUID.randomUUID()), ProfileId.of(UUID.randomUUID()), Instant.now());
        IslandStoragePort storage = mock(IslandStoragePort.class);
        when(storage.findIslandById(islandId)).thenReturn(Optional.of(island));
        when(storage.findLocationByIslandId(islandId))
                .thenReturn(Optional.of(new IslandLocation(islandId, "boxed", bounds, 0.5, 101.0, 0.5, 90f, 10f)));
        IslandLocationService locations = new IslandLocationService(storage);

        assertThat(locations.moveSpawnInto(islandId, IslandBounds.fromCenterAndRadius(2, -3, 5), 9, 9, 9))
                .describedAs("the spawn is still in the box")
                .isFalse();
        verify(storage, never()).saveIsland(any(Island.class), any(IslandLocation.class));
        assertThat(locations.moveSpawnInto(islandId, IslandBounds.fromCenterAndRadius(20, 1, 5), 20.5, 64.0, 1.5))
                .isTrue();
        ArgumentCaptor<IslandLocation> written = ArgumentCaptor.forClass(IslandLocation.class);
        verify(storage).saveIsland(any(Island.class), written.capture());
        assertThat(written.getValue())
                .isEqualTo(new IslandLocation(islandId, "boxed", bounds, 20.5, 64.0, 1.5, 90f, 10f));
        assertThat(locations.moveSpawnInto(IslandId.of(UUID.randomUUID()), bounds, 0, 0, 0))
                .isFalse();
    }
}
