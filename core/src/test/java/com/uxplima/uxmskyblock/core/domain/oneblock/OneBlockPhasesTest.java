package com.uxplima.uxmskyblock.core.domain.oneblock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A OneBlock island walks its phases by the count of blocks it has broken. */
class OneBlockPhasesTest {

    private static final OneBlockPhase PLAINS = phase("plains", 3, Map.of("GRASS_BLOCK", 1.0));
    private static final OneBlockPhase UNDERGROUND = phase("underground", 2, Map.of("STONE", 1.0));

    @Test
    @DisplayName("Each count of breaks falls in the phase it has reached, and the first break of a phase says so")
    void theCountFindsThePhase() {
        OneBlockPhases phases = new OneBlockPhases(List.of(PLAINS, UNDERGROUND), OneBlockPhases.AfterTheLast.STAY);

        assertThat(phases.positionAt(0).phase()).isEqualTo(PLAINS);
        assertThat(phases.positionAt(2).phase()).isEqualTo(PLAINS);
        assertThat(phases.positionAt(2).intoPhase()).isEqualTo(2);
        assertThat(phases.positionAt(3).phase()).isEqualTo(UNDERGROUND);
        assertThat(phases.positionAt(3).intoPhase()).isZero();
        assertThat(phases.startsAPhase(3)).isTrue();
        assertThat(phases.startsAPhase(4)).isFalse();
        assertThat(phases.startsAPhase(0))
                .describedAs("the island's first block is no news")
                .isFalse();
    }

    @Test
    @DisplayName("Past the last phase an island stays in it, or goes round again, as the operator chose")
    void pastTheLast() {
        OneBlockPhases staying = new OneBlockPhases(List.of(PLAINS, UNDERGROUND), OneBlockPhases.AfterTheLast.STAY);
        OneBlockPhases repeating = new OneBlockPhases(List.of(PLAINS, UNDERGROUND), OneBlockPhases.AfterTheLast.REPEAT);

        assertThat(staying.positionAt(40).phase()).isEqualTo(UNDERGROUND);
        assertThat(staying.startsAPhase(5)).isFalse();
        assertThat(repeating.positionAt(5).phase()).isEqualTo(PLAINS);
        assertThat(repeating.startsAPhase(5)).isTrue();
        assertThat(repeating.positionAt(8).phase()).isEqualTo(UNDERGROUND);
    }

    @Test
    @DisplayName("The next block is drawn from the phase's pool by weight")
    void theNextBlockIsDrawnByWeight() {
        OneBlockPhase mixed = phase("mixed", 100, Map.of("DIRT", 3.0, "IRON_ORE", 1.0));
        OneBlockPhases phases = new OneBlockPhases(List.of(mixed), OneBlockPhases.AfterTheLast.STAY);
        SplittableRandom random = new SplittableRandom(42);
        Map<String, Integer> drawn = new HashMap<>();

        for (int i = 0; i < 40_000; i++) {
            drawn.merge(phases.nextBlock(i % 100, random), 1, Integer::sum);
        }

        assertThat(drawn).containsOnlyKeys("DIRT", "IRON_ORE");
        assertThat(drawn.getOrDefault("DIRT", 0) / (double) drawn.getOrDefault("IRON_ORE", 1))
                .isBetween(2.8, 3.2);
    }

    @Test
    @DisplayName("A creature comes as often as the phase's chance says, and never from an empty pool")
    void creaturesComeByChance() {
        OneBlockPhase farm = new OneBlockPhase(
                "farm", 10, new WeightedPool(Map.of("DIRT", 1.0)), new WeightedPool(Map.of("COW", 1.0)), 0.25);
        OneBlockPhases phases = new OneBlockPhases(List.of(farm, PLAINS), OneBlockPhases.AfterTheLast.STAY);
        SplittableRandom random = new SplittableRandom(7);

        int cows = 0;
        for (int i = 0; i < 20_000; i++) {
            if (phases.nextCreature(i % 10, random).isPresent()) {
                cows++;
            }
        }

        assertThat(cows / 20_000.0).isBetween(0.23, 0.27);
        assertThat(phases.nextCreature(10, random))
                .describedAs("plains brings none")
                .isEmpty();
    }

    @Test
    @DisplayName("A phase with no block, no length, a bad chance or a second use of a name is refused")
    void badPhasesAreRefused() {
        assertThatThrownBy(() -> phase("empty", 3, Map.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> phase("zero", 0, Map.of("DIRT", 1.0))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WeightedPool(Map.of("DIRT", -1.0))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OneBlockPhase(
                        "chance", 3, new WeightedPool(Map.of("DIRT", 1.0)), WeightedPool.empty(), 1.5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OneBlockPhases(List.of(PLAINS, PLAINS), OneBlockPhases.AfterTheLast.STAY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OneBlockPhases(List.of(), OneBlockPhases.AfterTheLast.STAY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("A pass through the phases counts from 0, and an island that stays in the last never starts another")
    void cycles() {
        OneBlockPhases repeat = new OneBlockPhases(List.of(PLAINS, UNDERGROUND), OneBlockPhases.AfterTheLast.REPEAT);
        OneBlockPhases stay = new OneBlockPhases(List.of(PLAINS, UNDERGROUND), OneBlockPhases.AfterTheLast.STAY);

        assertThat(repeat.cycleOf(0)).isZero();
        assertThat(repeat.cycleOf(4)).isZero();
        assertThat(repeat.cycleOf(5)).isEqualTo(1);
        assertThat(repeat.cycleOf(11)).isEqualTo(2);
        assertThat(stay.cycleOf(11)).isZero();
        assertThatThrownBy(() -> repeat.cycleOf(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    private static OneBlockPhase phase(String key, long blocks, Map<String, Double> weights) {
        return new OneBlockPhase(key, blocks, new WeightedPool(weights), WeightedPool.empty(), 0);
    }
}
