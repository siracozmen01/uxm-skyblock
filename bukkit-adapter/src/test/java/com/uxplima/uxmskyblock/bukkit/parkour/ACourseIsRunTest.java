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
import org.bukkit.event.player.PlayerMoveEvent;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import com.uxplima.uxmskyblock.bukkit.config.ParkourConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.PresetConfiguration;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffectPlayer;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffects;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.schematic.IslandStart;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.island.IslandAccessService;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.parkour.ParkourPort;
import com.uxplima.uxmskyblock.core.application.parkour.ParkourService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.dimension.DimensionId;
import com.uxplima.uxmskyblock.core.domain.gamemode.GameModeType;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.preset.StarterPreset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

/**
 * A run over a Parkour course starts on the start plate, is saved at checkpoints, is put back on the last
 * one after a fall, and ends on the finish plate, where its time is weighed against the runner's best.
 */
class ACourseIsRunTest extends MockBukkitHarness {

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
    @DisplayName("A run is timed from stepping off the start to the finish, and each finish is weighed")
    void aRunIsTimed() {
        fired.clear();
        List<String> started = new ArrayList<>();
        runs.whenStarted(who -> started.add(who.getName()));
        step(0);
        assertThat(started)
                .describedAs("the play mode is set the moment a run starts")
                .containsExactly("Runner");
        assertThat(runs.runningOn(runner)).hasValue(course.id());
        assertThat(said()).contains("The run is on");
        clock.advance(Duration.ofSeconds(1));
        step(0);
        assertThat(said())
                .describedAs("standing on the start is not told again at once")
                .isEmpty();

        clock.advance(Duration.ofSeconds(30));
        step(10);
        assertThat(runs.runningOn(runner)).isEmpty();
        assertThat(said()).contains("0:30.000").contains("first time");

        step(0);
        said();
        clock.advance(Duration.ofMillis(25_250));
        step(10);
        assertThat(said()).contains("new best").contains("0:25.250").contains("0:30.000");

        step(0);
        said();
        clock.advance(Duration.ofSeconds(40));
        step(10);
        assertThat(said()).contains("0:40.000").contains("0:25.250");
        assertThat(service.top(course.id(), 3).getFirst().runs()).isEqualTo(3);
        assertThat(fired)
                .describedAs("each finish says its time; a record says it again, and the time it beat")
                .containsExactly(
                        "finished 0:30.000 30000",
                        "record 0:30.000 30000 %was%",
                        "finished 0:25.250 25250",
                        "record 0:25.250 25250 0:30.000",
                        "finished 0:40.000 40000");
    }

    @Test
    @DisplayName("A fall below the last checkpoint puts the runner back on it, and the clock keeps running")
    void aFallGoesBack() {
        step(0);
        move(new Location(world, 2.5, Y + 1 - 8, 8.5));
        assertThat(sentBack)
                .describedAs("eight below the start is still on the course")
                .isEmpty();
        move(new Location(world, 2.5, Y + 1 - 8.5, 8.5));
        assertThat(sentBack)
                .singleElement()
                .satisfies(back -> assertThat(back.getBlockX()).isEqualTo(8));

        step(5);
        assertThat(said()).contains("Checkpoint");
        step(5);
        assertThat(said()).describedAs("the same checkpoint twice is one").isEmpty();
        move(new Location(world, 13.5, Y - 20, 8.5));
        assertThat(sentBack)
                .last()
                .satisfies(back -> assertThat(back.getBlockX()).isEqualTo(13));
        assertThat(runs.runningOn(runner)).hasValue(course.id());
    }

    @Test
    @DisplayName("Leaving the course or taking too long ends the run, and a finish without a run counts nothing")
    void aRunCanBeLost() {
        step(10);
        assertThat(said()).isEmpty();
        assertThat(bests).isEmpty();

        step(0);
        said();
        move(new Location(world, 1_008.5, Y + 1, 8.5));
        assertThat(said()).contains("left the course");
        assertThat(runs.runningOn(runner)).isEmpty();

        step(0);
        said();
        clock.advance(Duration.ofMinutes(31));
        move(new Location(world, 9.5, Y + 1, 8.5));
        assertThat(said()).contains("took too long");
        assertThat(runs.runningOn(runner)).isEmpty();
    }

    @Test
    @DisplayName("Plates on another mode's island, and anything but stepping on a plate, start nothing")
    void onlyTheCourseCounts() {
        step(1000);
        assertThat(runs.runningOn(runner)).isEmpty();

        Block plate = world.getBlockAt(8, Y + 1, 8);
        runs.onStep(new PlayerInteractEvent(runner, Action.RIGHT_CLICK_BLOCK, null, plate, BlockFace.UP));
        assertThat(runs.runningOn(runner)).isEmpty();
    }

    @Test
    @DisplayName("A new course has its start plate at the platform's edge and is a course")
    void aNewCourse() throws Exception {
        IslandId made = IslandId.of(UUID.randomUUID());
        SchedulerPort inline = mock(SchedulerPort.class);
        doAnswer(call -> {
                    call.getArgument(0, Runnable.class).run();
                    return null;
                })
                .when(inline)
                .async(any(Runnable.class));
        StarterPreset preset = shipped().catalogue().findById("parkour").orElseThrow();

        new CourseStart(service, inline, ParkourConfiguration.Markers.SHIPPED)
                .apply(new IslandStart(world, made, 5_000, Y, 5_000, preset));

        assertThat(world.getBlockAt(5_000 - 2, Y, 5_000).getType()).isEqualTo(Material.EMERALD_BLOCK);
        assertThat(world.getBlockAt(5_000 - 2, Y + 1, 5_000).getType())
                .isEqualTo(Material.LIGHT_WEIGHTED_PRESSURE_PLATE);
        assertThat(service.isCourse(made)).isTrue();
        assertThat(preset.mode()).isEqualTo(GameModeType.PARKOUR);
        assertThat(preset.start()).containsExactly(StarterPreset.PLATFORM, CourseStart.ACTION);
        assertThat(preset.dimensions().resolve(DimensionId.THE_NETHER)).isEmpty();
        assertThat(ParkourRuns.format(Duration.ofMillis(65_320))).isEqualTo("1:05.320");
    }

    @Test
    @DisplayName("The shipped file reads as the shipped markers, and a marker that is no block falls back")
    void theShippedFile() throws Exception {
        ParkourConfiguration file = ParkourConfiguration.load(
                HoconConfigurationLoader.builder().buildAndLoadString(resource("modules/parkour.conf")));
        ParkourConfiguration odd = ParkourConfiguration.load(HoconConfigurationLoader.builder()
                .buildAndLoadString(
                        "markers { plate = \"NOT_A_BLOCK\" }\nruns { fall-depth = 0, time-limit = \"2m\" }"));

        assertThat(file).isEqualTo(ParkourConfiguration.defaultConfiguration());
        assertThat(odd.markers()).isEqualTo(ParkourConfiguration.Markers.SHIPPED);
        assertThat(odd.runs()).isEqualTo(ParkourConfiguration.Runs.SHIPPED);
        assertThat(ParkourConfiguration.load(
                                HoconConfigurationLoader.builder().buildAndLoadString("runs { time-limit = \"2m\" }"))
                        .runs()
                        .timeLimit())
                .isEqualTo(Duration.ofMinutes(2));
    }

    private void pad(int dx, Material under, Material plate) {
        world.getBlockAt(8 + dx, Y, 8).setType(under);
        world.getBlockAt(8 + dx, Y + 1, 8).setType(plate);
    }

    private void step(int dx) {
        Block plate = world.getBlockAt(8 + dx, Y + 1, 8);
        runs.onStep(new PlayerInteractEvent(runner, Action.PHYSICAL, null, plate, BlockFace.SELF));
    }

    private void move(Location to) {
        runs.onMove(new PlayerMoveEvent(runner, runnerMock.getLocation(), to));
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

    private PresetConfiguration shipped() throws Exception {
        return PresetConfiguration.load(
                HoconConfigurationLoader.builder().buildAndLoadString(resource("modules/presets.conf")));
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
