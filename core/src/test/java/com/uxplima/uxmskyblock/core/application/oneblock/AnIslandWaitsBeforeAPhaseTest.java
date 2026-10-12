package com.uxplima.uxmskyblock.core.application.oneblock;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
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

/** A OneBlock island waits before a phase, for a time or for its requirements, and keeps waiting across a restart. */
class AnIslandWaitsBeforeAPhaseTest {

    private static final IslandId ISLAND = IslandId.of(UUID.randomUUID());
    private static final Instant LATER = Instant.parse("2026-10-13T12:00:00Z");

    private final Map<IslandId, Long> stored = new HashMap<>();
    private final Map<IslandId, OneBlockWaitPort.Waiting> kept = new HashMap<>();
    private final List<String> asked = new ArrayList<>();
    private boolean failing;

    private final OneBlockProgressPort progress = new OneBlockProgressPort() {
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
            stored.merge(islandId, breaks, Long::sum);
        }

        @Override
        public void setBreaks(IslandId islandId, long count) {
            stored.put(islandId, count);
        }
    };

    private final OneBlockWaitPort waits = new OneBlockWaitPort() {
        @Override
        public List<Waiting> findAll() {
            asked.add("all");
            return List.copyOf(kept.values());
        }

        @Override
        public Optional<Waiting> find(IslandId islandId) {
            asked.add("one");
            return Optional.ofNullable(kept.get(islandId));
        }

        @Override
        public void set(Waiting waiting) {
            if (failing) {
                throw new IllegalStateException("the database is away");
            }
            kept.put(waiting.islandId(), waiting);
        }

        @Override
        public void clear(IslandId islandId) {
            if (failing) {
                throw new IllegalStateException("the database is away");
            }
            kept.remove(islandId);
        }
    };

    private static OneBlockPhase phase(String key, long blocks) {
        return new OneBlockPhase(key, blocks, new WeightedPool(Map.of("DIRT", 1.0)), WeightedPool.empty(), 0);
    }

    private final OneBlockPhases phases =
            new OneBlockPhases(List.of(phase("plains", 3), phase("cave", 2)), OneBlockPhases.AfterTheLast.REPEAT);

    private OneBlockService service() {
        stored.putIfAbsent(ISLAND, 0L);
        return new OneBlockService(progress, OneBlockRewardPort.NONE, waits, phases, new SplittableRandom(1));
    }

    @Test
    @DisplayName("The phase ahead is the one the next break begins, and only then")
    void ahead() {
        OneBlockService service = service();
        assertThat(service.phaseAhead(ISLAND)).isEmpty();
        service.onBreak(ISLAND);
        assertThat(service.phaseAhead(ISLAND)).isEmpty();
        service.onBreak(ISLAND);
        assertThat(service.phaseAhead(ISLAND)).map(OneBlockPhase::key).contains("cave");
        service.onBreak(ISLAND);
        service.onBreak(ISLAND);
        assertThat(service.phaseAhead(ISLAND))
                .describedAs("round to the first phase again")
                .map(OneBlockPhase::key)
                .contains("plains");
        assertThat(service.phaseAhead(IslandId.of(UUID.randomUUID()))).isEmpty();
    }

    @Test
    @DisplayName("A wait is held in memory, written when asked, and ended once, by whoever ends the one it is")
    void heldAndEnded() {
        OneBlockService service = service();
        assertThat(service.waitBefore(ISLAND, "cave", LATER)).isTrue();
        OneBlockWaitPort.Waiting waiting = service.waiting(ISLAND).orElseThrow();
        assertThat(waiting.timed()).isTrue();
        assertThat(kept).isEmpty();

        assertThat(service.storeWait(ISLAND)).isTrue();
        assertThat(kept).containsValue(new OneBlockWaitPort.Waiting(ISLAND, "cave", LATER));

        assertThat(service.endWait(ISLAND, new OneBlockWaitPort.Waiting(ISLAND, "cave", null)))
                .describedAs("another wait")
                .isFalse();
        assertThat(service.endWait(ISLAND, waiting)).isTrue();
        assertThat(service.endWait(ISLAND, waiting)).isFalse();
        assertThat(service.waiting(ISLAND)).isEmpty();
        assertThat(service.storeWait(ISLAND)).isTrue();
        assertThat(kept).isEmpty();

        assertThat(service.waitBefore(IslandId.of(UUID.randomUUID()), "cave", null))
                .isFalse();
    }

    @Test
    @DisplayName("A write that fails keeps the wait in memory and says so")
    void failingWrite() {
        OneBlockService service = service();
        service.waitBefore(ISLAND, "cave", null);
        failing = true;

        assertThat(service.storeWait(ISLAND)).isFalse();
        assertThat(service.waiting(ISLAND)).isPresent();
    }

    @Test
    @DisplayName("A restart finds every island still waiting, and the timed ones go on")
    void afterARestart() {
        IslandId other = IslandId.of(UUID.randomUUID());
        stored.put(other, 0L);
        kept.put(ISLAND, new OneBlockWaitPort.Waiting(ISLAND, "cave", LATER));
        kept.put(other, new OneBlockWaitPort.Waiting(other, "cave", null));

        OneBlockService primed = service();
        primed.prime();
        assertThat(primed.waiting(other)).map(OneBlockWaitPort.Waiting::timed).contains(false);
        assertThat(primed.timedWaits()).containsExactly(new OneBlockWaitPort.Waiting(ISLAND, "cave", LATER));

        asked.clear();
        OneBlockService read = service();
        assertThat(read.waiting(ISLAND)).isPresent();
        assertThat(asked).containsExactly("one");
    }

    @Test
    @DisplayName("An island that starts over waits for nothing")
    void startOver() {
        kept.put(ISLAND, new OneBlockWaitPort.Waiting(ISLAND, "cave", LATER));
        OneBlockService service = service();

        service.start(ISLAND, 0, 100, 0);

        assertThat(kept).isEmpty();
        assertThat(service.waiting(ISLAND)).isEmpty();
    }
}
