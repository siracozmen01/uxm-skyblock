package com.uxplima.uxmskyblock.core.application.oneblock;

import static org.assertj.core.api.Assertions.assertThat;

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

/**
 * An operator's reload hands a OneBlock island new phases: it keeps its count and its picks, and its next break
 * already draws from the new blocks, with nothing drawn ahead from the old ones.
 */
class AnIslandGoesOnWithReloadedPhasesTest {

    private static final IslandId ISLAND = IslandId.of(UUID.randomUUID());

    private final Map<IslandId, Long> stored = new HashMap<>();

    private final OneBlockProgressPort progress = new OneBlockProgressPort() {
        @Override
        public void start(IslandId islandId, int x, int y, int z) {
            stored.put(islandId, 0L);
        }

        @Override
        public List<OneBlockIsland> findAll() {
            return List.of();
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

    private static OneBlockPhase phase(String key, long blocks, String block) {
        return new OneBlockPhase(key, blocks, new WeightedPool(Map.of(block, 1.0)), WeightedPool.empty(), 0);
    }

    @Test
    @DisplayName("The count stays, the way follows the new phases, and the next break draws from them")
    void reloaded() {
        stored.put(ISLAND, 0L);
        OneBlockService service = new OneBlockService(
                progress,
                OneBlockRewardPort.NONE,
                OneBlockWaitPort.NONE,
                OneBlockBranchPort.NONE,
                new OneBlockPhases(
                        List.of(phase("plains", 10, "DIRT"), phase("cave", 10, "STONE"), phase("forest", 10, "MOSS")),
                        OneBlockPhases.AfterTheLast.STAY,
                        Map.of("plains", List.of("cave", "forest"))),
                new SplittableRandom(1));
        service.lookAhead(5);
        service.onBreak(ISLAND);
        assertThat(service.pick(ISLAND, "plains", "cave")).isTrue();
        service.onBreak(ISLAND);
        assertThat(service.blockNow(ISLAND)).contains("DIRT");

        service.usePhases(new OneBlockPhases(
                List.of(phase("plains", 3, "SAND"), phase("cave", 10, "CLAY"), phase("forest", 4, "OAK_LOG")),
                OneBlockPhases.AfterTheLast.STAY,
                Map.of("plains", List.of("cave", "forest"))));

        assertThat(service.phases().phases()).extracting(OneBlockPhase::blocks).containsExactly(3L, 10L, 4L);
        assertThat(service.onBreak(ISLAND))
                .describedAs("the third break, the last of the shorter plains, comes back as the picked cave")
                .get()
                .satisfies(broken -> {
                    assertThat(broken.position().phase().key()).isEqualTo("cave");
                    assertThat(broken.nextBlock()).isEqualTo("CLAY");
                });
        assertThat(service.phasesOf(ISLAND).orElseThrow().phases())
                .extracting(OneBlockPhase::key)
                .describedAs("its pick is kept, and cave goes on to the phase after it in the list")
                .containsExactly("plains", "cave", "forest");
    }
}
