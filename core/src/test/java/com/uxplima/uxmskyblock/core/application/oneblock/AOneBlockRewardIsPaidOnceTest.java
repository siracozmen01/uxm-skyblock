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

/**
 * A OneBlock reward is paid once in each pass through the phases: a count rolled back by a crash or an operator
 * reaches it again and finds it paid, and the next pass pays it again.
 */
class AOneBlockRewardIsPaidOnceTest {

    private static final IslandId ISLAND = IslandId.of(UUID.randomUUID());

    private final Map<IslandId, Long> stored = new HashMap<>();
    private final Map<String, OneBlockRewardPort.Paid> paid = new HashMap<>();
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

    /** Keeps what the SQL table keeps: one row for each reward, its last pass, raised and never lowered. */
    private final OneBlockRewardPort rewards = new OneBlockRewardPort() {
        @Override
        public List<Paid> findAll() {
            asked.add("all");
            return List.copyOf(paid.values());
        }

        @Override
        public List<Paid> find(IslandId islandId) {
            asked.add("one");
            return paid.values().stream()
                    .filter(one -> one.islandId().equals(islandId))
                    .toList();
        }

        @Override
        public boolean pay(IslandId islandId, String phase, String reward, long cycle) {
            if (failing) {
                throw new IllegalStateException("the database is away");
            }
            String key = islandId + "/" + phase + "/" + reward;
            Paid last = paid.get(key);
            if (last != null && last.cycle() >= cycle) {
                return false;
            }
            paid.put(key, new Paid(islandId, phase, reward, cycle));
            return true;
        }

        @Override
        public void clear(IslandId islandId) {
            paid.values().removeIf(one -> one.islandId().equals(islandId));
        }
    };

    private static OneBlockPhase phase(String key, long blocks) {
        return new OneBlockPhase(key, blocks, new WeightedPool(Map.of("DIRT", 1.0)), WeightedPool.empty(), 0);
    }

    private final OneBlockPhases phases =
            new OneBlockPhases(List.of(phase("plains", 3), phase("cave", 2)), OneBlockPhases.AfterTheLast.REPEAT);

    private OneBlockService service() {
        stored.putIfAbsent(ISLAND, 0L);
        return new OneBlockService(progress, rewards, phases, new SplittableRandom(1));
    }

    @Test
    @DisplayName("A reward is owed once in a pass, again in a later one, and never in an earlier one")
    void oncePerPass() {
        OneBlockService service = service();

        assertThat(service.claim(ISLAND, "plains", "moment:2", 0)).isTrue();
        assertThat(service.claim(ISLAND, "plains", "moment:2", 0)).isFalse();
        assertThat(service.claim(ISLAND, "plains", "finished", 0))
                .describedAs("another reward of the phase")
                .isTrue();
        assertThat(service.claim(ISLAND, "cave", "moment:2", 0))
                .describedAs("the same reward of another phase")
                .isTrue();
        assertThat(service.claim(ISLAND, "plains", "moment:2", 1)).isTrue();
        assertThat(service.claim(ISLAND, "plains", "moment:2", 0))
                .describedAs("a count set back into the pass before")
                .isFalse();
        assertThat(service.claim(IslandId.of(UUID.randomUUID()), "plains", "moment:2", 0))
                .describedAs("an island that is no OneBlock island is owed nothing")
                .isFalse();
    }

    @Test
    @DisplayName("What storage holds is read with the island, so a crash that rolls the count back pays nothing twice")
    void afterACrash() {
        OneBlockService before = service();
        before.prime();
        assertThat(before.claim(ISLAND, "plains", "moment:2", 0)).isTrue();
        assertThat(before.record(ISLAND, "plains", "moment:2", 0)).isTrue();

        OneBlockService primed = service();
        primed.prime();
        assertThat(primed.claim(ISLAND, "plains", "moment:2", 0)).isFalse();
        assertThat(primed.claim(ISLAND, "plains", "moment:2", 1)).isTrue();

        asked.clear();
        OneBlockService read = service();
        assertThat(read.claim(ISLAND, "plains", "moment:2", 0))
                .describedAs("read with the island the first time it is asked about")
                .isFalse();
        assertThat(asked).containsExactly("one");
    }

    @Test
    @DisplayName("Storage has the last word: a reward it already holds, or cannot record, is not paid")
    void storageDecides() {
        OneBlockService service = service();
        paid.put(ISLAND + "/plains/began", new OneBlockRewardPort.Paid(ISLAND, "plains", "began", 4));

        assertThat(service.record(ISLAND, "plains", "began", 4)).isFalse();
        assertThat(service.record(ISLAND, "plains", "began", 5)).isTrue();
        failing = true;
        assertThat(service.record(ISLAND, "plains", "began", 6)).isFalse();
    }

    @Test
    @DisplayName("An island that starts over is owed every reward again")
    void startOver() {
        OneBlockService service = service();
        assertThat(service.record(ISLAND, "plains", "moment:2", 0)).isTrue();

        service.start(ISLAND, 0, 100, 0);

        assertThat(paid).isEmpty();
        assertThat(service.claim(ISLAND, "plains", "moment:2", 0)).isTrue();
    }

    @Test
    @DisplayName("The break that goes round to the first phase belongs to one pass and turns the block into the next")
    void theNextPass() {
        OneBlockService service = service();
        for (int i = 0; i < 4; i++) {
            service.onBreak(ISLAND);
        }

        OneBlockService.Broken round = service.onBreak(ISLAND).orElseThrow();

        assertThat(round.cycle()).isZero();
        assertThat(round.nextCycle()).isEqualTo(1);
        assertThat(round.position().phase().key()).isEqualTo("plains");
        OneBlockService.Broken next = service.onBreak(ISLAND).orElseThrow();
        assertThat(next.cycle()).isEqualTo(1);
        assertThat(next.nextCycle()).isEqualTo(1);
    }
}
