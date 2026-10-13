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

        @Override
        public long bought(IslandId id) {
            return rows.stream().filter(row -> row.startsWith(id + BOUGHT)).count();
        }
    };
    private final BoxRules rules = new BoxRules(
            5,
            1,
            8,
            Map.of("minecraft:story/mine_diamond", 2, "minecraft:story/root", 0),
            List.of("minecraft:recipes/"),
            com.uxplima.uxmskyblock.core.domain.boxed.AdvancementWorth.flat(1));
    private final BoxedService service = new BoxedService(port, rules);

    @Test
    @DisplayName("A new Boxed island's box is the starting size, and another island has none")
    void aBoxStartsSmall() {
        service.start(islandId);

        assertThat(service.radius(islandId)).hasValue(5);
        assertThat(service.isBoxed(IslandId.of(UUID.randomUUID()))).isFalse();
        assertThat(service.earn(
                        IslandId.of(UUID.randomUUID()),
                        new com.uxplima.uxmskyblock.core.domain.boxed.AdvancementFacts(
                                "minecraft:story/mine_stone", 1, true)))
                .isEmpty();
    }

    @Test
    @DisplayName("Each advancement grows the box once, by its worth, and recipes and worthless ones by nothing")
    void advancementsGrowTheBox() {
        service.start(islandId);

        assertThat(service.earn(
                        islandId,
                        new com.uxplima.uxmskyblock.core.domain.boxed.AdvancementFacts(
                                "minecraft:story/mine_stone", 1, true)))
                .contains(new BoxedService.Grown(islandId, 1, 5, 6));
        assertThat(service.earn(
                        islandId,
                        new com.uxplima.uxmskyblock.core.domain.boxed.AdvancementFacts(
                                "minecraft:story/mine_stone", 1, true)))
                .describedAs("a second member making it adds nothing")
                .isEmpty();
        assertThat(service.earn(
                        islandId,
                        new com.uxplima.uxmskyblock.core.domain.boxed.AdvancementFacts(
                                "minecraft:story/mine_diamond", 1, true)))
                .contains(new BoxedService.Grown(islandId, 2, 6, 8));
        assertThat(service.earn(
                        islandId,
                        new com.uxplima.uxmskyblock.core.domain.boxed.AdvancementFacts(
                                "minecraft:recipes/misc/bread", 1, true)))
                .isEmpty();
        assertThat(service.earn(
                        islandId,
                        new com.uxplima.uxmskyblock.core.domain.boxed.AdvancementFacts(
                                "minecraft:story/root", 1, true)))
                .isEmpty();
        assertThat(service.radius(islandId)).hasValue(8);
    }

    @Test
    @DisplayName("The box never reaches past its most, and what was earned is read back after a restart")
    void theBoxHasAnEdgeAndIsRemembered() {
        service.start(islandId);
        service.earn(
                islandId,
                new com.uxplima.uxmskyblock.core.domain.boxed.AdvancementFacts(
                        "minecraft:story/mine_diamond", 1, true));
        service.earn(
                islandId,
                new com.uxplima.uxmskyblock.core.domain.boxed.AdvancementFacts("minecraft:story/smelt_iron", 1, true));
        service.earn(
                islandId,
                new com.uxplima.uxmskyblock.core.domain.boxed.AdvancementFacts(
                        "minecraft:adventure/kill_a_mob", 1, true));
        service.earn(
                islandId,
                new com.uxplima.uxmskyblock.core.domain.boxed.AdvancementFacts(
                        "minecraft:husbandry/plant_seed", 1, true));

        assertThat(service.radius(islandId)).hasValue(8);
        BoxedService restarted = new BoxedService(port, rules);
        assertThat(restarted.prime()).isEqualTo(1);
        assertThat(restarted.radius(islandId)).hasValue(8);
        islands.remove(islandId);
        restarted.forget(islandId);
        assertThat(restarted.isBoxed(islandId)).isFalse();
        assertThatThrownBy(() -> new BoxRules(
                        5,
                        1,
                        4,
                        Map.of(),
                        List.of(),
                        com.uxplima.uxmskyblock.core.domain.boxed.AdvancementWorth.flat(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("An operator pushes a box out, or sets how far it reaches, and storage holds what they did")
    void anOperatorsBox() {
        service.start(islandId);
        service.earn(
                islandId,
                new com.uxplima.uxmskyblock.core.domain.boxed.AdvancementFacts(
                        "minecraft:story/mine_diamond", 1, true));

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

    @Test
    @DisplayName("A box is moved within what a box reaches from the island's centre, kept, and its reach covers it")
    void aBoxIsMoved() {
        Map<IslandId, com.uxplima.uxmskyblock.core.domain.boxed.BoxOffset> kept = new HashMap<>();
        BoxOffsetsPort offsets = new BoxOffsetsPort() {
            @Override
            public Map<IslandId, com.uxplima.uxmskyblock.core.domain.boxed.BoxOffset> findAll() {
                return Map.copyOf(kept);
            }

            @Override
            public void move(IslandId id, com.uxplima.uxmskyblock.core.domain.boxed.BoxOffset offset) {
                kept.put(id, offset);
            }
        };
        BoxedService moving = new BoxedService(port, rules, offsets, new SidesKept());
        moving.start(islandId);
        var near = new com.uxplima.uxmskyblock.core.domain.boxed.BoxOffset(-2, 3);
        var far = new com.uxplima.uxmskyblock.core.domain.boxed.BoxOffset(4, 0);

        assertThat(moving.offset(islandId)).isEqualTo(com.uxplima.uxmskyblock.core.domain.boxed.BoxOffset.NONE);
        assertThat(moving.canMove(islandId, near)).isTrue();
        assertThat(moving.canMove(islandId, far))
                .describedAs("5 and 4 is past 8")
                .isFalse();
        assertThat(moving.move(islandId, far).errorOrThrow()).isEqualTo("boxed.move.too_far");
        assertThat(moving.move(IslandId.of(UUID.randomUUID()), near).errorOrThrow())
                .isEqualTo("boxed.move.no_box");
        assertThat(moving.move(islandId, near).orElseThrow()).isEqualTo(near);
        assertThat(kept).containsEntry(islandId, near);
        assertThat(moving.offset(islandId)).isEqualTo(near);
        assertThat(moving.radius(islandId)).hasValue(5);
        assertThat(moving.reach(islandId))
                .describedAs("the box and how far it moved")
                .hasValue(8);
        assertThat(moving.reach(IslandId.of(UUID.randomUUID()))).isEmpty();

        moving.grant(islandId, 3);
        assertThat(moving.radius(islandId))
                .describedAs("a moved box grows no further than 8 from the centre")
                .hasValue(5);
        assertThat(moving.grant(islandId, 1).orElseThrow().toRadius()).isEqualTo(5);
        assertThat(moving.canMove(islandId, near))
                .describedAs("a box that earned 8 may stand only at the centre")
                .isFalse();
        assertThat(moving.canMove(islandId, com.uxplima.uxmskyblock.core.domain.boxed.BoxOffset.NONE))
                .isTrue();
        assertThat(moving.reach(islandId)).hasValue(8);

        BoxedService again = new BoxedService(port, rules, offsets, new SidesKept());
        again.prime();
        assertThat(again.offset(islandId)).describedAs("read back at the start").isEqualTo(near);
        islands.remove(islandId);
        again.forget(islandId);
        assertThat(again.offset(islandId)).isEqualTo(com.uxplima.uxmskyblock.core.domain.boxed.BoxOffset.NONE);
        assertThat(new com.uxplima.uxmskyblock.core.domain.boxed.BoxOffset(-7, 3).reach())
                .isEqualTo(7);
        assertThat(new com.uxplima.uxmskyblock.core.domain.boxed.BoxOffset(2, -9).reach())
                .isEqualTo(9);
    }

    private final Map<IslandId, com.uxplima.uxmskyblock.core.domain.boxed.BoxSide> chosen = new HashMap<>();

    /** The sides chosen, as a table keeps them. */
    private final class SidesKept implements BoxSidesPort {
        @Override
        public Map<IslandId, com.uxplima.uxmskyblock.core.domain.boxed.BoxSide> findAll() {
            return Map.copyOf(chosen);
        }

        @Override
        public void choose(IslandId id, com.uxplima.uxmskyblock.core.domain.boxed.BoxSide side) {
            chosen.put(id, side);
        }
    }

    @Test
    @DisplayName("Growth bought is the next purchase among the advancements, and two buying at once both count")
    void growthIsBought() {
        service.start(islandId);

        assertThat(service.buy(islandId, 2)).hasValue(new BoxedService.Grown(islandId, 2, 5, 7));
        assertThat(service.buy(islandId, 1)).hasValue(new BoxedService.Grown(islandId, 1, 7, 8));
        assertThat(service.bought(islandId)).isEqualTo(2);
        assertThat(rows).contains(islandId + BoxedIslandsPort.BOUGHT + 1, islandId + BoxedIslandsPort.BOUGHT + 2);
        assertThat(service.buy(IslandId.of(UUID.randomUUID()), 1)).isEmpty();
        assertThatThrownBy(() -> service.buy(islandId, 0)).isInstanceOf(IllegalArgumentException.class);

        // A count that lags, as when another node bought a moment before: the purchase takes the next free number.
        BoxedService lagging = new BoxedService(
                new BoxedIslandsPort() {
                    @Override
                    public Map<IslandId, Long> findAll() {
                        return port.findAll();
                    }

                    @Override
                    public OptionalLong find(IslandId id) {
                        return port.find(id);
                    }

                    @Override
                    public void add(IslandId id) {
                        port.add(id);
                    }

                    @Override
                    public boolean earn(IslandId id, String advancement, int blocks) {
                        return port.earn(id, advancement, blocks);
                    }

                    @Override
                    public void grant(IslandId id, long blocks) {
                        port.grant(id, blocks);
                    }

                    @Override
                    public long bought(IslandId id) {
                        return 0;
                    }
                },
                new BoxRules(
                        5,
                        1,
                        400,
                        Map.of(),
                        List.of(),
                        com.uxplima.uxmskyblock.core.domain.boxed.AdvancementWorth.flat(1)));
        lagging.prime();
        assertThat(lagging.buy(islandId, 1)).isPresent();
        assertThat(rows).contains(islandId + BoxedIslandsPort.BOUGHT + 3);
    }

    @Test
    @DisplayName("A box with a side grows toward it: the opposite edges stay, as far as a box reaches")
    void aBoxLeansTowardItsSide() {
        BoxedService leaning = new BoxedService(port, rules, new KeptOffsets(), new SidesKept());
        leaning.start(islandId);
        assertThat(leaning.side(islandId)).isEqualTo(com.uxplima.uxmskyblock.core.domain.boxed.BoxSide.ALL);
        leaning.buy(islandId, 1);
        assertThat(moved).describedAs("a box with no side stays where it is").isEmpty();
        assertThat(leaning.choose(
                        IslandId.of(UUID.randomUUID()), com.uxplima.uxmskyblock.core.domain.boxed.BoxSide.EAST))
                .isFalse();
        assertThat(leaning.choose(islandId, com.uxplima.uxmskyblock.core.domain.boxed.BoxSide.NORTH_WEST))
                .isTrue();
        assertThat(chosen).containsEntry(islandId, com.uxplima.uxmskyblock.core.domain.boxed.BoxSide.NORTH_WEST);

        leaning.buy(islandId, 1);
        assertThat(leaning.offset(islandId)).isEqualTo(new com.uxplima.uxmskyblock.core.domain.boxed.BoxOffset(-1, -1));
        assertThat(leaning.radius(islandId)).hasValue(7);
        // The east and south edges, 6 from the centre once the box grew alike, are where they were.
        assertThat(leaning.offset(islandId).dx() + leaning.radius(islandId).getAsInt())
                .isEqualTo(6);
        assertThat(leaning.offset(islandId).dz() + leaning.radius(islandId).getAsInt())
                .isEqualTo(6);

        leaning.earn(
                islandId,
                new com.uxplima.uxmskyblock.core.domain.boxed.AdvancementFacts("minecraft:story/mine_stone", 1, true));
        assertThat(leaning.offset(islandId))
                .describedAs("8 and 2 is past 8, so the box stays where it stands, as wide as it reaches there")
                .isEqualTo(new com.uxplima.uxmskyblock.core.domain.boxed.BoxOffset(-1, -1));
        assertThat(leaning.radius(islandId)).hasValue(7);

        leaning.choose(islandId, com.uxplima.uxmskyblock.core.domain.boxed.BoxSide.ALL);
        leaning.grant(islandId, 1);
        assertThat(leaning.offset(islandId)).isEqualTo(new com.uxplima.uxmskyblock.core.domain.boxed.BoxOffset(-1, -1));

        chosen.put(islandId, com.uxplima.uxmskyblock.core.domain.boxed.BoxSide.WEST);
        BoxedService again = new BoxedService(port, rules, new KeptOffsets(), new SidesKept());
        again.prime();
        assertThat(again.side(islandId))
                .describedAs("read back at the start")
                .isEqualTo(com.uxplima.uxmskyblock.core.domain.boxed.BoxSide.WEST);
        islands.remove(islandId);
        chosen.put(islandId, com.uxplima.uxmskyblock.core.domain.boxed.BoxSide.EAST);
        BoxedService gone = new BoxedService(port, rules, new KeptOffsets(), new SidesKept());
        gone.prime();
        gone.forget(islandId);
        assertThat(gone.side(islandId)).isEqualTo(com.uxplima.uxmskyblock.core.domain.boxed.BoxSide.ALL);
    }

    @Test
    @DisplayName("A side is named by its word, with a dash or an underscore, and steps the way it says")
    void sides() {
        assertThat(com.uxplima.uxmskyblock.core.domain.boxed.BoxSide.named(" North-East "))
                .hasValue(com.uxplima.uxmskyblock.core.domain.boxed.BoxSide.NORTH_EAST);
        assertThat(com.uxplima.uxmskyblock.core.domain.boxed.BoxSide.named("up"))
                .isEmpty();
        assertThat(com.uxplima.uxmskyblock.core.domain.boxed.BoxSide.SOUTH_WEST.word())
                .isEqualTo("south_west");
        assertThat(com.uxplima.uxmskyblock.core.domain.boxed.BoxSide.NORTH.stepZ())
                .isEqualTo(-1);
        assertThat(com.uxplima.uxmskyblock.core.domain.boxed.BoxSide.EAST.stepX())
                .isEqualTo(1);
        assertThat(com.uxplima.uxmskyblock.core.domain.boxed.BoxSide.SOUTH_WEST.stepX())
                .isEqualTo(-1);
        assertThat(com.uxplima.uxmskyblock.core.domain.boxed.BoxSide.SOUTH_WEST.stepZ())
                .isEqualTo(1);
    }

    private final Map<IslandId, com.uxplima.uxmskyblock.core.domain.boxed.BoxOffset> moved = new HashMap<>();

    /** The moved boxes, as a table keeps them. */
    private final class KeptOffsets implements BoxOffsetsPort {
        @Override
        public Map<IslandId, com.uxplima.uxmskyblock.core.domain.boxed.BoxOffset> findAll() {
            return Map.copyOf(moved);
        }

        @Override
        public void move(IslandId id, com.uxplima.uxmskyblock.core.domain.boxed.BoxOffset offset) {
            moved.put(id, offset);
        }
    }
}
