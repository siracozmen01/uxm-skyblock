package com.uxplima.uxmskyblock.core.application.oneblock;

import static org.assertj.core.api.Assertions.assertThat;

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

/** A OneBlock island picks the phase that follows one that branches, goes that way, and keeps the pick. */
class AnIslandPicksItsWayTest {

    private static final IslandId ISLAND = IslandId.of(UUID.randomUUID());

    private final Map<IslandId, Long> stored = new HashMap<>();
    private final List<OneBlockBranchPort.Pick> kept = new ArrayList<>();
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
        public void addBreaks(IslandId islandId, long breaks) {}

        @Override
        public void setBreaks(IslandId islandId, long count) {}
    };

    private final OneBlockBranchPort branches = new OneBlockBranchPort() {
        @Override
        public List<Pick> findAll() {
            asked.add("all");
            return List.copyOf(kept);
        }

        @Override
        public List<Pick> find(IslandId islandId) {
            asked.add("one");
            return kept.stream().filter(one -> one.islandId().equals(islandId)).toList();
        }

        @Override
        public void pick(Pick pick) {
            if (failing) {
                throw new IllegalStateException("the database is away");
            }
            kept.removeIf(
                    one -> one.islandId().equals(pick.islandId()) && one.phase().equals(pick.phase()));
            kept.add(pick);
        }

        @Override
        public void clear(IslandId islandId) {
            kept.removeIf(one -> one.islandId().equals(islandId));
        }
    };

    private static OneBlockPhase phase(String key, long blocks, String block) {
        return new OneBlockPhase(key, blocks, new WeightedPool(Map.of(block, 1.0)), WeightedPool.empty(), 0);
    }

    private final OneBlockPhases phases = new OneBlockPhases(
            List.of(phase("plains", 2, "GRASS_BLOCK"), phase("cave", 2, "STONE"), phase("forest", 3, "OAK_LOG")),
            OneBlockPhases.AfterTheLast.STAY,
            Map.of("plains", List.of("cave", "forest"), "cave", List.of()));

    private OneBlockService service() {
        stored.putIfAbsent(ISLAND, 0L);
        return new OneBlockService(
                progress, OneBlockRewardPort.NONE, OneBlockWaitPort.NONE, branches, phases, new SplittableRandom(1));
    }

    @Test
    @DisplayName("At the end of a phase that branches the island is asked to pick, and once it picks, goes that way")
    void picks() {
        OneBlockService service = service();
        assertThat(service.branchAhead(ISLAND)).isEmpty();
        service.onBreak(ISLAND);
        assertThat(service.branchAhead(ISLAND)).containsExactly("cave", "forest");

        assertThat(service.pick(ISLAND, "plains", "nether")).isFalse();
        assertThat(service.pick(ISLAND, "cave", "forest"))
                .describedAs("cave does not branch")
                .isFalse();
        assertThat(service.pick(ISLAND, "plains", "forest")).isTrue();
        assertThat(service.branchAhead(ISLAND)).isEmpty();

        OneBlockService.Broken next = service.onBreak(ISLAND).orElseThrow();
        assertThat(next.position().phase().key()).isEqualTo("forest");
        assertThat(next.nextBlock()).isEqualTo("OAK_LOG");
        assertThat(service.phasesOf(ISLAND).orElseThrow().phases())
                .extracting(OneBlockPhase::key)
                .containsExactly("plains", "forest");
        assertThat(service.blockNow(ISLAND)).contains("OAK_LOG");
        assertThat(service.position(ISLAND).orElseThrow().phase().key()).isEqualTo("forest");
    }

    @Test
    @DisplayName("Without a pick the island would go the first way")
    void theFirstWay() {
        OneBlockService service = service();
        service.onBreak(ISLAND);
        assertThat(service.phaseAhead(ISLAND)).map(OneBlockPhase::key).contains("cave");
    }

    @Test
    @DisplayName("A pick is written when asked, read back after a restart, and forgotten when the island starts over")
    void kept() {
        OneBlockService service = service();
        service.pick(ISLAND, "plains", "forest");
        assertThat(kept).isEmpty();
        assertThat(service.storePick(ISLAND, "plains")).isTrue();
        assertThat(kept).containsExactly(new OneBlockBranchPort.Pick(ISLAND, "plains", "forest"));
        assertThat(service.storePick(ISLAND, "cave"))
                .describedAs("nothing picked there")
                .isFalse();

        OneBlockService primed = service();
        primed.prime();
        primed.onBreak(ISLAND);
        assertThat(primed.branchAhead(ISLAND)).isEmpty();
        assertThat(primed.phaseAhead(ISLAND)).map(OneBlockPhase::key).contains("forest");

        asked.clear();
        OneBlockService read = service();
        assertThat(read.phasesOf(ISLAND).orElseThrow().phases())
                .hasSize(2)
                .last()
                .extracting(OneBlockPhase::key)
                .isEqualTo("forest");
        assertThat(asked).containsExactly("one");

        read.start(ISLAND, 0, 100, 0);
        assertThat(kept).isEmpty();
        read.onBreak(ISLAND);
        assertThat(read.branchAhead(ISLAND)).containsExactly("cave", "forest");
    }

    @Test
    @DisplayName("A write that fails keeps the pick in memory and says so")
    void failingWrite() {
        OneBlockService service = service();
        service.pick(ISLAND, "plains", "forest");
        failing = true;

        assertThat(service.storePick(ISLAND, "plains")).isFalse();
        assertThat(service.phasesOf(ISLAND).orElseThrow().phases())
                .last()
                .extracting(OneBlockPhase::key)
                .isEqualTo("forest");
    }
}
