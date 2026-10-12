package com.uxplima.uxmskyblock.core.application.oneblock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.oneblock.OneBlockPhase;
import com.uxplima.uxmskyblock.core.domain.oneblock.OneBlockPhases;
import com.uxplima.uxmskyblock.core.domain.oneblock.WeightedPool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** An operator sets, adds to and takes from a OneBlock island's count, and the change is written at once. */
class AnOperatorSetsAOneBlockCountTest {

    private static final IslandId ISLAND = IslandId.of(UUID.randomUUID());
    private static final IslandId ORDINARY = IslandId.of(UUID.randomUUID());

    private final Map<IslandId, Long> stored = new HashMap<>();
    private final List<String> writes = new ArrayList<>();
    private boolean failing;

    private final OneBlockProgressPort port = new OneBlockProgressPort() {
        @Override
        public void start(IslandId islandId, int x, int y, int z) {
            stored.put(islandId, 0L);
        }

        @Override
        public List<OneBlockIsland> findAll() {
            return stored.entrySet().stream()
                    .map(entry -> new OneBlockIsland(entry.getKey(), 0, 100, 0, entry.getValue()))
                    .toList();
        }

        @Override
        public Optional<OneBlockIsland> find(IslandId islandId) {
            return Optional.ofNullable(stored.get(islandId))
                    .map(count -> new OneBlockIsland(islandId, 0, 100, 0, count));
        }

        @Override
        public void addBreaks(IslandId islandId, long breaks) {
            if (breaks < 0) {
                throw new IllegalArgumentException("storage takes no breaks back");
            }
            if (failing) {
                throw new IllegalStateException("the database is away");
            }
            writes.add("add " + breaks);
            stored.merge(islandId, breaks, Long::sum);
        }

        @Override
        public void setBreaks(IslandId islandId, long count) {
            if (failing) {
                throw new IllegalStateException("the database is away");
            }
            writes.add("set " + count);
            stored.put(islandId, count);
        }
    };

    private static OneBlockPhase phase(String key, long blocks) {
        return new OneBlockPhase(key, blocks, new WeightedPool(Map.of("DIRT", 1.0)), WeightedPool.empty(), 0);
    }

    private final OneBlockPhases phases = new OneBlockPhases(
            List.of(phase("plains", 10), phase("cave", 20), phase("end", 5)), OneBlockPhases.AfterTheLast.STAY);

    private OneBlockService service() {
        stored.put(ISLAND, 12L);
        OneBlockService service = new OneBlockService(port, phases, new SplittableRandom(1));
        service.prime();
        return service;
    }

    @Test
    @DisplayName("A count set is the island's count at once, and in storage at once")
    void set() {
        OneBlockService service = service();
        service.onBreak(ISLAND);

        assertThat(service.setBroken(ISLAND, 40)).contains(new OneBlockService.Changed(13, 40));

        assertThat(service.island(ISLAND).orElseThrow().blocksBroken()).isEqualTo(40);
        assertThat(stored).containsEntry(ISLAND, 40L);
        assertThat(service.position(ISLAND).orElseThrow().phase().key()).isEqualTo("end");
        assertThat(service.flush()).describedAs("nothing left to write").isZero();
        service.onBreak(ISLAND);
        service.flush();
        assertThat(stored).describedAs("a break after the set is added to it").containsEntry(ISLAND, 41L);
        assertThat(writes).containsExactly("set 40", "add 1");
        assertThat(service.setBroken(ORDINARY, 5)).isEmpty();
        assertThatThrownBy(() -> service.setBroken(ISLAND, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Breaks added or taken away move the count, never below nothing")
    void add() {
        OneBlockService service = service();

        assertThat(service.addBroken(ISLAND, 5)).contains(new OneBlockService.Changed(12, 17));
        assertThat(service.addBroken(ISLAND, -100)).contains(new OneBlockService.Changed(17, 0));
        assertThat(stored).containsEntry(ISLAND, 0L);
        assertThat(writes)
                .describedAs("a count taken from is set whole, as storage adds nothing below nothing")
                .containsExactly("set 17", "set 0");
        assertThat(service.addBroken(ORDINARY, 1)).isEmpty();
    }

    @Test
    @DisplayName("A change that cannot be written now is kept and written with the next")
    void keptWhenTheWriteFails() {
        OneBlockService service = service();
        failing = true;

        assertThat(service.setBroken(ISLAND, 30)).contains(new OneBlockService.Changed(12, 30));
        assertThat(stored).containsEntry(ISLAND, 12L);

        failing = false;
        assertThat(service.flush()).isEqualTo(1);
        assertThat(stored).containsEntry(ISLAND, 30L);
    }

    @Test
    @DisplayName("A phase starts after the breaks of the phases before it, on the first pass")
    void whereAPhaseStarts() {
        assertThat(phases.startOf("plains")).hasValue(0);
        assertThat(phases.startOf("cave")).hasValue(10);
        assertThat(phases.startOf("end")).hasValue(30);
        assertThat(phases.startOf("nether")).isEmpty();
    }
}
