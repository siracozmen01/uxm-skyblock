package com.uxplima.uxmskyblock.core.application.recycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.world.SpiralSlotPoolPort;
import com.uxplima.uxmskyblock.core.application.world.WorldGridAllocationPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;
import com.uxplima.uxmskyblock.core.domain.recycle.IslandFootprint;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import com.uxplima.uxmskyblock.core.domain.world.WorldGridAllocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * An island is cleared over everything it wrote, not only its border.
 *
 * <p>A SkyGrid island's grid reaches 48 blocks from its centre and the island a player starts with 25. Deleting it
 * cleared the 25, the grid past them stayed, and a ChunkBlock island laid in the same place found it past its own
 * border. An Upside Down in the Nether was never cleared at all.
 */
class AnIslandIsClearedOverAllItWroteTest {

    private static final IslandId ISLAND = IslandId.of(UUID.randomUUID());
    private static final IslandBounds BORDER = IslandBounds.fromCenterAndRadius(100, 200, 25);

    /** Footprints kept in memory, as the table keeps them: one per world and centre, the widest. */
    static final class Kept implements IslandFootprintPort {
        final Map<String, IslandFootprint> rows = new HashMap<>();

        @Override
        public void record(IslandId islandId, IslandFootprint footprint) {
            rows.merge(
                    footprint.world() + "@" + footprint.centerX() + "," + footprint.centerZ(),
                    footprint,
                    (held, fresh) -> held.atLeast(fresh.radius()));
        }

        @Override
        public List<IslandFootprint> of(IslandId islandId) {
            return new ArrayList<>(rows.values());
        }

        @Override
        public void forget(IslandId islandId) {
            rows.clear();
        }
    }

    private static Map<String, Integer> radii(List<IslandWipeAreas.Area> areas) {
        Map<String, Integer> radii = new HashMap<>();
        areas.forEach(area -> radii.put(area.world(), area.bounds().radius()));
        return radii;
    }

    @Test
    @DisplayName("With nothing written past it, an island is cleared over its border and the margin")
    void theBorderAndTheMargin() {
        IslandWipeAreas areas = new IslandWipeAreas(new Kept(), island -> OptionalInt.empty(), 16);

        List<IslandWipeAreas.Area> cleared = areas.of(ISLAND, "skyblock", BORDER);

        assertThat(cleared).hasSize(1);
        assertThat(cleared.get(0).bounds()).isEqualTo(IslandBounds.fromCenterAndRadius(100, 200, 41));
        assertThat(IslandWipeAreas.bordersOnly().of(ISLAND, "skyblock", BORDER))
                .containsExactly(new IslandWipeAreas.Area("skyblock", BORDER));
    }

    @Test
    @DisplayName("A grid laid past the border is cleared as far as it reaches")
    void aGridPastTheBorder() {
        Kept kept = new Kept();
        kept.record(ISLAND, new IslandFootprint("skyblock", 100, 200, 0));
        kept.record(ISLAND, new IslandFootprint("skyblock", 100, 200, 48));
        kept.record(ISLAND, new IslandFootprint("skyblock", 100, 200, 30));

        assertThat(radii(new IslandWipeAreas(kept, island -> OptionalInt.empty(), 0).of(ISLAND, "skyblock", BORDER)))
                .isEqualTo(Map.of("skyblock", 48));
    }

    @Test
    @DisplayName("The island's Nether is cleared at least as far as its border, and further where it was written")
    void itsOtherWorlds() {
        Kept kept = new Kept();
        kept.record(ISLAND, new IslandFootprint("skyblock_nether", 100, 200, 0));
        kept.record(ISLAND, new IslandFootprint("skyblock_the_end", 100, 200, 63));

        assertThat(radii(new IslandWipeAreas(kept, island -> OptionalInt.empty(), 0).of(ISLAND, "skyblock", BORDER)))
                .isEqualTo(Map.of("skyblock", 25, "skyblock_nether", 25, "skyblock_the_end", 63));
    }

    @Test
    @DisplayName("A box grown past the border is cleared as far as it has grown, in every world of the island")
    void aBoxGrownPastTheBorder() {
        Kept kept = new Kept();
        kept.record(ISLAND, new IslandFootprint("skyblock_nether", 100, 200, 0));

        assertThat(radii(new IslandWipeAreas(kept, island -> OptionalInt.of(120), 0).of(ISLAND, "skyblock", BORDER)))
                .isEqualTo(Map.of("skyblock", 120, "skyblock_nether", 120));
    }

    @Test
    @DisplayName("A square written somewhere else keeps its own reach, with the margin past it")
    void aSquareElsewhere() {
        Kept kept = new Kept();
        kept.record(ISLAND, new IslandFootprint("skyblock_nether", 900, 900, 0));

        List<IslandWipeAreas.Area> cleared =
                new IslandWipeAreas(kept, island -> OptionalInt.empty(), 4).of(ISLAND, "skyblock", BORDER);

        assertThat(cleared)
                .contains(new IslandWipeAreas.Area("skyblock_nether", IslandBounds.fromCenterAndRadius(900, 900, 4)));
    }

    @Test
    @DisplayName("A reset clears every square, forgets them once cleared, and only then hands the slot on")
    void aResetClearsEverySquare() {
        IslandStoragePort islands = mock(IslandStoragePort.class);
        WorldGridAllocationPort allocations = mock(WorldGridAllocationPort.class);
        SpiralSlotPoolPort slots = mock(SpiralSlotPoolPort.class);
        IslandVoidingPort voiding = mock(IslandVoidingPort.class);
        ProfileId owner = new ProfileId(UUID.randomUUID());
        Island island = Island.create(ISLAND, BORDER, new PlayerUuid(owner.value()), owner, Instant.now());
        when(islands.findIslandById(ISLAND)).thenReturn(Optional.of(island));
        when(islands.findLocationByIslandId(ISLAND))
                .thenReturn(Optional.of(IslandLocation.fromCenterAndRadius(ISLAND, "skyblock", 100, 200, 25)));
        when(allocations.findByIslandId(ISLAND))
                .thenReturn(Optional.of(new WorldGridAllocation(
                        7L, "skyblock", 100, 200, Optional.of(ISLAND), ServerNodeId.of("node-1"), Instant.now())));
        when(voiding.voidIslandChunks(any(), any(), any())).thenReturn(CompletableFuture.completedFuture(null));
        IslandRecycleService service = new IslandRecycleService(
                islands, allocations, slots, voiding, null, null, null, java.time.Clock.systemUTC());
        Kept kept = new Kept();
        kept.record(ISLAND, new IslandFootprint("skyblock", 100, 200, 48));
        kept.record(ISLAND, new IslandFootprint("skyblock_nether", 100, 200, 0));
        service.useWipeAreas(new IslandWipeAreas(kept, id -> OptionalInt.empty(), 0));

        assertThat(service.executeReset(owner, ISLAND, null, true).join())
                .isInstanceOf(IslandRecycleService.RecycleResult.Success.class);

        verify(voiding)
                .voidIslandChunks(eq(ISLAND), eq("skyblock"), eq(IslandBounds.fromCenterAndRadius(100, 200, 48)));
        verify(voiding).voidIslandChunks(eq(ISLAND), eq("skyblock_nether"), eq(BORDER));
        assertThat(kept.rows).describedAs("forgotten once cleared").isEmpty();
        verify(slots).releaseSlot(7L, "skyblock", 100, 200);
    }

    @Test
    @DisplayName("A clearing that fails keeps the footprints, so the next try clears them all")
    void aFailedClearingForgetsNothing() {
        IslandStoragePort islands = mock(IslandStoragePort.class);
        IslandVoidingPort voiding = mock(IslandVoidingPort.class);
        ProfileId owner = new ProfileId(UUID.randomUUID());
        when(islands.findIslandById(ISLAND))
                .thenReturn(Optional.of(
                        Island.create(ISLAND, BORDER, new PlayerUuid(owner.value()), owner, Instant.now())));
        when(islands.findLocationByIslandId(ISLAND))
                .thenReturn(Optional.of(IslandLocation.fromCenterAndRadius(ISLAND, "skyblock", 100, 200, 25)));
        when(voiding.voidIslandChunks(any(), any(), any()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("region gone")));
        IslandRecycleService service = new IslandRecycleService(
                islands,
                mock(WorldGridAllocationPort.class),
                mock(SpiralSlotPoolPort.class),
                voiding,
                null,
                null,
                null,
                java.time.Clock.systemUTC());
        Kept kept = new Kept();
        kept.record(ISLAND, new IslandFootprint("skyblock", 100, 200, 48));
        service.useWipeAreas(new IslandWipeAreas(kept, id -> OptionalInt.empty(), 0));

        assertThat(service.executeReset(owner, ISLAND, null, true).join())
                .isInstanceOf(IslandRecycleService.RecycleResult.Failure.class);
        assertThat(kept.rows).isNotEmpty();
    }
}
