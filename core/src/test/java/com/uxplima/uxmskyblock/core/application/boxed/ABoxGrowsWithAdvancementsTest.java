package com.uxplima.uxmskyblock.core.application.boxed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.domain.boxed.BoxRules;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A Boxed island's box starts small and grows as its players make advancements: each advancement once
 * for the island, by what the operator says it is worth, never past the most the box can reach.
 */
class ABoxGrowsWithAdvancementsTest {

    private final IslandId islandId = IslandId.of(UUID.randomUUID());
    private final Map<IslandId, Long> islands = new HashMap<>();
    private final Set<String> rows = new HashSet<>();
    private final BoxedIslandsPort port = new BoxedIslandsPort() {
        @Override
        public Map<IslandId, Long> findAll() {
            return Map.copyOf(islands);
        }

        @Override
        public OptionalLong find(IslandId id) {
            Long blocks = islands.get(id);
            return blocks == null ? OptionalLong.empty() : OptionalLong.of(blocks);
        }

        @Override
        public void add(IslandId id) {
            islands.putIfAbsent(id, 0L);
        }

        @Override
        public boolean earn(IslandId id, String advancement, int blocks) {
            if (!rows.add(id + advancement)) {
                return false;
            }
            islands.merge(id, (long) blocks, Long::sum);
            return true;
        }

        @Override
        public void grant(IslandId id, long blocks) {
            islands.merge(id, blocks, Long::sum);
        }
    };
    private final BoxRules rules = new BoxRules(
            5,
            1,
            8,
            Map.of("minecraft:story/mine_diamond", 2, "minecraft:story/root", 0),
            List.of("minecraft:recipes/"));
    private final BoxedService service = new BoxedService(port, rules);

    @Test
    @DisplayName("A new Boxed island's box is the starting size, and another island has none")
    void aBoxStartsSmall() {
        service.start(islandId);

        assertThat(service.radius(islandId)).hasValue(5);
        assertThat(service.isBoxed(IslandId.of(UUID.randomUUID()))).isFalse();
        assertThat(service.earn(IslandId.of(UUID.randomUUID()), "minecraft:story/mine_stone"))
                .isEmpty();
    }

    @Test
    @DisplayName("Each advancement grows the box once, by its worth, and recipes and worthless ones by nothing")
    void advancementsGrowTheBox() {
        service.start(islandId);

        assertThat(service.earn(islandId, "minecraft:story/mine_stone"))
                .contains(new BoxedService.Grown(islandId, 1, 5, 6));
        assertThat(service.earn(islandId, "minecraft:story/mine_stone"))
                .describedAs("a second member making it adds nothing")
                .isEmpty();
        assertThat(service.earn(islandId, "minecraft:story/mine_diamond"))
                .contains(new BoxedService.Grown(islandId, 2, 6, 8));
        assertThat(service.earn(islandId, "minecraft:recipes/misc/bread")).isEmpty();
        assertThat(service.earn(islandId, "minecraft:story/root")).isEmpty();
        assertThat(service.radius(islandId)).hasValue(8);
    }

    @Test
    @DisplayName("The box never reaches past its most, and what was earned is read back after a restart")
    void theBoxHasAnEdgeAndIsRemembered() {
        service.start(islandId);
        service.earn(islandId, "minecraft:story/mine_diamond");
        service.earn(islandId, "minecraft:story/smelt_iron");
        service.earn(islandId, "minecraft:adventure/kill_a_mob");
        service.earn(islandId, "minecraft:husbandry/plant_seed");

        assertThat(service.radius(islandId)).hasValue(8);
        BoxedService restarted = new BoxedService(port, rules);
        assertThat(restarted.prime()).isEqualTo(1);
        assertThat(restarted.radius(islandId)).hasValue(8);
        islands.remove(islandId);
        restarted.forget(islandId);
        assertThat(restarted.isBoxed(islandId)).isFalse();
        assertThatThrownBy(() -> new BoxRules(5, 1, 4, Map.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("An operator pushes a box out, or sets how far it reaches, and storage holds what they did")
    void anOperatorsBox() {
        service.start(islandId);
        service.earn(islandId, "minecraft:story/mine_diamond");

        assertThat(service.grant(islandId, 1)).contains(new BoxedService.Grown(islandId, 1, 7, 8));
        assertThat(islands).containsEntry(islandId, 3L);
        assertThat(service.resize(islandId, 6)).contains(new BoxedService.Grown(islandId, -2, 8, 6));
        assertThat(service.radius(islandId)).hasValue(6);
        assertThat(islands).describedAs("one block earned past the start").containsEntry(islandId, 1L);
        service.forget(islandId);
        assertThat(service.radius(islandId))
                .describedAs("read back from storage")
                .hasValue(6);

        assertThatThrownBy(() -> service.resize(islandId, 9)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.resize(islandId, 4)).isInstanceOf(IllegalArgumentException.class);
        IslandId ordinary = IslandId.of(UUID.randomUUID());
        assertThat(service.grant(ordinary, 1)).isEmpty();
        assertThat(service.resize(ordinary, 6)).isEmpty();
    }
}
