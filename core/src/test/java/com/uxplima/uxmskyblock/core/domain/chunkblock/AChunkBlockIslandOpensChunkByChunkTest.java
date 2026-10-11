package com.uxplima.uxmskyblock.core.domain.chunkblock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A ChunkBlock island starts as the one chunk its magic block stands in and opens the chunks beside
 * it with its level, which it holds rather than spends. When the level falls the newest chunks close
 * first, and the first chunk never closes.
 */
class AChunkBlockIslandOpensChunkByChunkTest {

    /** An island of radius 40 around 8, 8: chunks -2 to 3 on each axis overlap it. */
    private static final IslandBounds BOUNDS = IslandBounds.fromCenterAndRadius(8, 8, 40);

    private static final ChunkUnlockRules RULES = new ChunkUnlockRules(List.of(1L, 3L, 6L), 4);

    private final ChunkPos origin = ChunkPos.ofBlock(8, 8);

    @Test
    @DisplayName("The first unlocks cost what the list says, and every one after it the step more")
    void theLadder() {
        assertThat(RULES.requiredFor(1)).isEqualTo(1);
        assertThat(RULES.requiredFor(3)).isEqualTo(6);
        assertThat(RULES.requiredFor(4)).isEqualTo(10);
        assertThat(RULES.requiredFor(6)).isEqualTo(18);
        assertThat(new ChunkUnlockRules(List.of(), 2).requiredFor(3)).isEqualTo(6);
        assertThatThrownBy(() -> new ChunkUnlockRules(List.of(5L, 2L), 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChunkUnlockRules(List.of(), 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("An island starts with one chunk: the one its block stands in")
    void itStartsWithOneChunk() {
        ChunkTerritory territory = ChunkTerritory.startingAt(origin);

        assertThat(territory.size()).isEqualTo(1);
        assertThat(territory.isOpen(new ChunkPos(0, 0))).isTrue();
        assertThat(territory.isOpen(new ChunkPos(1, 0))).isFalse();
        assertThat(ChunkPos.ofBlock(-1, 16)).isEqualTo(new ChunkPos(-1, 1));
    }

    @Test
    @DisplayName("A chunk opens beside the territory once the level reaches what the next one needs")
    void aChunkOpensBesideTheTerritory() {
        ChunkTerritory territory = ChunkTerritory.startingAt(origin);

        assertThat(territory.unlock(new ChunkPos(1, 0), BOUNDS, RULES, 0))
                .isEqualTo(ChunkTerritory.Unlock.LEVEL_TOO_LOW);
        assertThat(territory.unlock(new ChunkPos(2, 0), BOUNDS, RULES, 99))
                .isEqualTo(ChunkTerritory.Unlock.NOT_BESIDE_TERRITORY);
        assertThat(territory.unlock(new ChunkPos(1, 1), BOUNDS, RULES, 99))
                .describedAs("a corner is not a side")
                .isEqualTo(ChunkTerritory.Unlock.NOT_BESIDE_TERRITORY);
        assertThat(territory.unlock(new ChunkPos(1, 0), BOUNDS, RULES, 1)).isEqualTo(ChunkTerritory.Unlock.UNLOCKED);
        assertThat(territory.unlock(new ChunkPos(1, 0), BOUNDS, RULES, 99))
                .isEqualTo(ChunkTerritory.Unlock.ALREADY_OPEN);
        assertThat(territory.unlock(new ChunkPos(2, 0), BOUNDS, RULES, 2))
                .describedAs("the second chunk needs level 3")
                .isEqualTo(ChunkTerritory.Unlock.LEVEL_TOO_LOW);
        assertThat(territory.unlock(new ChunkPos(2, 0), BOUNDS, RULES, 3)).isEqualTo(ChunkTerritory.Unlock.UNLOCKED);
        assertThat(territory.opened()).containsExactly(new ChunkPos(1, 0), new ChunkPos(2, 0));
    }

    @Test
    @DisplayName("No chunk opens past the island's edge")
    void theIslandsEdgeHolds() {
        ChunkTerritory territory =
                new ChunkTerritory(origin, List.of(new ChunkPos(1, 0), new ChunkPos(2, 0), new ChunkPos(3, 0)));

        assertThat(territory.unlock(new ChunkPos(4, 0), BOUNDS, RULES, 999))
                .isEqualTo(ChunkTerritory.Unlock.OUTSIDE_ISLAND);
        assertThat(new ChunkPos(3, 0).overlaps(BOUNDS)).isTrue();
        assertThat(new ChunkPos(-3, 0).overlaps(BOUNDS)).isFalse();
    }

    @Test
    @DisplayName("A falling level closes the newest chunks first, never the first chunk, and the rest stay joined")
    void aFallingLevelClosesNewestFirst() {
        ChunkTerritory territory = new ChunkTerritory(
                origin, List.of(new ChunkPos(1, 0), new ChunkPos(1, 1), new ChunkPos(0, 1), new ChunkPos(-1, 1)));

        assertThat(territory.relockFor(10, RULES)).isEmpty();
        assertThat(territory.relockFor(5, RULES))
                .describedAs("four chunks need 10, three need 6: level 5 keeps two")
                .containsExactly(new ChunkPos(-1, 1), new ChunkPos(0, 1));
        assertThat(territory.opened()).containsExactly(new ChunkPos(1, 0), new ChunkPos(1, 1));
        assertThat(territory.relockFor(0, RULES)).containsExactly(new ChunkPos(1, 1), new ChunkPos(1, 0));
        assertThat(territory.size()).isEqualTo(1);
        assertThat(territory.isOpen(origin)).isTrue();
    }

    @Test
    @DisplayName("A chunk paid for never closes, and the ladder prices only the chunks the level holds")
    void aBoughtChunkStaysOpen() {
        ChunkTerritory territory = ChunkTerritory.startingAt(origin);

        assertThat(territory.open(new ChunkPos(1, 0), BOUNDS, false)).isEqualTo(ChunkTerritory.Unlock.UNLOCKED);
        assertThat(territory.open(new ChunkPos(2, 0), BOUNDS, true)).isEqualTo(ChunkTerritory.Unlock.UNLOCKED);
        assertThat(territory.open(new ChunkPos(2, 0), BOUNDS, true)).isEqualTo(ChunkTerritory.Unlock.ALREADY_OPEN);

        assertThat(territory.bought()).containsExactly(new ChunkPos(1, 0));
        assertThat(territory.heldCount()).isEqualTo(1);
        assertThat(territory.nextRequirement(RULES)).isEqualTo(RULES.requiredFor(2));
        assertThat(territory.relockFor(0, RULES)).containsExactly(new ChunkPos(2, 0));
        assertThat(territory.isOpen(new ChunkPos(1, 0))).isTrue();
        assertThat(territory.relockFor(0, RULES)).isEmpty();
    }

    @Test
    @DisplayName("A held chunk whose closing would cut a paid one off stays, and the next newest closes instead")
    void aPaidChunkIsNeverCutOff() {
        ChunkPos held = new ChunkPos(1, 0);
        ChunkPos paid = new ChunkPos(2, 0);
        ChunkPos side = new ChunkPos(0, 1);
        ChunkTerritory territory = new ChunkTerritory(origin, List.of(side, held, paid), java.util.Set.of(paid));

        assertThat(territory.relockFor(0, RULES)).containsExactly(side);
        assertThat(territory.isOpen(held)).isTrue();
        assertThat(territory.isOpen(paid)).isTrue();
        assertThat(territory.heldCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("A bought chunk is an opened one")
    void boughtIsOpened() {
        assertThatThrownBy(() -> new ChunkTerritory(origin, List.of(), java.util.Set.of(new ChunkPos(1, 0))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("A player shut out of a chunk is put in the nearest one still open")
    void theNearestOpenChunk() {
        ChunkTerritory territory = new ChunkTerritory(origin, List.of(new ChunkPos(1, 0), new ChunkPos(2, 0)));

        assertThat(territory.nearestOpenTo(new ChunkPos(3, 0))).isEqualTo(new ChunkPos(2, 0));
        assertThat(territory.nearestOpenTo(new ChunkPos(-2, 0))).isEqualTo(origin);
    }

    @Test
    @DisplayName("A territory that names a chunk twice is refused")
    void aChunkIsOpenedOnce() {
        assertThatThrownBy(() -> new ChunkTerritory(origin, List.of(new ChunkPos(1, 0), new ChunkPos(1, 0))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChunkTerritory(origin, List.of(origin)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
