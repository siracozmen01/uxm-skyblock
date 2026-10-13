package com.uxplima.uxmskyblock.bukkit.parkour;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;

import com.uxplima.uxmskyblock.bukkit.arrival.Arrival;
import com.uxplima.uxmskyblock.bukkit.arrival.ArrivalCause;
import com.uxplima.uxmskyblock.bukkit.arrival.ArrivalObserver;
import com.uxplima.uxmskyblock.bukkit.config.ParkourConfiguration;

/**
 * What ends a run that is not run on foot: flying or gliding, being teleported, and moving faster than a runner can.
 * The course's own return to the last checkpoint is no teleport, and a player with the operator's bypass permission is
 * not checked.
 *
 * <p>A teleport is heard as an arrival, because Folia announces none: a {@code /tp}, a pearl and a plugin's move
 * reach the arrival watch either way.
 *
 * <p>Speed is measured across the ground over a quarter of a second at least, so a single long step the server reports
 * late does not end a run, and a fall is not counted.
 */
public final class ParkourChecks implements Listener, ArrivalObserver {

    /** The shortest stretch a speed is measured over. */
    static final Duration STRETCH = Duration.ofMillis(250);

    /**
     * How long the course's return to a checkpoint is expected. The arrival watch sees a teleport within a quarter of a
     * second; a return not seen by then never came.
     */
    static final Duration RETURN_WINDOW = Duration.ofSeconds(5);

    /** Where a runner was, and when, at the start of the stretch being measured. */
    private record Sample(Location at, Instant when) {}

    private final ParkourRuns runs;
    private final ParkourConfiguration.Checks config;
    private final Clock clock;
    private final Map<UUID, Sample> samples = new ConcurrentHashMap<>();
    private final Map<UUID, Sample> returning = new ConcurrentHashMap<>();

    public ParkourChecks(ParkourRuns runs, ParkourConfiguration.Checks config, Clock clock) {
        this.runs = Objects.requireNonNull(runs, "runs must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Declares the operator's bypass permission as one nobody holds unless given it. A permission no plugin declared is
     * held by every operator, and an operator testing a course is checked like anybody else.
     */
    public void declare(org.bukkit.plugin.PluginManager plugins) {
        String bypass = config.bypassPermission();
        if (!bypass.isEmpty() && plugins.getPermission(bypass) == null) {
            plugins.addPermission(new org.bukkit.permissions.Permission(
                    bypass,
                    "Runs a Parkour course without its checks",
                    org.bukkit.permissions.PermissionDefault.FALSE));
        }
    }

    /**
     * A run starts, or starts again: nothing measured before counts. A return under way stays expected, because a return
     * to the start plate starts the run again before the arrival is seen.
     */
    void started(UUID runner) {
        samples.remove(runner);
    }

    /** A run ended. */
    void ended(UUID runner) {
        samples.remove(runner);
        returning.remove(runner);
    }

    /** The course puts the runner back on {@code back}: that teleport is the course's own, and no stretch spans it. */
    void returning(UUID runner, Location back) {
        returning.put(runner, new Sample(back.clone(), clock.instant()));
        samples.remove(runner);
    }

    /** The check a runner's move to {@code to} broke, if any. On the runner's thread. */
    Optional<String> moved(Player runner, Location to) {
        if (exempt(runner)) {
            return Optional.empty();
        }
        if (config.flight() && (runner.isFlying() || runner.isGliding())) {
            return Optional.of("flight");
        }
        if (!config.speed()) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        Sample from = samples.get(runner.getUniqueId());
        if (from == null || !Objects.equals(from.at().getWorld(), to.getWorld())) {
            samples.put(runner.getUniqueId(), new Sample(to.clone(), now));
            return Optional.empty();
        }
        Duration took = Duration.between(from.when(), now);
        if (took.compareTo(STRETCH) < 0) {
            return Optional.empty();
        }
        samples.put(runner.getUniqueId(), new Sample(to.clone(), now));
        double across =
                Math.hypot(to.getX() - from.at().getX(), to.getZ() - from.at().getZ());
        double perSecond = across / (took.toMillis() / 1000.0);
        return perSecond > config.fastest() ? Optional.of("speed") : Optional.empty();
    }

    /** Any teleport or pearl but the course's own return ends the run. On the runner's thread. */
    @Override
    public void arrived(Arrival arrival) {
        Player runner = arrival.player();
        if (!config.teleports()
                || (arrival.cause() != ArrivalCause.TELEPORT && arrival.cause() != ArrivalCause.PEARL)
                || runs.runningOn(runner).isEmpty()) {
            return;
        }
        Sample back = returning.remove(runner.getUniqueId());
        boolean ours = back != null
                && sameBlock(back.at(), arrival.to())
                && Duration.between(back.when(), clock.instant()).compareTo(RETURN_WINDOW) <= 0;
        if (ours || exempt(runner)) {
            return;
        }
        runs.voidRun(runner, "teleport");
    }

    /** Taking off ends the run. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFlight(PlayerToggleFlightEvent event) {
        if (event.isFlying()) {
            stopped(event.getPlayer());
        }
    }

    /** Opening wings ends the run. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGlide(EntityToggleGlideEvent event) {
        if (event.isGliding() && event.getEntity() instanceof Player runner) {
            stopped(runner);
        }
    }

    private void stopped(Player runner) {
        if (config.flight() && runs.runningOn(runner).isPresent() && !exempt(runner)) {
            runs.voidRun(runner, "flight");
        }
    }

    private boolean exempt(Player runner) {
        return !config.bypassPermission().isEmpty() && runner.hasPermission(config.bypassPermission());
    }

    private static boolean sameBlock(Location a, Location b) {
        return a.getBlockX() == b.getBlockX()
                && a.getBlockY() == b.getBlockY()
                && a.getBlockZ() == b.getBlockZ()
                && Objects.equals(a.getWorld(), b.getWorld());
    }
}
