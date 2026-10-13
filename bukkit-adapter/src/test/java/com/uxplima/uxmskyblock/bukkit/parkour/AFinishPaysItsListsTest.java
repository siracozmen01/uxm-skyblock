package com.uxplima.uxmskyblock.bukkit.parkour;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import com.uxplima.uxmskyblock.bukkit.config.ParkourConfiguration;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffectPlayer;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffects;
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
 * A finish pays the operator's lists: the first on a course, a new best of the runner's own, and a course record,
 * faster than anybody has run it.
 */
class AFinishPaysItsListsTest extends MockBukkitHarness {

    private static final int Y = 100;

    private final Set<IslandId> courses = new HashSet<>();
    private final Map<String, ParkourPort.Best> bests = new HashMap<>();
    private final MovableClock clock = new MovableClock(Instant.parse("2026-09-29T12:00:00Z"));
    private final List<Location> sentBack = new ArrayList<>();

    @SuppressWarnings("NullAway.Init")
    private World world;

    @SuppressWarnings("NullAway.Init")
    private ParkourRuns runs;

    @SuppressWarnings("NullAway.Init")
    private ParkourService service;

    @SuppressWarnings("NullAway.Init")
    private Player runner;

    @SuppressWarnings("NullAway.Init")
    private PlayerMock runnerMock;

    @SuppressWarnings("NullAway.Init")
    private Island course;

    private final List<String> fired = new ArrayList<>();
    private boolean recordsDown;

    @BeforeEach
    void setUpCourse() {
        world = server.addSimpleWorld("skyblock");
        course = island(0);
        Island plain = island(1000);
        IslandProtectionListener islands =
                new IslandProtectionListener(mock(IslandStoragePort.class), new IslandAccessService());
        islands.cacheIsland(course, "skyblock");
        islands.cacheIsland(plain, "skyblock");
        service = new ParkourService(new ParkourPort() {
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
                ParkourPort.Best best = bests.get(islandId + "/" + who);
                return best == null ? OptionalLong.empty() : OptionalLong.of(best.millis());
            }

            @Override
            public void finish(IslandId islandId, PlayerUuid who, long millis) {
                bests.merge(
                        islandId + "/" + who,
                        new ParkourPort.Best(who, millis, 1),
                        (was, now) -> new ParkourPort.Best(who, Math.min(was.millis(), millis), was.runs() + 1));
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
                if (recordsDown) {
                    throw new IllegalStateException("the database is gone");
                }
                List<ParkourPort.Best> all = new ArrayList<>(bests.values());
                all.sort(Comparator.comparingLong(ParkourPort.Best::millis));
                return all;
            }
        });
        service.start(course.id());
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
        server.getCommandMap().register("probe", new org.bukkit.command.Command("probe") {
            @Override
            public boolean execute(org.bukkit.command.CommandSender sender, String label, String[] args) {
                fired.add(String.join(" ", args));
                return true;
            }
        });
        runs = new ParkourRuns(
                service,
                islands,
                scheduler,
                ParkourConfiguration.defaultConfiguration(),
                Messages.bundled(),
                new InteractionEffects(Map.of(
                        "parkour-finished",
                        com.uxplima.uxmlib.condition.action.ActionList.parse(
                                List.of("[console] probe finished %time% %millis%")),
                        "parkour-record",
                        com.uxplima.uxmlib.condition.action.ActionList.parse(
                                List.of("[console] probe record %time% %millis% %was%")))),
                new InteractionEffectPlayer(),
                clock);
        runs.rewards(new InteractionEffects(Map.of(
                "first-finish",
                com.uxplima.uxmlib.condition.action.ActionList.parse(
                        List.of("[console] probe first %player_name% %time% %millis% %was%")),
                "personal-best",
                com.uxplima.uxmlib.condition.action.ActionList.parse(
                        List.of("[console] probe best %player_name% %time% %millis% %was%")),
                "course-record",
                com.uxplima.uxmlib.condition.action.ActionList.parse(
                        List.of("[console] probe course %player_name% %time% %millis% %was%")))));
        runnerMock = createPlayer("Runner");
        runnerMock.teleport(new Location(world, 8.5, Y + 1, 8.5));
        runner = spy(runnerMock);
        doAnswer(call -> {
                    sentBack.add(call.getArgument(0));
                    return CompletableFuture.completedFuture(true);
                })
                .when(runner)
                .teleportAsync(any(Location.class));
        pad(0, Material.EMERALD_BLOCK, Material.LIGHT_WEIGHTED_PRESSURE_PLATE);
        pad(5, Material.STONE, Material.HEAVY_WEIGHTED_PRESSURE_PLATE);
        pad(10, Material.GOLD_BLOCK, Material.LIGHT_WEIGHTED_PRESSURE_PLATE);
        pad(1000, Material.EMERALD_BLOCK, Material.LIGHT_WEIGHTED_PRESSURE_PLATE);
    }

    @Test
    @DisplayName("A first finish, a new best and a course record each pay their list, once, with the time they beat")
    void eachFinishPays() {
        fired.clear();
        run(runner, 30_000);
        assertThat(paid())
                .describedAs("the first finish on a course is its record too")
                .containsExactly("first Runner 0:30.000 30000", "course Runner 0:30.000 30000");
        assertThat(said()).contains("A course record").contains("0:30.000");

        fired.clear();
        run(runner, 25_000);
        assertThat(paid())
                .containsExactly("best Runner 0:25.000 25000 0:30.000", "course Runner 0:25.000 25000 0:30.000");

        fired.clear();
        said();
        run(runner, 40_000);
        assertThat(paid()).describedAs("a slower run pays nothing").isEmpty();
        assertThat(said()).doesNotContain("course record");

        PlayerMock second = createPlayer("Second");
        second.teleport(new Location(world, 8.5, Y + 1, 8.5));
        run(second, 35_000);
        assertThat(paid())
                .describedAs("another runner's first finish, slower than the record")
                .containsExactly("first Second 0:35.000 35000");
        fired.clear();
        run(second, 25_000);
        assertThat(paid())
                .describedAs("as fast as the record is no record")
                .containsExactly("best Second 0:25.000 25000 0:35.000");
        fired.clear();
        run(second, 20_000);
        assertThat(paid())
                .containsExactly("best Second 0:20.000 20000 0:25.000", "course Second 0:20.000 20000 0:25.000");
    }

    @Test
    @DisplayName("A finish that cannot be counted pays nothing")
    void aFinishNotCountedPaysNothing() {
        fired.clear();
        recordsDown = true;
        run(runner, 30_000);
        assertThat(paid()).isEmpty();
        assertThat(said()).contains("You finished in").contains("0:30.000").doesNotContain("course record");
    }

    @Test
    @DisplayName("The lists are read from the file, empty as shipped")
    void theListsAreRead() throws Exception {
        assertThat(ParkourConfiguration.load(
                                HoconConfigurationLoader.builder().buildAndLoadString(resource("modules/parkour.conf")))
                        .rewards())
                .isEqualTo(ParkourConfiguration.Rewards.SHIPPED);
        assertThat(ParkourConfiguration.load(HoconConfigurationLoader.builder()
                                .buildAndLoadString("rewards { first-finish = [\"[console] a\"],"
                                        + " personal-best = [\"[console] b\", \"[console] c\"],"
                                        + " course-record = [\"[console] d\"] }"))
                        .rewards())
                .isEqualTo(new ParkourConfiguration.Rewards(
                        List.of("[console] a"), List.of("[console] b", "[console] c"), List.of("[console] d")));
    }

    /** What the reward lists fired, leaving the effects. */
    private List<String> paid() {
        return fired.stream()
                .filter(line -> line.startsWith("first ") || line.startsWith("best ") || line.startsWith("course "))
                .toList();
    }

    /** {@code who} runs from the start to the finish in {@code millis}. */
    private void run(Player who, long millis) {
        Block start = world.getBlockAt(8, Y + 1, 8);
        Block finish = world.getBlockAt(18, Y + 1, 8);
        runs.onStep(new PlayerInteractEvent(who, Action.PHYSICAL, null, start, BlockFace.SELF));
        clock.advance(Duration.ofMillis(millis));
        runs.onStep(new PlayerInteractEvent(who, Action.PHYSICAL, null, finish, BlockFace.SELF));
    }

    private void pad(int dx, Material under, Material plate) {
        world.getBlockAt(8 + dx, Y, 8).setType(under);
        world.getBlockAt(8 + dx, Y + 1, 8).setType(plate);
    }

    /** Everything the runner was told since the last look, as plain text. */
    private String said() {
        StringBuilder all = new StringBuilder();
        Component line;
        while ((line = runnerMock.nextComponentMessage()) != null) {
            all.append(PlainTextComponentSerializer.plainText().serialize(line)).append('\n');
        }
        return all.toString();
    }

    private String resource(String path) throws Exception {
        try (InputStream in = Objects.requireNonNull(getClass().getClassLoader().getResourceAsStream(path))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Island island(int centreX) {
        return Island.create(
                IslandId.of(UUID.randomUUID()),
                IslandBounds.fromCenterAndRadius(centreX + 8, 8, 40),
                new PlayerUuid(UUID.randomUUID()),
                new ProfileId(UUID.randomUUID()),
                Instant.now());
    }

    /** A clock the test moves on by hand. */
    private static final class MovableClock extends Clock {
        private Instant now;

        MovableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
