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

/** A OneBlock island's draws are made a set number of breaks ahead, so a creature is known before it comes. */
class AnIslandDrawsAheadTest {

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

    /** Each break draws one of four creatures, and one of four blocks, so a draw out of turn shows. */
    private static OneBlockPhase phase(String key, long blocks) {
        return new OneBlockPhase(
                key,
                blocks,
                new WeightedPool(Map.of("DIRT", 1.0, "STONE", 1.0, "SAND", 1.0, "CLAY", 1.0)),
                new WeightedPool(Map.of("zombie", 1.0, "skeleton", 1.0, "spider", 1.0, "creeper", 1.0)),
                1.0);
    }

    private final OneBlockPhases phases = new OneBlockPhases(
            List.of(phase("plains", 20), phase("cave", 20), phase("forest", 20)),
            OneBlockPhases.AfterTheLast.STAY,
            Map.of("plains", List.of("cave", "forest")));

    private OneBlockService service(int ahead) {
        stored.put(ISLAND, 0L);
        OneBlockService service = new OneBlockService(
                progress,
                OneBlockRewardPort.NONE,
                OneBlockWaitPort.NONE,
                OneBlockBranchPort.NONE,
                phases,
                new SplittableRandom(7));
        service.lookAhead(ahead);
        return service;
    }

    @Test
    @DisplayName("Each break tells the creature drawn for the break that many ahead, and that creature comes then")
    void toldThenComes() {
        OneBlockService service = service(3);

        OneBlockService.Broken first = service.onBreak(ISLAND).orElseThrow();
        assertThat(first.coming())
                .describedAs("the first break draws the whole way ahead")
                .extracting(OneBlockService.Coming::inBreaks)
                .containsExactly(1L, 2L, 3L);
        List<String> told = new ArrayList<>(
                first.coming().stream().map(OneBlockService.Coming::creature).toList());
        List<String> came = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            OneBlockService.Broken next = service.onBreak(ISLAND).orElseThrow();
            came.add(next.creature().orElseThrow());
            assertThat(next.coming()).singleElement().satisfies(one -> {
                assertThat(one.inBreaks()).isEqualTo(3);
                told.add(one.creature());
            });
        }
        assertThat(came).containsExactlyElementsOf(told.subList(0, 6));
    }

    @Test
    @DisplayName("Nothing ahead tells nothing")
    void none() {
        OneBlockService service = service(0);

        assertThat(service.onBreak(ISLAND).orElseThrow().coming()).isEmpty();
        assertThat(service.onBreak(ISLAND).orElseThrow().coming()).isEmpty();
    }

    @Test
    @DisplayName("A count set or a way picked draws again from there")
    void drawnAgain() {
        OneBlockService service = service(2);
        for (int i = 0; i < 4; i++) {
            service.onBreak(ISLAND);
        }

        service.setBroken(ISLAND, 1);
        assertThat(service.onBreak(ISLAND).orElseThrow().coming())
                .describedAs("set back, the way ahead is drawn and told again")
                .hasSize(2);
        service.onBreak(ISLAND);
        service.onBreak(ISLAND);
        service.addBroken(ISLAND, -3);
        assertThat(service.onBreak(ISLAND).orElseThrow().coming()).hasSize(2);
        service.setBroken(ISLAND, 18);
        service.onBreak(ISLAND);
        assertThat(service.pick(ISLAND, "plains", "forest")).isTrue();
        assertThat(service.onBreak(ISLAND).orElseThrow().coming())
                .describedAs("the draws ahead followed the first way, so they are drawn again along the one picked")
                .hasSize(2);
    }

    @Test
    @DisplayName("A break is never drawn behind")
    void notBehind() {
        assertThatThrownBy(() -> service(-1)).isInstanceOf(IllegalArgumentException.class);
    }
}
