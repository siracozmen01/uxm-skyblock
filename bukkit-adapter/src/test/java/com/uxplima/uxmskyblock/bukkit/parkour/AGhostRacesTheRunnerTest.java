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
import java.util.Optional;
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
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffectPlayer;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffects;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.island.IslandAccessService;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.parkour.GhostPath;
import com.uxplima.uxmskyblock.core.application.parkour.ParkourGhostsPort;
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
 * A runner's best run is recorded as it goes and kept as their ghost; the next run races it, an item only the runner
 * sees retracing the run on the runner's clock, gone once the run ends.
 */
class AGhostRacesTheRunnerTest extends MockBukkitHarness {

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
    private final Map<String, ParkourGhostsPort.Ghost> kept = new HashMap<>();
    private final List<Location> shownAt = new ArrayList<>();
    private final List<PlayerUuid> shownOf = new ArrayList<>();
    private final List<Location> moves = new ArrayList<>();
    private int removed;
    private int keeps;
    private boolean ghostsDown;
    private boolean stageRefuses;
    private Runnable tick = () -> {};
    private final List<Runnable> held = new ArrayList<>();
    private boolean hold;

    @SuppressWarnings("NullAway.Init")
    private SchedulerPort scheduler;

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
        scheduler = mock(SchedulerPort.class);
        doAnswer(call -> {
                    if (hold) {
                        held.add(call.getArgument(0, Runnable.class));
                    } else {
                        call.getArgument(0, Runnable.class).run();
                    }
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
        race(ParkourConfiguration.Ghost.SHIPPED);
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

    /** Races ghosts as {@code ghost} says, from now on. */
    private void race(ParkourConfiguration.Ghost ghost) {
        ParkourGhostsPort port = new ParkourGhostsPort() {
            @Override
            public void keep(IslandId on, PlayerUuid who, long millis, GhostPath path) {
                keeps++;
                kept.merge(
                        on + "/" + who,
                        new ParkourGhostsPort.Ghost(who, millis, path),
                        (was, now) -> now.millis() < was.millis() ? now : was);
            }

            @Override
            public Optional<ParkourGhostsPort.Ghost> of(IslandId on, PlayerUuid who) {
                down();
                return Optional.ofNullable(kept.get(on + "/" + who));
            }

            @Override
            public Optional<ParkourGhostsPort.Ghost> fastest(IslandId on) {
                down();
                return kept.entrySet().stream()
                        .filter(entry -> entry.getKey().startsWith(on + "/"))
                        .map(Map.Entry::getValue)
                        .min(Comparator.comparingLong(ParkourGhostsPort.Ghost::millis));
            }

            private void down() {
                if (ghostsDown) {
                    throw new IllegalStateException("the database is gone");
                }
            }
        };
        ParkourGhosts.Stage stage = (running, at, whose, ticking) -> {
            if (stageRefuses) {
                return Optional.empty();
            }
            shownAt.add(at);
            shownOf.add(whose);
            tick = ticking;
            return Optional.of(new ParkourGhosts.Shown() {
                @Override
                public void moveTo(Location to) {
                    moves.add(to);
                }

                @Override
                public void remove() {
                    removed++;
                }
            });
        };
        runs.ghosts(new ParkourGhosts(
                port,
                scheduler,
                ghost,
                Duration.ofMinutes(30),
                Messages.bundled(),
                new InteractionEffects(Map.of(
                        "parkour-ghost",
                        com.uxplima.uxmlib.condition.action.ActionList.parse(
                                List.of("[console] probe ghost %time% %millis%")))),
                new InteractionEffectPlayer(),
                clock,
                stage));
    }

    @Test
    @DisplayName("A best run is kept as a ghost, and the next run races it on the runner's clock")
    void aBestRunIsRaced() {
        step(0);
        assertThat(shownAt).describedAs("no ghost before anybody finished").isEmpty();
        clock.advance(Duration.ofSeconds(1));
        move(new Location(world, 30.5, Y + 1, 8.5));
        // Standing on the start again starts the recording over.
        step(0);
        clock.advance(Duration.ofSeconds(1));
        move(new Location(world, 10.5, Y + 1, 8.5, 90, 0));
        clock.advance(Duration.ofSeconds(1));
        step(10);
        assertThat(kept.get(course.id() + "/" + PlayerUuid.of(runner.getUniqueId())))
                .satisfies(ghost -> {
                    assertThat(ghost.millis()).isEqualTo(2000);
                    assertThat(ghost.path().poses())
                            .extracting(GhostPath.Pose::millis, GhostPath.Pose::x)
                            .containsExactly(
                                    org.assertj.core.groups.Tuple.tuple(0L, 0.0),
                                    org.assertj.core.groups.Tuple.tuple(1000L, 2.0),
                                    org.assertj.core.groups.Tuple.tuple(2000L, 10.0));
                });
        said();
        fired.clear();

        step(0);
        assertThat(shownAt).containsExactly(new Location(world, 8.5, Y + 1 + 1.4, 8.5, 0, 0));
        assertThat(shownOf).containsExactly(PlayerUuid.of(runner.getUniqueId()));
        assertThat(said()).contains("A ghost runs beside you").contains("0:02.000");
        assertThat(fired).containsExactly("ghost 0:02.000 2000");
        clock.advance(Duration.ofMillis(500));
        tick.run();
        assertThat(moves.getLast().getX()).isEqualTo(9.5);
        assertThat(moves.getLast().getY()).isEqualTo(Y + 1 + 1.4);
        step(0);
        tick.run();
        assertThat(moves.getLast().getX())
                .describedAs("standing on the plate starts the ghost over")
                .isEqualTo(8.5);
        assertThat(shownAt).describedAs("the same ghost, not a second").hasSize(1);
        clock.advance(Duration.ofSeconds(5));
        tick.run();
        assertThat(moves.getLast().getX())
                .describedAs("its run done, it stands at the finish")
                .isEqualTo(18.5);

        step(10);
        assertThat(removed).describedAs("the finish takes the ghost away").isEqualTo(1);
        assertThat(keeps).describedAs("a slower run is not kept").isEqualTo(1);
        assertThat(runs.runningOn(runner)).isEmpty();

        step(0);
        clock.advance(Duration.ofMillis(1500));
        step(10);
        assertThat(keeps).describedAs("a new best is kept").isEqualTo(2);
        assertThat(kept.get(course.id() + "/" + PlayerUuid.of(runner.getUniqueId())))
                .extracting(ParkourGhostsPort.Ghost::millis)
                .isEqualTo(1500L);
    }

    @Test
    @DisplayName("A ghost read for a run that ended before it came is not shown")
    void aLateGhostIsNotShown() {
        seed(PlayerUuid.of(UUID.randomUUID()), 4000);
        hold = true;
        step(0);
        quit();
        hold = false;
        held.forEach(Runnable::run);
        assertThat(held).hasSize(1);
        assertThat(shownAt).isEmpty();
    }

    @Test
    @DisplayName("Leaving the course, a run that takes too long and quitting each take the ghost away")
    void anEndedRunTakesTheGhost() {
        seed(PlayerUuid.of(UUID.randomUUID()), 4000);
        step(0);
        assertThat(shownAt).hasSize(1);
        move(new Location(world, 500, Y + 1, 8.5));
        assertThat(removed).isEqualTo(1);

        step(0);
        clock.advance(Duration.ofMinutes(31));
        move(new Location(world, 9.5, Y + 1, 8.5));
        assertThat(removed).isEqualTo(2);

        step(0);
        quit();
        assertThat(removed).isEqualTo(3);
        assertThat(shownAt).hasSize(3);
    }

    @Test
    @DisplayName("Against the record the fastest is raced, against their own the runner's best, and off nothing is")
    void whoseGhost() {
        PlayerUuid fastest = PlayerUuid.of(UUID.randomUUID());
        PlayerUuid self = PlayerUuid.of(runner.getUniqueId());
        seed(fastest, 1000);
        seed(self, 3000);

        step(0);
        assertThat(shownOf).containsExactly(fastest);
        quit();

        race(new ParkourConfiguration.Ghost(
                true,
                ParkourConfiguration.Ghost.Against.OWN,
                Duration.ofMillis(100),
                Material.PLAYER_HEAD,
                1.4,
                0.6f,
                true));
        step(0);
        assertThat(shownOf).containsExactly(fastest, self);
        quit();
        kept.remove(course.id() + "/" + self);
        race(new ParkourConfiguration.Ghost(
                true,
                ParkourConfiguration.Ghost.Against.OWN,
                Duration.ofMillis(100),
                Material.PLAYER_HEAD,
                1.4,
                0.6f,
                true));
        step(0);
        assertThat(shownOf).describedAs("before their own, the fastest").containsExactly(fastest, self, fastest);
        quit();

        race(new ParkourConfiguration.Ghost(
                false,
                ParkourConfiguration.Ghost.Against.RECORD,
                Duration.ofMillis(100),
                Material.PLAYER_HEAD,
                1.4,
                0.6f,
                true));
        step(0);
        clock.advance(Duration.ofMillis(500));
        step(10);
        assertThat(shownOf).describedAs("off").hasSize(3);
        assertThat(keeps).describedAs("off, nothing is kept").isZero();
    }

    @Test
    @DisplayName("A ghost that cannot be read or put down leaves the run as it was, and is not told")
    void aGhostThatFails() {
        seed(PlayerUuid.of(UUID.randomUUID()), 4000);
        ghostsDown = true;
        step(0);
        assertThat(shownAt).isEmpty();
        assertThat(said()).doesNotContain("ghost");
        quit();

        ghostsDown = false;
        stageRefuses = true;
        step(0);
        assertThat(said()).contains("The run is on").doesNotContain("ghost");
        clock.advance(Duration.ofSeconds(1));
        step(10);
        assertThat(said()).contains("0:01.000");
        assertThat(removed).isZero();
    }

    @Test
    @DisplayName("The ghost is read from the file, as shipped, and a ghost written wrong is the shipped one")
    void theGhostIsRead() throws Exception {
        assertThat(ParkourConfiguration.load(
                                HoconConfigurationLoader.builder().buildAndLoadString(resource("modules/parkour.conf")))
                        .ghost())
                .isEqualTo(ParkourConfiguration.Ghost.SHIPPED);
        assertThat(ParkourConfiguration.load(HoconConfigurationLoader.builder()
                                .buildAndLoadString("ghost { enabled = false, against = own, sample-every = 250ms,"
                                        + " item = ender_pearl, height = 0.5, scale = 2, glow = false }"))
                        .ghost())
                .isEqualTo(new ParkourConfiguration.Ghost(
                        false,
                        ParkourConfiguration.Ghost.Against.OWN,
                        Duration.ofMillis(250),
                        Material.ENDER_PEARL,
                        0.5,
                        2f,
                        false));
        for (String wrong : List.of(
                "against = nobody", "sample-every = 10ms", "item = nothing", "scale = 0", "scale = 5", "item = air")) {
            assertThat(ParkourConfiguration.load(
                                    HoconConfigurationLoader.builder().buildAndLoadString("ghost { " + wrong + " }"))
                            .ghost())
                    .describedAs(wrong)
                    .isEqualTo(ParkourConfiguration.Ghost.SHIPPED);
        }
    }

    private void quit() {
        runs.onQuit(new org.bukkit.event.player.PlayerQuitEvent(
                runner, Component.empty(), org.bukkit.event.player.PlayerQuitEvent.QuitReason.DISCONNECTED));
    }

    private void seed(PlayerUuid who, long millis) {
        GhostPath.Recorder recorder = new GhostPath.Recorder(1, 100);
        recorder.at(0, 0, 0, 0, 0);
        kept.put(
                course.id() + "/" + who, new ParkourGhostsPort.Ghost(who, millis, recorder.until(millis, 10, 0, 0, 0)));
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
