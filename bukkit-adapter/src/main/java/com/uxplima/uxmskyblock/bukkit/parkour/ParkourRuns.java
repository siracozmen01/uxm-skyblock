package com.uxplima.uxmskyblock.bukkit.parkour;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import com.uxplima.uxmskyblock.bukkit.config.ParkourConfiguration;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffectPlayer;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffects;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.core.application.parkour.ParkourPort;
import com.uxplima.uxmskyblock.core.application.parkour.ParkourSeasonService;
import com.uxplima.uxmskyblock.core.application.parkour.ParkourService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.island.Island;

/**
 * Runs over a Parkour course: a run starts on the start plate, is saved at every checkpoint, and ends on
 * the finish plate, where its time is weighed against the runner's best. A runner who falls too far
 * below the last checkpoint is put back on it; one who leaves the course or takes too long loses the run.
 *
 * <p>Every event here runs on the thread that owns the runner. The best time is read and written on
 * the scheduler, and the answer comes back to the runner's thread.
 */
public final class ParkourRuns implements Listener {

    private static final Logger LOGGER = Logger.getLogger(ParkourRuns.class.getName());

    /** The reward lists of parkour.conf, which are no lines of effects.conf. */
    public static final String FIRST_FINISH = "first-finish";

    public static final String PERSONAL_BEST = "personal-best";

    public static final String COURSE_RECORD = "course-record";

    public static final String SEASON_RECORD = "season-record";

    /** How long a runner standing on the start plate goes before being told again that a run began. */
    private static final Duration TOLD_AGAIN = Duration.ofSeconds(3);

    /** One run under way. */
    record Run(IslandId course, Instant started, Location checkpoint, Instant told) {}

    private final ParkourService service;
    private final IslandProtectionListener islands;
    private final SchedulerPort scheduler;
    private final ParkourConfiguration config;
    private final Messages messages;
    private final InteractionEffects effects;
    private final InteractionEffectPlayer effectPlayer;
    private final Clock clock;
    private final Map<UUID, Run> runs = new ConcurrentHashMap<>();
    private volatile java.util.function.Consumer<Player> whenStarted = runner -> {};
    private volatile @org.jspecify.annotations.Nullable ParkourGhosts ghosts;
    private volatile @org.jspecify.annotations.Nullable ParkourSplits splits;
    private volatile @org.jspecify.annotations.Nullable ParkourChecks checks;
    private volatile @org.jspecify.annotations.Nullable ParkourSeasonService seasons;
    private volatile InteractionEffects rewards = InteractionEffects.none();
    private volatile com.uxplima.uxmskyblock.core.application.parkour.@org.jspecify.annotations.Nullable ParkourRewardsPort
            ledger;
    private volatile ParkourConfiguration.Rewards terms = ParkourConfiguration.Rewards.SHIPPED;

    public ParkourRuns(
            ParkourService service,
            IslandProtectionListener islands,
            SchedulerPort scheduler,
            ParkourConfiguration config,
            Messages messages,
            InteractionEffects effects,
            InteractionEffectPlayer effectPlayer,
            Clock clock) {
        this.service = Objects.requireNonNull(service, "service must not be null");
        this.islands = Objects.requireNonNull(islands, "islands must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
        this.effects = Objects.requireNonNull(effects, "effects must not be null");
        this.effectPlayer = Objects.requireNonNull(effectPlayer, "effectPlayer must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /** Tells {@code listener} each time a run starts, on the runner's thread. */
    public void whenStarted(java.util.function.Consumer<Player> listener) {
        this.whenStarted = Objects.requireNonNull(listener, "listener must not be null");
    }

    /** Records each run and shows the ghost it races. */
    public void ghosts(ParkourGhosts racing) {
        this.ghosts = Objects.requireNonNull(racing, "racing must not be null");
    }

    /**
     * Pays a finish from {@code paid}: first-finish, personal-best and course-record, each claimed in {@code ledger}
     * so it is paid once in its period, as {@code written} says.
     */
    public void rewards(
            InteractionEffects paid,
            com.uxplima.uxmskyblock.core.application.parkour.ParkourRewardsPort ledger,
            ParkourConfiguration.Rewards written) {
        this.rewards = Objects.requireNonNull(paid, "paid must not be null");
        this.ledger = Objects.requireNonNull(ledger, "ledger must not be null");
        this.terms = Objects.requireNonNull(written, "written must not be null");
    }

    /** Counts each finish in the season under way, which keeps its own record. */
    public void seasons(ParkourSeasonService counting) {
        this.seasons = Objects.requireNonNull(counting, "counting must not be null");
    }

    /** Ends a run that is not run on foot. */
    public void checks(ParkourChecks watching) {
        this.checks = Objects.requireNonNull(watching, "watching must not be null");
    }

    /** Takes each run's split times at its checkpoints, against the runner's best. */
    public void splits(ParkourSplits taking) {
        this.splits = Objects.requireNonNull(taking, "taking must not be null");
    }

    /** The run the player has under way, for the commands and the tests. */
    public Optional<IslandId> runningOn(Player player) {
        return Optional.ofNullable(runs.get(player.getUniqueId())).map(Run::course);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onStep(PlayerInteractEvent event) {
        Block plate = event.getClickedBlock();
        if (event.getAction() != Action.PHYSICAL || plate == null) {
            return;
        }
        Material type = plate.getType();
        ParkourConfiguration.Markers markers = config.markers();
        if (type != markers.plate() && type != markers.checkpoint()) {
            return;
        }
        Optional<Island> island = islands.findIslandAt(plate.getLocation());
        if (island.isEmpty() || !service.isCourse(island.get().id())) {
            return;
        }
        Player runner = event.getPlayer();
        IslandId course = island.get().id();
        // A new location, never the block's own: moving that one would move the block.
        Location standing = new Location(plate.getWorld(), plate.getX() + 0.5, plate.getY(), plate.getZ() + 0.5);
        Material under = plate.getRelative(0, -1, 0).getType();
        if (type == markers.checkpoint()) {
            checkpoint(runner, course, standing);
        } else if (under == markers.startUnder()) {
            start(runner, course, standing);
        } else if (under == markers.finishUnder()) {
            // The course's own team builds it, so it earns nothing there.
            boolean team = island.get().members().values().stream()
                    .anyMatch(member -> member.playerUuid().equals(PlayerUuid.of(runner.getUniqueId())));
            finish(runner, course, standing, team);
        }
    }

    private void start(Player runner, IslandId course, Location plate) {
        Instant now = clock.instant();
        Run was = runs.get(runner.getUniqueId());
        boolean tell = was == null
                || !was.course().equals(course)
                || Duration.between(was.told(), now).compareTo(TOLD_AGAIN) >= 0;
        // Standing on the start plate starts the run again, so the clock runs from the moment the
        // runner steps off it.
        runs.put(runner.getUniqueId(), new Run(course, now, plate, tell || was == null ? now : was.told()));
        ParkourGhosts racing = ghosts;
        if (racing != null) {
            racing.started(runner, course, plate, now);
        }
        ParkourSplits taking = splits;
        if (taking != null) {
            taking.started(runner.getUniqueId(), course);
        }
        ParkourChecks watching = checks;
        if (watching != null) {
            watching.started(runner.getUniqueId());
        }
        whenStarted.accept(runner);
        if (tell) {
            messages.send(runner, "parkour.started");
            effectPlayer.fire(effects, "parkour-run-started", runner);
        }
    }

    private void checkpoint(Player runner, IslandId course, Location plate) {
        Run run = runs.get(runner.getUniqueId());
        if (run == null || !run.course().equals(course) || sameBlock(run.checkpoint(), plate)) {
            return;
        }
        runs.put(runner.getUniqueId(), new Run(course, run.started(), plate, run.told()));
        ParkourSplits taking = splits;
        Optional<ParkourSplits.Split> split = taking == null
                ? Optional.empty()
                : taking.reached(
                        runner.getUniqueId(),
                        plate,
                        Duration.between(run.started(), clock.instant()).toMillis());
        if (split.isEmpty()) {
            messages.send(runner, "parkour.checkpoint");
            effectPlayer.fire(effects, "parkour-checkpoint", runner, Map.of("time", "", "millis", "", "diff", ""));
            return;
        }
        Duration time = Duration.ofMillis(split.get().millis());
        Long best = split.get().best();
        String diff = best == null ? "" : signed(split.get().millis() - best);
        if (best == null) {
            messages.send(runner, "parkour.split", timed("time", time));
        } else {
            messages.send(
                    runner,
                    split.get().millis() <= best ? "parkour.split_ahead" : "parkour.split_behind",
                    timed("time", time),
                    timed("diff", Duration.ofMillis(Math.abs(split.get().millis() - best))));
        }
        effectPlayer.fire(
                effects,
                "parkour-checkpoint",
                runner,
                Map.of("time", format(time), "millis", Long.toString(time.toMillis()), "diff", diff));
    }

    /** A difference of times with its sign, as an effect writes it: -0:01.250 ahead, +0:00.300 behind. */
    static String signed(long millis) {
        return (millis > 0 ? "+" : "-") + format(Duration.ofMillis(Math.abs(millis)));
    }

    private void finish(Player runner, IslandId course, Location plate, boolean team) {
        Run run = runs.get(runner.getUniqueId());
        if (run == null || !run.course().equals(course)) {
            return;
        }
        runs.remove(runner.getUniqueId());
        Duration time = Duration.between(run.started(), clock.instant());
        PlayerUuid who = PlayerUuid.of(runner.getUniqueId());
        ParkourGhosts racing = ghosts;
        com.uxplima.uxmskyblock.core.application.parkour.GhostPath path =
                racing == null ? null : racing.finished(runner, time, plate);
        ParkourSplits taking = splits;
        Optional<Map<com.uxplima.uxmskyblock.core.application.parkour.ParkourSplitsPort.Checkpoint, Long>> taken =
                taking == null ? Optional.empty() : taking.finished(runner.getUniqueId());
        effectPlayer.fire(effects, "parkour-finished", runner, said(time));
        scheduler.async(() -> {
            ParkourService.Finish finish;
            Optional<ParkourPort.Best> before;
            Optional<ParkourPort.Best> after;
            try {
                before = service.leader(course);
                finish = service.finish(course, who, time);
                after = service.leader(course);
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, e, () -> "A run on course " + course + " could not be counted.");
                scheduler.onEntity(who, () -> messages.send(runner, "parkour.finished", timed("time", time)));
                return;
            }
            boolean best =
                    finish instanceof ParkourService.Finish.First || finish instanceof ParkourService.Finish.Beaten;
            if (taking != null && best && taken.isPresent()) {
                taking.keep(course, who, taken.get());
            }
            if (racing != null && path != null && best) {
                racing.keep(course, who, time, path);
            }
            // A record is a time below the course's best before it, that still leads once it is counted: of two
            // finishes at once, the slower is no record.
            boolean courseRecord =
                    after.filter(leader -> leader.runner().equals(who) && leader.millis() == time.toMillis())
                                    .isPresent()
                            && before.map(was -> time.toMillis() < was.millis()).orElse(true);
            String recordWas =
                    before.map(was -> format(Duration.ofMillis(was.millis()))).orElse("");
            Optional<ParkourSeasonService.Counted> season = counted(course, who, time);
            boolean seasonRecord =
                    season.filter(ParkourSeasonService.Counted::record).isPresent();
            String seasonWas = season.flatMap(ParkourSeasonService.Counted::was)
                    .map(was -> format(Duration.ofMillis(was)))
                    .orElse("");
            List<String> lists = team ? List.of() : claimed(course, who, finish, courseRecord, seasonRecord, time);
            scheduler.onEntity(who, () -> {
                told(runner, finish);
                if (courseRecord) {
                    messages.send(runner, "parkour.course_record", timed("time", time));
                }
                if (seasonRecord) {
                    messages.send(
                            runner,
                            "parkour.season.record",
                            timed("time", time),
                            Placeholder.unparsed("season", season.get().season().name()));
                }
                paid(runner, finish, lists, recordWas, seasonWas);
            });
        });
    }

    /**
     * Pays the finish: the first on the course, a new best of the runner's own, and a course record, each its list.
     * {@code recordWas} is the course's best this run beat, empty when there was none.
     */
    private void paid(
            Player runner, ParkourService.Finish finish, List<String> lists, String recordWas, String seasonWas) {
        Map<String, String> says = said(finish.time());
        for (String list : lists) {
            String was =
                    switch (list) {
                        case PERSONAL_BEST ->
                            finish instanceof ParkourService.Finish.Beaten beaten ? format(beaten.was()) : "";
                        case COURSE_RECORD -> recordWas;
                        case SEASON_RECORD -> seasonWas;
                        default -> "";
                    };
            effectPlayer.fire(rewards, list, runner, with(says, was));
        }
    }

    /**
     * The lists this finish earns and has claimed: a first finish once for good, a new best and a course record once
     * in their period, none for a run quicker than the shortest. Off the main thread.
     */
    private List<String> claimed(
            IslandId course,
            PlayerUuid who,
            ParkourService.Finish finish,
            boolean courseRecord,
            boolean seasonRecord,
            Duration time) {
        com.uxplima.uxmskyblock.core.application.parkour.ParkourRewardsPort paidIn = ledger;
        ParkourConfiguration.Rewards written = terms;
        if (paidIn == null || time.compareTo(written.shortestRun()) < 0) {
            return List.of();
        }
        long now = clock.millis();
        long again = now - written.againAfter().toMillis();
        List<String> lists = new ArrayList<>();
        if (finish instanceof ParkourService.Finish.First) {
            claim(paidIn, course, who, FIRST_FINISH, now, Long.MIN_VALUE, lists);
        }
        if (finish instanceof ParkourService.Finish.Beaten) {
            claim(paidIn, course, who, PERSONAL_BEST, now, again, lists);
        }
        if (courseRecord) {
            claim(paidIn, course, who, COURSE_RECORD, now, again, lists);
        }
        if (seasonRecord) {
            claim(paidIn, course, who, SEASON_RECORD, now, again, lists);
        }
        return List.copyOf(lists);
    }

    /** The finish counted in the season under way, if one is and it could be counted. Off the main thread. */
    private Optional<ParkourSeasonService.Counted> counted(IslandId course, PlayerUuid who, Duration time) {
        ParkourSeasonService counting = seasons;
        if (counting == null) {
            return Optional.empty();
        }
        try {
            return counting.count(course, who, time);
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, e, () -> "A run on course " + course + " could not be counted in its season.");
            return Optional.empty();
        }
    }

    private static void claim(
            com.uxplima.uxmskyblock.core.application.parkour.ParkourRewardsPort paidIn,
            IslandId course,
            PlayerUuid who,
            String list,
            long now,
            long paidBefore,
            List<String> lists) {
        try {
            if (paidIn.claim(course, who, list, now, paidBefore)) {
                lists.add(list);
            }
        } catch (RuntimeException e) {
            LOGGER.log(
                    Level.WARNING, e, () -> "The " + list + " rewards on course " + course + " could not be claimed.");
        }
    }

    private static Map<String, String> with(Map<String, String> says, String was) {
        Map<String, String> all = new java.util.HashMap<>(says);
        all.put("was", was);
        return Map.copyOf(all);
    }

    private void told(Player runner, ParkourService.Finish finish) {
        switch (finish) {
            case ParkourService.Finish.First first -> {
                messages.send(runner, "parkour.first", timed("time", first.time()));
                effectPlayer.fire(effects, "parkour-record", runner, said(first.time()));
            }
            case ParkourService.Finish.Beaten beaten -> {
                messages.send(runner, "parkour.beaten", timed("time", beaten.time()), timed("was", beaten.was()));
                effectPlayer.fire(
                        effects,
                        "parkour-record",
                        runner,
                        Map.of(
                                "time", format(beaten.time()),
                                "millis", Long.toString(beaten.time().toMillis()),
                                "was", format(beaten.was())));
            }
            case ParkourService.Finish.Slower slower ->
                messages.send(runner, "parkour.slower", timed("time", slower.time()), timed("best", slower.best()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player runner = event.getPlayer();
        Run run = runs.get(runner.getUniqueId());
        if (run == null) {
            return;
        }
        if (Duration.between(run.started(), clock.instant())
                        .compareTo(config.runs().timeLimit())
                > 0) {
            runs.remove(runner.getUniqueId());
            ended(runner.getUniqueId());
            messages.send(runner, "parkour.timed_out");
            return;
        }
        Location to = event.getTo();
        Optional<Island> island = islands.findIslandAt(to);
        if (island.isEmpty() || !island.get().id().equals(run.course())) {
            runs.remove(runner.getUniqueId());
            ended(runner.getUniqueId());
            messages.send(runner, "parkour.left");
            return;
        }
        ParkourChecks watching = checks;
        Optional<String> broken = watching == null ? Optional.empty() : watching.moved(runner, to);
        if (broken.isPresent()) {
            voidRun(runner, broken.get());
            return;
        }
        ParkourGhosts racing = ghosts;
        if (racing != null) {
            racing.moved(runner, to);
        }
        if (to.getY() < run.checkpoint().getY() - config.runs().fallDepth()) {
            Location back = run.checkpoint().clone();
            back.setYaw(to.getYaw());
            back.setPitch(to.getPitch());
            runner.setFallDistance(0);
            if (watching != null) {
                watching.returning(runner.getUniqueId(), back);
            }
            var unused = runner.teleportAsync(back);
            messages.send(runner, "parkour.returned");
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        runs.remove(event.getPlayer().getUniqueId());
        ended(event.getPlayer().getUniqueId());
    }

    /** The run broke the check named {@code check}: it ends, and the runner is told why. On the runner's thread. */
    void voidRun(Player runner, String check) {
        if (runs.remove(runner.getUniqueId()) == null) {
            return;
        }
        ended(runner.getUniqueId());
        messages.send(runner, "parkour.check." + check);
        effectPlayer.fire(effects, "parkour-run-voided", runner, Map.of("check", check));
    }

    /** A run ended without a finish: its ghost and its splits go. */
    private void ended(UUID runner) {
        ParkourGhosts racing = ghosts;
        if (racing != null) {
            racing.ended(runner);
        }
        ParkourSplits taking = splits;
        if (taking != null) {
            taking.ended(runner);
        }
        ParkourChecks watching = checks;
        if (watching != null) {
            watching.ended(runner);
        }
    }

    /** A time the way a runner reads it: minutes, seconds and milliseconds. */
    public static String format(Duration time) {
        long millis = Math.max(0, time.toMillis());
        return String.format(Locale.ROOT, "%d:%02d.%03d", millis / 60_000, millis / 1000 % 60, millis % 1000);
    }

    /** What a finish says to its effect list: the time as the chat writes it, and in milliseconds. */
    private static Map<String, String> said(Duration time) {
        return Map.of("time", format(time), "millis", Long.toString(time.toMillis()));
    }

    private static net.kyori.adventure.text.minimessage.tag.resolver.TagResolver timed(String name, Duration time) {
        return Placeholder.unparsed(name, format(time));
    }

    private static boolean sameBlock(Location a, Location b) {
        return a.getBlockX() == b.getBlockX()
                && a.getBlockY() == b.getBlockY()
                && a.getBlockZ() == b.getBlockZ()
                && Objects.equals(a.getWorld(), b.getWorld());
    }
}
