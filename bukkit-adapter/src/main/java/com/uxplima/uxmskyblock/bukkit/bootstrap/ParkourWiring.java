package com.uxplima.uxmskyblock.bukkit.bootstrap;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.uxplima.uxmskyblock.bukkit.config.ParkourConfiguration;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffectPlayer;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.parkour.CourseStart;
import com.uxplima.uxmskyblock.bukkit.parkour.ParkourRuns;
import com.uxplima.uxmskyblock.bukkit.schematic.IslandStart;
import com.uxplima.uxmskyblock.core.application.gamemode.CreationActionProvider;
import com.uxplima.uxmskyblock.core.application.parkour.ParkourService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.persistence.bootstrap.PersistenceBootstrap;

/** The Parkour game mode, while the operator lets islands be Parkour courses. */
public final class ParkourWiring implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(ParkourWiring.class.getName());

    private final ParkourConfiguration config;
    private final ParkourService service;
    private final SchedulerPort scheduler;
    private final ParkourRuns runs;
    private final com.uxplima.uxmskyblock.bukkit.parkour.ParkourModes modes;
    private final com.uxplima.uxmskyblock.bukkit.parkour.ParkourBoard board;
    private @org.jspecify.annotations.Nullable AutoCloseable beat;

    public ParkourWiring(
            ConfigurationWiring configuration,
            PersistenceBootstrap persistence,
            SchedulerPort scheduler,
            IslandProtectionListener islands,
            java.util.function.Predicate<org.bukkit.entity.Player> inPlay,
            com.uxplima.uxmskyblock.bukkit.parkour.ParkourGhosts.Stage ghostStage) {
        this.config = Objects.requireNonNull(configuration.parkourConfig(), "parkourConfig must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.service = new ParkourService(persistence.parkourPort());
        this.runs = new ParkourRuns(
                service,
                islands,
                scheduler,
                config,
                configuration.messages(),
                configuration.effectsConfig(),
                new InteractionEffectPlayer(scheduler, configuration.messages()),
                Clock.systemUTC());
        this.modes = new com.uxplima.uxmskyblock.bukkit.parkour.ParkourModes(
                service,
                islands,
                scheduler,
                config.modes(),
                runner -> runs.runningOn(runner).isPresent(),
                new com.uxplima.uxmskyblock.bukkit.creative.SealedInventory(
                        new com.uxplima.uxmskyblock.bukkit.creative.ServerInventoryCodec(), "parkour", inPlay),
                configuration.messages(),
                configuration.effectsConfig(),
                new InteractionEffectPlayer(scheduler, configuration.messages()));
        runs.whenStarted(modes::startRun);
        runs.rewards(
                new com.uxplima.uxmskyblock.bukkit.effect.InteractionEffects(Map.of(
                        ParkourRuns.FIRST_FINISH,
                        com.uxplima.uxmskyblock.bukkit.config.EffectsConfiguration.parseAll(
                                "the Parkour first-finish rewards",
                                config.rewards().firstFinish()),
                        ParkourRuns.PERSONAL_BEST,
                        com.uxplima.uxmskyblock.bukkit.config.EffectsConfiguration.parseAll(
                                "the Parkour personal-best rewards",
                                config.rewards().personalBest()),
                        ParkourRuns.COURSE_RECORD,
                        com.uxplima.uxmskyblock.bukkit.config.EffectsConfiguration.parseAll(
                                "the Parkour course-record rewards",
                                config.rewards().courseRecord()))),
                persistence.parkourRewardsPort(),
                config.rewards());
        runs.splits(new com.uxplima.uxmskyblock.bukkit.parkour.ParkourSplits(
                persistence.parkourSplitsPort(), scheduler, config.runs().splits()));
        runs.ghosts(new com.uxplima.uxmskyblock.bukkit.parkour.ParkourGhosts(
                persistence.parkourGhostsPort(),
                scheduler,
                config.ghost(),
                config.runs().timeLimit(),
                configuration.messages(),
                configuration.effectsConfig(),
                new InteractionEffectPlayer(scheduler, configuration.messages()),
                Clock.systemUTC(),
                ghostStage));
        this.board = new com.uxplima.uxmskyblock.bukkit.parkour.ParkourBoard(
                service,
                islands,
                scheduler,
                configuration.messages(),
                config.runs().boardSize(),
                uuid -> org.bukkit.Bukkit.getOfflinePlayer(uuid).getName());
        if (config.enabled()) {
            this.beat = modes.start();
        }
        scheduler.async(() -> {
            try {
                int count = service.prime();
                LOGGER.fine(() -> count + " Parkour courses are in memory.");
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "The Parkour courses could not be read ahead.", e);
            }
        });
    }

    public ParkourService service() {
        return service;
    }

    public ParkourRuns runs() {
        return runs;
    }

    public com.uxplima.uxmskyblock.bukkit.parkour.ParkourModes modes() {
        return modes;
    }

    /** What the course command shows. */
    public com.uxplima.uxmskyblock.bukkit.parkour.ParkourBoard board() {
        return board;
    }

    public ParkourConfiguration config() {
        return config;
    }

    public boolean enabled() {
        return config.enabled();
    }

    /** The action that makes an island a course, while Parkour is enabled, and none otherwise. */
    public List<CreationActionProvider<IslandStart>> startActions() {
        return config.enabled() ? List.of(new CourseStart(service, scheduler, config.markers())) : List.of();
    }

    /** Stops setting modes, before the server stops. */
    @Override
    public void close() {
        AutoCloseable running = beat;
        beat = null;
        if (running != null) {
            try {
                running.close();
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Stopping the Parkour modes failed.", e);
            }
        }
    }
}
