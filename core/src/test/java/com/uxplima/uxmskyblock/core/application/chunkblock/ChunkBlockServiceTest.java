package com.uxplima.uxmskyblock.core.application.chunkblock;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkPos;
import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkTerritory;
import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkUnlockRules;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The ChunkBlock service keeps memory and the table in step, and tells who must move when a chunk closes. */
class ChunkBlockServiceTest {

    private static final IslandBounds BOUNDS = IslandBounds.fromCenterAndRadius(8, 8, 40);
    private static final ChunkUnlockRules RULES = new ChunkUnlockRules(List.of(1L, 3L), 3);

    private final IslandId islandId = IslandId.of(UUID.randomUUID());
    private final Table table = new Table();
    private final ChunkBlockService service = new ChunkBlockService(table, RULES);

    @Test
    @DisplayName("An island that is no ChunkBlock island is left alone")
    void otherIslandsAreLeftAlone() {
        assertThat(service.isOpen(islandId, new ChunkPos(9, 9))).isEmpty();
        assertThat(service.unlock(islandId, new ChunkPos(1, 0), BOUNDS, 99).outcome())
                .isEqualTo(ChunkBlockService.Outcome.NOT_CHUNKBLOCK);
        assertThat(service.onLevel(islandId, 0)).isEmpty();
    }

    @Test
    @DisplayName("A started island holds the chunk its block stands in, in memory and in the table")
    void startingHoldsTheFirstChunk() {
        service.start(islandId, 8, 8);

        assertThat(service.isOpen(islandId, new ChunkPos(0, 0))).contains(true);
        assertThat(service.isOpen(islandId, new ChunkPos(1, 0))).contains(false);
        assertThat(table.find(islandId)).isPresent();
    }

    @Test
    @DisplayName("An unlock is written before memory says the chunk is open, and says what the next needs")
    void anUnlockIsWrittenFirst() {
        service.start(islandId, 8, 8);

        ChunkBlockService.UnlockResult low = service.unlock(islandId, new ChunkPos(1, 0), BOUNDS, 0);
        ChunkBlockService.UnlockResult opened = service.unlock(islandId, new ChunkPos(1, 0), BOUNDS, 1);

        assertThat(low).isEqualTo(new ChunkBlockService.UnlockResult(ChunkBlockService.Outcome.LEVEL_TOO_LOW, 1));
        assertThat(opened).isEqualTo(new ChunkBlockService.UnlockResult(ChunkBlockService.Outcome.UNLOCKED, 3));
        assertThat(table.find(islandId).orElseThrow().opened()).containsExactly(new ChunkPos(1, 0));
        assertThat(service.isOpen(islandId, new ChunkPos(1, 0))).contains(true);
    }

    @Test
    @DisplayName("When another node took the place first, the table's answer stands and memory follows it")
    void aTakenPlaceIsReadAgain() {
        service.start(islandId, 8, 8);
        table.open(islandId, new ChunkPos(0, 1), 1);

        ChunkBlockService.UnlockResult result = service.unlock(islandId, new ChunkPos(1, 0), BOUNDS, 99);

        assertThat(result.outcome()).isEqualTo(ChunkBlockService.Outcome.TAKEN);
        assertThat(result.nextRequirement()).isEqualTo(3);
        assertThat(service.isOpen(islandId, new ChunkPos(0, 1))).contains(true);
        assertThat(service.isOpen(islandId, new ChunkPos(1, 0))).contains(false);
    }

    @Test
    @DisplayName("An opened chunk is told once it is open in memory, and a refused one is not told")
    void anOpenedChunkIsTold() {
        service.start(islandId, 8, 8);
        List<ChunkPos> told = new ArrayList<>();
        AtomicBoolean openWhenTold = new AtomicBoolean();
        service.whenOpened((island, chunk) -> {
            told.add(chunk);
            openWhenTold.set(service.isOpen(island, chunk).orElse(false));
        });

        service.unlock(islandId, new ChunkPos(1, 0), BOUNDS, 0);
        service.unlock(islandId, new ChunkPos(1, 0), BOUNDS, 9);

        assertThat(told).containsExactly(new ChunkPos(1, 0));
        assertThat(openWhenTold).isTrue();
        assertThat(service.inMemory(islandId).orElseThrow().open())
                .containsExactly(new ChunkPos(0, 0), new ChunkPos(1, 0));
    }

    @Test
    @DisplayName("A fallen level closes the newest chunks in the table, then in memory, then says so")
    void aFallenLevelClosesAndTells() {
        service.start(islandId, 8, 8);
        service.unlock(islandId, new ChunkPos(1, 0), BOUNDS, 9);
        service.unlock(islandId, new ChunkPos(2, 0), BOUNDS, 9);
        List<List<ChunkPos>> told = new ArrayList<>();
        AtomicBoolean writtenFirst = new AtomicBoolean();
        service.whenClosed((island, chunks) -> {
            told.add(chunks);
            writtenFirst.set(table.find(island).orElseThrow().opened().size() == 1);
        });

        assertThat(service.onLevel(islandId, 3)).isEmpty();
        assertThat(service.onLevel(islandId, 2)).containsExactly(new ChunkPos(2, 0));

        assertThat(told).containsExactly(List.of(new ChunkPos(2, 0)));
        assertThat(writtenFirst).isTrue();
        assertThat(service.isOpen(islandId, new ChunkPos(2, 0))).contains(false);
        assertThat(service.isOpen(islandId, new ChunkPos(1, 0))).contains(true);
    }

    @Test
    @DisplayName("Priming reads every ChunkBlock island into memory")
    void primingReadsEveryIsland() {
        table.start(islandId, new ChunkPos(4, 4));

        assertThat(service.prime()).isEqualTo(1);
        assertThat(service.isOpen(islandId, new ChunkPos(4, 4))).contains(true);
    }

    @Test
    @DisplayName("An island heard back as changed is read again and stays a ChunkBlock island")
    void aChangedIslandIsReadAgain() {
        service.start(islandId, 8, 8);
        table.open(islandId, new ChunkPos(1, 0), 1);

        service.forget(islandId);

        assertThat(service.isOpen(islandId, new ChunkPos(0, 0)))
                .describedAs("a node hears its own island being created, and the island must stay closed-in")
                .contains(true);
        assertThat(service.isOpen(islandId, new ChunkPos(1, 0)))
                .describedAs("a chunk another node opened is open here once the island is read again")
                .contains(true);
        assertThat(service.isOpen(islandId, new ChunkPos(0, 1))).contains(false);
    }

    @Test
    @DisplayName("An island whose rows are gone is dropped from memory")
    void anErasedIslandIsDropped() {
        service.start(islandId, 8, 8);
        table.origins.remove(islandId);

        service.forget(islandId);

        assertThat(service.isOpen(islandId, new ChunkPos(0, 0))).isEmpty();
    }

    /** The table, in memory, with the unique order the real one has. */
    private static final class Table implements ChunkTerritoryPort {

        private final Map<IslandId, ChunkPos> origins = new HashMap<>();
        private final Map<IslandId, List<ChunkPos>> opened = new HashMap<>();

        @Override
        public Map<IslandId, ChunkTerritory> findAll() {
            Map<IslandId, ChunkTerritory> all = new HashMap<>();
            origins.keySet().forEach(id -> all.put(id, find(id).orElseThrow()));
            return all;
        }

        @Override
        public Optional<ChunkTerritory> find(IslandId islandId) {
            ChunkPos origin = origins.get(islandId);
            return origin == null
                    ? Optional.empty()
                    : Optional.of(new ChunkTerritory(origin, opened.getOrDefault(islandId, List.of())));
        }

        @Override
        public void start(IslandId islandId, ChunkPos origin) {
            origins.putIfAbsent(islandId, origin);
        }

        @Override
        public boolean open(IslandId islandId, ChunkPos chunk, int order) {
            List<ChunkPos> held = opened.computeIfAbsent(islandId, id -> new ArrayList<>());
            if (held.size() >= order || held.contains(chunk)) {
                return false;
            }
            held.add(chunk);
            return true;
        }

        @Override
        public void close(IslandId islandId, List<ChunkPos> chunks) {
            opened.getOrDefault(islandId, new ArrayList<>()).removeAll(chunks);
        }
    }
}
