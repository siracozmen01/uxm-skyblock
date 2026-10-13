package com.uxplima.uxmskyblock.core.application.oneblock;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.application.oneblock.OneBlockContributionPort.Contribution;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Each player's breaks on a OneBlock island are counted at once, written later, and kept when storage refuses. */
class EachMemberBreaksCountTest {

    private static final IslandId ISLAND = IslandId.of(new UUID(0, 1));
    private static final IslandId OTHER = IslandId.of(new UUID(0, 2));
    private static final PlayerUuid ADA = PlayerUuid.of(new UUID(0, 10));
    private static final PlayerUuid BO = PlayerUuid.of(new UUID(0, 11));
    private static final PlayerUuid CY = PlayerUuid.of(new UUID(0, 12));

    /** Storage in a list, that refuses while {@code refusing} is set. */
    private static final class Kept implements OneBlockContributionPort {
        final List<Contribution> added = new ArrayList<>();
        final List<IslandId> cleared = new ArrayList<>();
        final List<Contribution> stored = new ArrayList<>();
        boolean refusing;

        @Override
        public List<Contribution> findAll() {
            return List.copyOf(stored);
        }

        @Override
        public void add(IslandId islandId, PlayerUuid player, long breaks) {
            if (refusing) {
                throw new IllegalStateException("the database is away");
            }
            added.add(new Contribution(islandId, player, breaks));
        }

        @Override
        public void clear(IslandId islandId) {
            cleared.add(islandId);
        }
    }

    @Test
    @DisplayName("Breaks count at once, the most first and of two alike the lower uuid, each island apart")
    void counted() {
        OneBlockContributions breaks = new OneBlockContributions(new Kept());

        breaks.count(ISLAND, BO);
        breaks.count(ISLAND, CY);
        breaks.count(ISLAND, ADA);
        breaks.count(ISLAND, ADA);
        breaks.count(OTHER, BO);

        assertThat(breaks.of(ISLAND))
                .containsExactly(
                        new Contribution(ISLAND, ADA, 2),
                        new Contribution(ISLAND, BO, 1),
                        new Contribution(ISLAND, CY, 1));
        assertThat(breaks.of(ISLAND, ADA)).isEqualTo(2);
        assertThat(breaks.of(OTHER, ADA)).isZero();
        assertThat(breaks.of(IslandId.of(UUID.randomUUID()))).isEmpty();
    }

    @Test
    @DisplayName("A flush writes what is new once, and keeps what storage refused for the next")
    void flushed() {
        Kept kept = new Kept();
        OneBlockContributions breaks = new OneBlockContributions(kept);
        breaks.count(ISLAND, ADA);
        breaks.count(ISLAND, ADA);

        kept.refusing = true;
        assertThat(breaks.flush()).isZero();
        kept.refusing = false;
        breaks.count(ISLAND, ADA);
        assertThat(breaks.flush()).isEqualTo(1);
        assertThat(breaks.flush()).describedAs("nothing new").isZero();

        assertThat(kept.added).containsExactly(new Contribution(ISLAND, ADA, 3));
        assertThat(breaks.of(ISLAND, ADA)).isEqualTo(3);
    }

    @Test
    @DisplayName("A start reads what storage holds, and an island that starts over forgets its counts")
    void primedAndStarted() {
        Kept kept = new Kept();
        kept.stored.add(new Contribution(ISLAND, ADA, 40));
        kept.stored.add(new Contribution(OTHER, BO, 5));
        OneBlockContributions breaks = new OneBlockContributions(kept);

        assertThat(breaks.prime()).isEqualTo(2);
        breaks.count(ISLAND, ADA);
        assertThat(breaks.of(ISLAND, ADA)).isEqualTo(41);
        breaks.flush();
        assertThat(kept.added)
                .describedAs("only the new break is added")
                .containsExactly(new Contribution(ISLAND, ADA, 1));

        breaks.start(ISLAND);
        assertThat(breaks.of(ISLAND)).isEmpty();
        assertThat(kept.cleared).containsExactly(ISLAND);
        assertThat(breaks.of(OTHER, BO)).isEqualTo(5);
    }
}
