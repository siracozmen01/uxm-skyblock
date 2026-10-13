package com.uxplima.uxmskyblock.bukkit.parkour;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
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
 * A run is run on foot: flying or gliding, a teleport that is not the course's own return, and moving faster than the
 * operator's most end it, unless the runner holds the bypass permission.
 */
class ARunIsRunOnFootTest extends MockBukkitHarness {

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
                        "parkour-run-voided",
                        com.uxplima.uxmlib.condition.action.ActionList.parse(List.of("[console] probe voided %check%")),
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
        watch(ParkourConfiguration.Checks.SHIPPED);
    }

    private void watch(ParkourConfiguration.Checks checks) {
        ParkourChecks watching = new ParkourChecks(runs, checks, clock);
        runs.checks(watching);
        this.checks = watching;
    }

    @SuppressWarnings("NullAway.Init")
    private ParkourChecks checks;

    @Test
    @DisplayName(
            "Faster than the most across the ground, over a quarter of a second, ends the run; the most itself and a fall do not")
    void speed() {
        fired.clear();
        step(0);
        said();
        move(at(9.5, Y + 1));
        clock.advance(Duration.ofMillis(250));
        move(at(13.5, Y + 1));
        clock.advance(Duration.ofMillis(250));
        move(at(13.5, Y + 1 - 6));
        assertThat(runs.runningOn(runner))
                .describedAs("16 a second, and a fall")
                .isPresent();

        clock.advance(Duration.ofMillis(100));
        move(at(40.5, Y + 1));
        assertThat(runs.runningOn(runner))
                .describedAs("measured over a quarter of a second at least")
                .isPresent();
        clock.advance(Duration.ofMillis(150));
        move(at(17.6, Y + 1));
        assertThat(runs.runningOn(runner))
                .describedAs("4.1 blocks in a quarter of a second")
                .isEmpty();
        assertThat(said()).contains("faster than anybody runs");
        assertThat(fired).containsExactly("voided speed");
    }

    @Test
    @DisplayName("Flying, taking off and opening wings each end the run")
    void flight() {
        step(0);
        runner.setAllowFlight(true);
        runner.setFlying(true);
        move(at(9.5, Y + 2));
        assertThat(runs.runningOn(runner)).isEmpty();
        assertThat(said()).contains("No flying on a run.");
        runner.setFlying(false);

        step(0);
        runner.setGliding(true);
        move(at(9.5, Y + 2));
        assertThat(runs.runningOn(runner)).describedAs("gliding").isEmpty();
        runner.setGliding(false);

        step(0);
        checks.onFlight(new org.bukkit.event.player.PlayerToggleFlightEvent(runner, false));
        assertThat(runs.runningOn(runner)).describedAs("landing").isPresent();
        checks.onFlight(new org.bukkit.event.player.PlayerToggleFlightEvent(runner, true));
        assertThat(runs.runningOn(runner)).isEmpty();

        step(0);
        checks.onGlide(new org.bukkit.event.entity.EntityToggleGlideEvent(runner, false));
        assertThat(runs.runningOn(runner)).isPresent();
        checks.onGlide(new org.bukkit.event.entity.EntityToggleGlideEvent(runner, true));
        assertThat(runs.runningOn(runner)).isEmpty();
    }

    @Test
    @DisplayName(
            "A teleport or a pearl ends the run, but the course's own return to the checkpoint after a fall does not")
    void teleport() {
        step(0);
        said();
        move(at(9.5, Y + 1 - 9));
        assertThat(sentBack).hasSize(1);
        teleported(sentBack.getFirst());
        assertThat(runs.runningOn(runner))
                .describedAs("the course's own return")
                .isPresent();

        teleported(at(12.5, Y + 1));
        assertThat(runs.runningOn(runner)).isEmpty();
        assertThat(said()).contains("moved off the course's path");

        teleported(at(14.5, Y + 1));
        assertThat(said()).describedAs("no run, nothing said").isEmpty();

        step(0);
        arrived(at(12.5, Y + 1), com.uxplima.uxmskyblock.bukkit.arrival.ArrivalCause.RESPAWN);
        arrived(at(12.5, Y + 1), com.uxplima.uxmskyblock.bukkit.arrival.ArrivalCause.JOIN);
        arrived(at(12.5, Y + 1), com.uxplima.uxmskyblock.bukkit.arrival.ArrivalCause.VEHICLE);
        assertThat(runs.runningOn(runner))
                .describedAs("only a teleport or a pearl")
                .isPresent();
        arrived(at(12.5, Y + 1), com.uxplima.uxmskyblock.bukkit.arrival.ArrivalCause.PEARL);
        assertThat(runs.runningOn(runner)).isEmpty();

        // A teleport elsewhere while a return is under way is no return.
        step(0);
        move(at(9.5, Y + 1 - 9));
        teleported(at(15.5, Y + 1));
        assertThat(runs.runningOn(runner)).isEmpty();

        // A return onto the start plate starts the run again before the arrival is seen: it is still the course's.
        step(0);
        move(at(9.5, Y + 1 - 9));
        step(0);
        teleported(sentBack.getLast());
        assertThat(runs.runningOn(runner))
                .describedAs("a return the restart came before")
                .isPresent();

        // A run that ended forgets its return: the next run's teleport onto that block is no return.
        move(at(9.5, Y + 1 - 9));
        runner.setAllowFlight(true);
        checks.onFlight(new org.bukkit.event.player.PlayerToggleFlightEvent(runner, true));
        step(0);
        teleported(sentBack.getLast());
        assertThat(runs.runningOn(runner))
                .describedAs("a return of an ended run")
                .isEmpty();
        step(0);

        // A return whose teleport never came in time is no return.
        move(at(9.5, Y + 1 - 9));
        clock.advance(ParkourChecks.RETURN_WINDOW.plusMillis(1));
        teleported(sentBack.getLast());
        assertThat(runs.runningOn(runner)).isEmpty();
    }

    @Test
    @DisplayName("The bypass permission and checks turned off end nothing")
    void exemptOrOff() {
        doReturn(true).when(runner).hasPermission("uxmskyblock.parkour.checks.bypass");
        step(0);
        teleported(at(12.5, Y + 1));
        checks.onFlight(new org.bukkit.event.player.PlayerToggleFlightEvent(runner, true));
        move(at(9.5, Y + 1));
        clock.advance(Duration.ofMillis(250));
        move(at(39.5, Y + 1));
        assertThat(runs.runningOn(runner)).isPresent();

        doReturn(false).when(runner).hasPermission("uxmskyblock.parkour.checks.bypass");
        watch(new ParkourConfiguration.Checks(false, false, false, 16, ""));
        step(0);
        runner.setAllowFlight(true);
        runner.setFlying(true);
        move(at(9.5, Y + 2));
        runner.setFlying(false);
        teleported(at(12.5, Y + 1));
        checks.onGlide(new org.bukkit.event.entity.EntityToggleGlideEvent(runner, true));
        move(at(9.5, Y + 1));
        clock.advance(Duration.ofMillis(250));
        move(at(39.5, Y + 1));
        assertThat(runs.runningOn(runner)).isPresent();
    }

    @Test
    @DisplayName(
            "The bypass permission is declared as nobody's, so an operator is checked; none is declared when empty")
    void anOperatorIsChecked() {
        checks.declare(server.getPluginManager());
        checks.declare(server.getPluginManager());
        assertThat(server.getPluginManager().getPermission("uxmskyblock.parkour.checks.bypass"))
                .isNotNull()
                .extracting(org.bukkit.permissions.Permission::getDefault)
                .isEqualTo(org.bukkit.permissions.PermissionDefault.FALSE);
        PlayerMock operator = createPlayer("Operator");
        operator.setOp(true);
        assertThat(operator.hasPermission("uxmskyblock.parkour.checks.bypass")).isFalse();

        int declared = server.getPluginManager().getPermissions().size();
        new ParkourChecks(runs, new ParkourConfiguration.Checks(true, true, true, 16, ""), clock)
                .declare(server.getPluginManager());
        assertThat(server.getPluginManager().getPermissions()).hasSize(declared);
    }

    @Test
    @DisplayName("The checks are read from the file, as shipped, and a most that is no speed is the shipped one")
    void theChecksAreRead() throws Exception {
        assertThat(ParkourConfiguration.load(
                                HoconConfigurationLoader.builder().buildAndLoadString(resource("modules/parkour.conf")))
                        .checks())
                .isEqualTo(ParkourConfiguration.Checks.SHIPPED);
        assertThat(ParkourConfiguration.load(HoconConfigurationLoader.builder().buildAndLoadString("""
                                        checks {
                                            flight = false
                                            teleport = false
                                            speed { enabled = false, fastest = 9.5 }
                                            bypass-permission = " my.node "
                                        }
                                        """))
                        .checks())
                .isEqualTo(new ParkourConfiguration.Checks(false, false, false, 9.5, "my.node"));
        assertThat(ParkourConfiguration.load(HoconConfigurationLoader.builder()
                                .buildAndLoadString("checks { speed { fastest = 0 } }"))
                        .checks())
                .isEqualTo(ParkourConfiguration.Checks.SHIPPED);
    }

    private Location at(double x, double y) {
        return new Location(world, x, y, 8.5);
    }

    private void teleported(Location to) {
        arrived(to, com.uxplima.uxmskyblock.bukkit.arrival.ArrivalCause.TELEPORT);
    }

    private void arrived(Location to, com.uxplima.uxmskyblock.bukkit.arrival.ArrivalCause cause) {
        checks.arrived(
                new com.uxplima.uxmskyblock.bukkit.arrival.Arrival(runner, runnerMock.getLocation(), to, cause, false));
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
