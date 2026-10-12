package com.uxplima.uxmskyblock.core.application.parkour;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Parkour courses are held in memory, and a finished run is weighed against the runner's best. */
class ParkourServiceTest {

    private final Set<IslandId> table = new HashSet<>();
    private final Map<String, ParkourPort.Best> bests = new HashMap<>();
    private final ParkourService service = new ParkourService(new ParkourPort() {
        @Override
        public Set<IslandId> findAll() {
            return Set.copyOf(table);
        }

        @Override
        public boolean exists(IslandId course) {
            return table.contains(course);
        }

        @Override
        public void add(IslandId course) {
            table.add(course);
        }

        @Override
        public OptionalLong best(IslandId course, PlayerUuid runner) {
            ParkourPort.Best best = bests.get(course + "/" + runner);
            return best == null ? OptionalLong.empty() : OptionalLong.of(best.millis());
        }

        @Override
        public void finish(IslandId course, PlayerUuid runner, long millis) {
            bests.merge(
                    course + "/" + runner,
                    new ParkourPort.Best(runner, millis, 1),
                    (was, now) -> new ParkourPort.Best(runner, Math.min(was.millis(), millis), was.runs() + 1));
        }

        @Override
        public int clear(IslandId cleared, java.util.Optional<PlayerUuid> runner) {
            int before = bests.size();
            bests.keySet()
                    .removeIf(key -> key.startsWith(cleared + "/")
                            && runner.map(one -> key.equals(cleared + "/" + one))
                                    .orElse(true));
            return before - bests.size();
        }

        @Override
        public List<ParkourPort.Runs> mostRun(int limit) {

            return List.of();
        }

        @Override
        public List<ParkourPort.Best> top(IslandId course, int limit) {
            List<ParkourPort.Best> all = new ArrayList<>(bests.values());
            all.sort(Comparator.comparingLong(ParkourPort.Best::millis));
            return all.subList(0, Math.min(limit, all.size()));
        }
    });

    private final IslandId course = IslandId.of(UUID.randomUUID());
    private final PlayerUuid runner = PlayerUuid.of(UUID.randomUUID());

    @Test
    @DisplayName("A first finish is the best, a faster one beats it, and a slower one leaves it")
    void aRunIsWeighed() {
        assertThat(service.finish(course, runner, Duration.ofSeconds(30)))
                .isEqualTo(new ParkourService.Finish.First(Duration.ofSeconds(30)));
        assertThat(service.finish(course, runner, Duration.ofSeconds(25)))
                .isEqualTo(new ParkourService.Finish.Beaten(Duration.ofSeconds(25), Duration.ofSeconds(30)));
        assertThat(service.finish(course, runner, Duration.ofSeconds(25)))
                .isEqualTo(new ParkourService.Finish.Slower(Duration.ofSeconds(25), Duration.ofSeconds(25)));
        assertThat(service.top(course, 0)).extracting(ParkourPort.Best::millis).containsExactly(25_000L);
        assertThat(service.top(course, 5).getFirst().runs()).isEqualTo(3);
    }

    @Test
    @DisplayName("An operator forgets one runner's runs on a course, or every runner's, and the next finish is a first")
    void runsAreForgotten() {
        PlayerUuid other = PlayerUuid.of(UUID.randomUUID());
        IslandId elsewhere = IslandId.of(UUID.randomUUID());
        service.finish(course, runner, Duration.ofSeconds(30));
        service.finish(course, other, Duration.ofSeconds(40));
        service.finish(elsewhere, runner, Duration.ofSeconds(50));

        assertThat(service.clear(course, java.util.Optional.of(runner))).isEqualTo(1);
        assertThat(service.finish(course, runner, Duration.ofSeconds(35)))
                .isEqualTo(new ParkourService.Finish.First(Duration.ofSeconds(35)));
        assertThat(service.clear(course, java.util.Optional.empty())).isEqualTo(2);
        assertThat(service.finish(course, other, Duration.ofSeconds(45)))
                .isEqualTo(new ParkourService.Finish.First(Duration.ofSeconds(45)));
        assertThat(service.finish(elsewhere, runner, Duration.ofSeconds(55)))
                .describedAs("another course keeps its runs")
                .isEqualTo(new ParkourService.Finish.Slower(Duration.ofSeconds(55), Duration.ofSeconds(50)));
    }

    @Test
    @DisplayName("A course is held in memory, kept while its row stands and dropped once it is gone")
    void coursesAreHeld() {
        IslandId erased = IslandId.of(UUID.randomUUID());
        service.start(course);
        service.start(erased);
        table.remove(erased);

        service.forget(course);
        service.forget(erased);

        assertThat(service.isCourse(course)).isTrue();
        assertThat(service.isCourse(erased)).isFalse();
        IslandId other = IslandId.of(UUID.randomUUID());
        table.add(other);
        assertThat(service.prime()).isEqualTo(2);
        assertThat(service.isCourse(other)).isTrue();
    }
}
