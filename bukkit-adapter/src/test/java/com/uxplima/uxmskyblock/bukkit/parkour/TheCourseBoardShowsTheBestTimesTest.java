package com.uxplima.uxmskyblock.bukkit.parkour;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.World;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import com.uxplima.uxmskyblock.bukkit.config.ParkourConfiguration;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.island.IslandAccessService;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.parkour.ParkourPort;
import com.uxplima.uxmskyblock.core.application.parkour.ParkourService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

/**
 * The course command shows the best times on the course the player stands on, fastest first and no
 * longer than the operator wrote, and says so when the player stands on no course or nobody has finished.
 */
class TheCourseBoardShowsTheBestTimesTest extends MockBukkitHarness {

    private static final int Y = 100;
    private static final UUID FAST = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID SLOW = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
    private static final UUID LAST = UUID.fromString("cccccccc-0000-0000-0000-000000000003");

    private final Set<IslandId> courses = new HashSet<>();
    private final List<ParkourPort.Best> bests = new ArrayList<>();
    private final List<Integer> asked = new ArrayList<>();

    @SuppressWarnings("NullAway.Init")
    private World world;

    @SuppressWarnings("NullAway.Init")
    private ParkourBoard board;

    @SuppressWarnings("NullAway.Init")
    private PlayerMock player;

    @SuppressWarnings("NullAway.Init")
    private Island course;

    @BeforeEach
    void setUpCourse() {
        world = server.addSimpleWorld("skyblock");
        course = island(0);
        Island empty = island(1000);
        Island plain = island(2000);
        IslandProtectionListener islands =
                new IslandProtectionListener(mock(IslandStoragePort.class), new IslandAccessService());
        islands.cacheIsland(course, "skyblock");
        islands.cacheIsland(empty, "skyblock");
        islands.cacheIsland(plain, "skyblock");
        ParkourService service = new ParkourService(new ParkourPort() {
            @Override
            public Set<IslandId> findAll() {
                return Set.copyOf(courses);
            }

            @Override
            public boolean exists(IslandId islandId) {
                return courses.contains(islandId);
            }

            @Override
            public void add(IslandId islandId) {
                courses.add(islandId);
            }

            @Override
            public OptionalLong best(IslandId islandId, PlayerUuid who) {
                return OptionalLong.empty();
            }

            @Override
            public void finish(IslandId islandId, PlayerUuid who, long millis) {
                throw new UnsupportedOperationException("the board writes nothing");
            }

            @Override
            public int clear(IslandId cleared, java.util.Optional<PlayerUuid> runner) {
                throw new UnsupportedOperationException("nothing here clears a course");
            }

            @Override
            public List<ParkourPort.Runs> mostRun(int limit) {
                return List.of();
            }

            @Override
            public List<ParkourPort.Best> top(IslandId islandId, int limit) {
                asked.add(limit);
                return islandId.equals(course.id()) ? bests.subList(0, Math.min(limit, bests.size())) : List.of();
            }
        });
        service.start(course.id());
        service.start(empty.id());
        SchedulerPort scheduler = mock(SchedulerPort.class);
        doAnswer(call -> {
                    call.getArgument(0, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .async(any(Runnable.class));
        doAnswer(call -> {
                    call.getArgument(1, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .onEntity(any(PlayerUuid.class), any(Runnable.class));
        board = new ParkourBoard(
                service,
                islands,
                scheduler,
                Messages.bundled(),
                2,
                uuid -> uuid.equals(FAST) ? "Swift" : uuid.equals(SLOW) ? "Steady" : null);
        player = createPlayer("Looker");
        bests.add(new ParkourPort.Best(PlayerUuid.of(FAST), 3_000, 2));
        bests.add(new ParkourPort.Best(PlayerUuid.of(LAST), 65_320, 1));
        bests.add(new ParkourPort.Best(PlayerUuid.of(SLOW), 90_000, 4));
    }

    @Test
    @DisplayName("On a course the board lists the fastest runners, as many as the operator wrote")
    void theBoardListsTheFastest() {
        standAt(8);
        board.show(player);

        String said = said();
        assertThat(said).contains("Best times here");
        assertThat(said).contains("#1 Swift 0:03.000 (2 runs)");
        assertThat(said)
                .describedAs("a runner the server never knew is shown by the start of their id")
                .contains("#2 cccccccc 1:05.320 (1 runs)");
        assertThat(said).doesNotContain("Steady");
        assertThat(asked).containsExactly(2);
    }

    @Test
    @DisplayName("A course nobody has finished, and a spot on no course, are said so")
    void anEmptyCourseAndNoCourse() {
        standAt(1008);
        board.show(player);
        assertThat(said()).contains("Nobody has finished this course yet");

        standAt(2008);
        board.show(player);
        assertThat(said()).contains("You are not on a Parkour course");
        assertThat(asked).describedAs("a spot on no course reads no times").containsExactly(2);
    }

    @Test
    @DisplayName(
            "A season's board lists its own fastest, the one under way or the one numbered, and says when there is none")
    void aSeasonsBoard() {
        com.uxplima.uxmskyblock.core.application.season.IslandSeasonStoragePort storage =
                mock(com.uxplima.uxmskyblock.core.application.season.IslandSeasonStoragePort.class);
        Instant now = Instant.now();
        com.uxplima.uxmskyblock.core.domain.season.SeasonRecord summer =
                new com.uxplima.uxmskyblock.core.domain.season.SeasonRecord(
                        com.uxplima.uxmskyblock.core.domain.season.SeasonId.of(2),
                        "Summer",
                        now.minusSeconds(60),
                        now.plusSeconds(600),
                        com.uxplima.uxmskyblock.core.domain.season.SeasonState.ACTIVE);
        com.uxplima.uxmskyblock.core.domain.season.SeasonRecord spring =
                new com.uxplima.uxmskyblock.core.domain.season.SeasonRecord(
                        com.uxplima.uxmskyblock.core.domain.season.SeasonId.of(1),
                        "Spring",
                        now.minusSeconds(6000),
                        now.minusSeconds(600),
                        com.uxplima.uxmskyblock.core.domain.season.SeasonState.COMPLETED);
        org.mockito.Mockito.when(storage.findSeason(com.uxplima.uxmskyblock.core.domain.season.SeasonId.of(1)))
                .thenReturn(java.util.Optional.of(spring));
        org.mockito.Mockito.when(storage.findSeason(com.uxplima.uxmskyblock.core.domain.season.SeasonId.of(9)))
                .thenReturn(java.util.Optional.empty());
        List<String> read = new ArrayList<>();
        board.seasons(new com.uxplima.uxmskyblock.core.application.parkour.ParkourSeasonService(
                new com.uxplima.uxmskyblock.core.application.parkour.ParkourSeasonsPort() {
                    @Override
                    public void finish(IslandId on, int season, PlayerUuid runner, long millis) {
                        throw new UnsupportedOperationException("the board writes nothing");
                    }

                    @Override
                    public List<ParkourPort.Best> top(IslandId on, int season, int limit) {
                        read.add(season + "x" + limit);
                        return season == 2 && on.equals(course.id())
                                ? List.of(new ParkourPort.Best(PlayerUuid.of(SLOW), 4_000, 3))
                                : List.of();
                    }

                    @Override
                    public int clear(IslandId on, java.util.Optional<PlayerUuid> runner) {
                        throw new UnsupportedOperationException("nothing here clears a course");
                    }
                },
                storage,
                java.time.Clock.systemUTC()));

        standAt(8);
        org.mockito.Mockito.when(storage.findActiveSeason()).thenReturn(java.util.Optional.empty());
        board.showSeason(player, java.util.OptionalInt.empty());
        assertThat(said()).contains("There is no season under way.");

        org.mockito.Mockito.when(storage.findActiveSeason()).thenReturn(java.util.Optional.of(summer));
        board.showSeason(player, java.util.OptionalInt.empty());
        assertThat(said())
                .contains("Summer: the best times")
                .doesNotContain("Season Summer")
                .contains("#1 Steady 0:04.000 (3 runs)");

        board.showSeason(player, java.util.OptionalInt.of(1));
        assertThat(said()).contains("Nobody finished this course in Spring yet.");

        board.showSeason(player, java.util.OptionalInt.of(9));
        assertThat(said()).contains("There is no season 9.");

        standAt(2008);
        board.showSeason(player, java.util.OptionalInt.empty());
        assertThat(said()).contains("You are not on a Parkour course");
        assertThat(read).describedAs("as many as the operator wrote").containsExactly("2x2", "1x2");
    }

    @Test
    @DisplayName("Without the season boards, a season's board is said to be none")
    void noSeasonBoards() {
        standAt(8);
        board.showSeason(player, java.util.OptionalInt.empty());
        assertThat(said()).contains("There is no season under way.");
    }

    @Test
    @DisplayName("The board's length is read from the file, and one out of bounds falls back to ten")
    void theBoardSizeIsRead() throws Exception {
        assertThat(sizeWritten("runs { board-size = 3 }")).isEqualTo(3);
        assertThat(sizeWritten("runs { board-size = 100 }")).isEqualTo(100);
        assertThat(sizeWritten("runs { board-size = 0 }")).isEqualTo(10);
        assertThat(sizeWritten("runs { board-size = 101 }")).isEqualTo(10);
        assertThat(ParkourConfiguration.defaultConfiguration().runs().boardSize())
                .isEqualTo(10);
    }

    private static int sizeWritten(String hocon) throws Exception {
        return ParkourConfiguration.load(HoconConfigurationLoader.builder().buildAndLoadString(hocon))
                .runs()
                .boardSize();
    }

    private void standAt(int x) {
        player.teleport(new Location(world, x + 0.5, Y, 8.5));
    }

    /** Everything the player was told since the last look, as plain text. */
    private String said() {
        StringBuilder all = new StringBuilder();
        Component line;
        while ((line = player.nextComponentMessage()) != null) {
            all.append(PlainTextComponentSerializer.plainText().serialize(line)).append('\n');
        }
        return all.toString();
    }

    private static Island island(int centreX) {
        return Island.create(
                IslandId.of(UUID.randomUUID()),
                IslandBounds.fromCenterAndRadius(centreX + 8, 8, 40),
                new PlayerUuid(UUID.randomUUID()),
                new ProfileId(UUID.randomUUID()),
                Instant.now());
    }
}
