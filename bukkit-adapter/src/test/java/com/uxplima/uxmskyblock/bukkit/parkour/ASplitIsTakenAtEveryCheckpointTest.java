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
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffectPlayer;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffects;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.island.IslandAccessService;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.parkour.ParkourPort;
import com.uxplima.uxmskyblock.core.application.parkour.ParkourService;
import com.uxplima.uxmskyblock.core.application.parkour.ParkourSplitsPort;
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
 * Each checkpoint a run reaches says how long the run took to it, against the same checkpoint on the runner's best
 * run; a run that sets the runner's best keeps its splits in their place.
 */
class ASplitIsTakenAtEveryCheckpointTest extends MockBukkitHarness {

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
    private final Map<String, Map<ParkourSplitsPort.Checkpoint, Long>> kept = new HashMap<>();
    private int keeps;
    private boolean splitsDown;

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
                        "parkour-checkpoint",
                        com.uxplima.uxmlib.condition.action.ActionList.parse(
                                List.of("[console] probe checkpoint %time% %millis% %diff%")),
                        "parkour-finished",
                        com.uxplima.uxmlib.condition.action.ActionList.parse(
                                List.of("[console] probe finished %time% %millis%")),
                        "parkour-record",
                        com.uxplima.uxmlib.condition.action.ActionList.parse(
                                List.of("[console] probe record %time% %millis% %was%")))),
                new InteractionEffectPlayer(),
                clock);
        take(true);
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
        pad(7, Material.STONE, Material.HEAVY_WEIGHTED_PRESSURE_PLATE);
        pad(1000, Material.EMERALD_BLOCK, Material.LIGHT_WEIGHTED_PRESSURE_PLATE);
    }

    /** Takes splits, or not, from now on. */
    private void take(boolean enabled) {
        runs.splits(new ParkourSplits(
                new ParkourSplitsPort() {
                    @Override
                    public Map<ParkourSplitsPort.Checkpoint, Long> best(IslandId on, PlayerUuid who) {
                        if (splitsDown) {
                            throw new IllegalStateException("the database is gone");
                        }
                        return kept.getOrDefault(on + "/" + who, Map.of());
                    }

                    @Override
                    public void keep(IslandId on, PlayerUuid who, Map<ParkourSplitsPort.Checkpoint, Long> splits) {
                        if (splitsDown) {
                            throw new IllegalStateException("the database is gone");
                        }
                        keeps++;
                        kept.put(on + "/" + who, splits);
                    }
                },
                scheduler,
                enabled));
    }

    @Test
    @DisplayName(
            "The first run says its time at each checkpoint, once each, and its splits are kept with the first finish")
    void theFirstRunSaysItsTimes() {
        step(0);
        said();
        clock.advance(Duration.ofSeconds(1));
        step(5);
        assertThat(said()).contains("Checkpoint at 0:01.000.");
        clock.advance(Duration.ofSeconds(1));
        step(7);
        assertThat(said()).contains("Checkpoint at 0:02.000.");
        step(5);
        assertThat(said())
                .describedAs("reached again in the run, it says no time")
                .contains("Checkpoint.")
                .doesNotContain("0:0");
        clock.advance(Duration.ofSeconds(1));
        step(10);
        assertThat(kept.get(course.id() + "/" + PlayerUuid.of(runner.getUniqueId())))
                .isEqualTo(Map.of(checkpoint(5), 1000L, checkpoint(7), 2000L));
        assertThat(fired).containsSequence("checkpoint 0:01.000 1000", "checkpoint 0:02.000 2000", "checkpoint");
    }

    @Test
    @DisplayName("A later run says how far ahead of or behind the best it is, and only a new best keeps its splits")
    void aLaterRunWeighsItsSplits() {
        step(0);
        clock.advance(Duration.ofSeconds(1));
        step(5);
        clock.advance(Duration.ofSeconds(1));
        step(7);
        clock.advance(Duration.ofSeconds(1));
        step(10);
        said();
        fired.clear();

        step(0);
        clock.advance(Duration.ofMillis(800));
        step(5);
        assertThat(said()).contains("Checkpoint at 0:00.800").contains("0:00.200 ahead of your best");
        clock.advance(Duration.ofMillis(1500));
        step(7);
        assertThat(said()).contains("Checkpoint at 0:02.300").contains("0:00.300 behind your best");
        assertThat(fired).containsExactly("checkpoint 0:00.800 800 -0:00.200", "checkpoint 0:02.300 2300 +0:00.300");
        clock.advance(Duration.ofMillis(600));
        step(10);
        assertThat(kept.get(course.id() + "/" + PlayerUuid.of(runner.getUniqueId())))
                .describedAs("2.9s beats 3s")
                .isEqualTo(Map.of(checkpoint(5), 800L, checkpoint(7), 2300L));

        step(0);
        clock.advance(Duration.ofMillis(700));
        step(5);
        assertThat(said()).contains("0:00.100 ahead of your best");
        clock.advance(Duration.ofSeconds(9));
        step(10);
        assertThat(keeps).describedAs("a slower run keeps nothing").isEqualTo(2);
        assertThat(kept.get(course.id() + "/" + PlayerUuid.of(runner.getUniqueId())))
                .isEqualTo(Map.of(checkpoint(5), 800L, checkpoint(7), 2300L));
    }

    @Test
    @DisplayName("Standing on the start again starts the splits over, and a run that ends without a finish keeps none")
    void anEndedRunKeepsNothing() {
        step(0);
        clock.advance(Duration.ofSeconds(1));
        step(5);
        step(0);
        said();
        clock.advance(Duration.ofMillis(500));
        step(5);
        assertThat(said()).describedAs("the split is taken again").contains("Checkpoint at 0:00.500.");

        move(new Location(world, 500, Y + 1, 8.5));
        step(10);
        assertThat(keeps).isZero();
        // Kept elsewhere meanwhile, as by another server: the next run reads the best afresh.
        kept.put(course.id() + "/" + PlayerUuid.of(runner.getUniqueId()), Map.of(checkpoint(5), 400L));
        step(0);
        clock.advance(Duration.ofSeconds(1));
        step(5);
        assertThat(said()).contains("Checkpoint at 0:01.000").contains("0:00.600 behind your best");
        runs.onQuit(new org.bukkit.event.player.PlayerQuitEvent(
                runner, Component.empty(), org.bukkit.event.player.PlayerQuitEvent.QuitReason.DISCONNECTED));
        step(10);
        assertThat(keeps).isZero();
    }

    @Test
    @DisplayName("Off, a checkpoint says no time and nothing is kept; splits that cannot be read or kept change no run")
    void offOrFailing() {
        take(false);
        step(0);
        said();
        clock.advance(Duration.ofSeconds(1));
        step(5);
        assertThat(said()).contains("Checkpoint.").doesNotContain("0:01");
        clock.advance(Duration.ofSeconds(1));
        step(10);
        assertThat(keeps).isZero();

        take(true);
        splitsDown = true;
        step(0);
        said();
        clock.advance(Duration.ofSeconds(1));
        step(5);
        assertThat(said()).contains("Checkpoint at 0:01.000.");
        clock.advance(Duration.ofSeconds(1));
        step(10);
        assertThat(said()).contains("0:02.000");
        assertThat(keeps).isZero();
    }

    @Test
    @DisplayName("Splits are read from the file, on as shipped, and a difference is written with its sign")
    void readAndWritten() throws Exception {
        assertThat(ParkourConfiguration.load(
                                HoconConfigurationLoader.builder().buildAndLoadString(resource("modules/parkour.conf")))
                        .runs())
                .isEqualTo(ParkourConfiguration.Runs.SHIPPED);
        assertThat(ParkourConfiguration.Runs.SHIPPED.splits()).isTrue();
        assertThat(ParkourConfiguration.load(
                                HoconConfigurationLoader.builder().buildAndLoadString("runs { splits = false }"))
                        .runs()
                        .splits())
                .isFalse();
        assertThat(ParkourRuns.signed(-1250)).isEqualTo("-0:01.250");
        assertThat(ParkourRuns.signed(300)).isEqualTo("+0:00.300");
        assertThat(ParkourRuns.signed(0)).isEqualTo("-0:00.000");
    }

    private ParkourSplitsPort.Checkpoint checkpoint(int dx) {
        return new ParkourSplitsPort.Checkpoint(8 + dx, Y + 1, 8);
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
