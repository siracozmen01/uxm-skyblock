package com.uxplima.uxmskyblock.core.domain.oneblock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A OneBlock phase may name the phases that can follow it, and each island's way through them is its own. */
class APhaseMayBranchTest {

    private static OneBlockPhase phase(String key, long blocks) {
        return new OneBlockPhase(key, blocks, new WeightedPool(Map.of("DIRT", 1.0)), WeightedPool.empty(), 0);
    }

    private static final List<OneBlockPhase> FILE =
            List.of(phase("plains", 3), phase("cave", 4), phase("forest", 5), phase("nether", 6), phase("end", 7));

    private static List<String> keys(OneBlockPhases way) {
        return way.phases().stream().map(OneBlockPhase::key).toList();
    }

    @Test
    @DisplayName("Without a pick the first is followed, a phase that names none is followed by the next in the list")
    void unpicked() {
        OneBlockPhases phases = new OneBlockPhases(
                FILE,
                OneBlockPhases.AfterTheLast.REPEAT,
                Map.of("plains", List.of("cave", "forest"), "cave", List.of("nether"), "forest", List.of("nether")));

        assertThat(keys(phases.route(Map.of()))).containsExactly("plains", "cave", "nether", "end");
        assertThat(keys(phases.route(Map.of("plains", "forest")))).containsExactly("plains", "forest", "nether", "end");
        assertThat(keys(phases.route(Map.of("plains", "end"))))
                .describedAs("a pick that is no option of the phase")
                .containsExactly("plains", "cave", "nether", "end");
        assertThat(phases.route(Map.of("plains", "forest")).cycleLength()).isEqualTo(3 + 5 + 6 + 7);
        assertThat(phases.route(Map.of("plains", "forest")).afterTheLast())
                .isEqualTo(OneBlockPhases.AfterTheLast.REPEAT);
    }

    @Test
    @DisplayName("The way ends at a phase that names nothing to follow it, or at one already gone through")
    void ends() {
        OneBlockPhases stops = new OneBlockPhases(
                FILE, OneBlockPhases.AfterTheLast.STAY, Map.of("plains", List.of("forest"), "forest", List.of()));
        assertThat(keys(stops.route(Map.of()))).containsExactly("plains", "forest");

        OneBlockPhases loops =
                new OneBlockPhases(FILE, OneBlockPhases.AfterTheLast.STAY, Map.of("cave", List.of("plains")));
        assertThat(keys(loops.route(Map.of()))).containsExactly("plains", "cave");
    }

    @Test
    @DisplayName("A phase with no branch is its own way, and only a choice of two or more is offered")
    void plain() {
        OneBlockPhases plain = new OneBlockPhases(FILE, OneBlockPhases.AfterTheLast.STAY);
        assertThat(plain.route(Map.of("plains", "end"))).isSameAs(plain);

        OneBlockPhases one = new OneBlockPhases(
                FILE,
                OneBlockPhases.AfterTheLast.STAY,
                Map.of("plains", List.of("forest"), "cave", List.of("nether", "end")));
        assertThat(one.optionsAfter("plains")).isEmpty();
        assertThat(one.optionsAfter("cave")).containsExactly("nether", "end");
        assertThat(one.optionsAfter("end")).isEmpty();
    }

    @Test
    @DisplayName("A branch from or to a phase that is not there cannot be")
    void unknown() {
        assertThatThrownBy(() ->
                        new OneBlockPhases(FILE, OneBlockPhases.AfterTheLast.STAY, Map.of("plains", List.of("void"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                        new OneBlockPhases(FILE, OneBlockPhases.AfterTheLast.STAY, Map.of("void", List.of("plains"))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
