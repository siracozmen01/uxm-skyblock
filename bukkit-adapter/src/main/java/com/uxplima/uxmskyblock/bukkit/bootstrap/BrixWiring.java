package com.uxplima.uxmskyblock.bukkit.bootstrap;

import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.uxplima.uxmskyblock.bukkit.brix.PlotStart;
import com.uxplima.uxmskyblock.bukkit.config.BrixConfiguration;
import com.uxplima.uxmskyblock.bukkit.schematic.IslandStart;
import com.uxplima.uxmskyblock.core.application.brix.BrixService;
import com.uxplima.uxmskyblock.core.application.gamemode.CreationActionProvider;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.persistence.bootstrap.PersistenceBootstrap;

/** The Brix game mode, while the operator lets islands be Brix plots. */
public final class BrixWiring implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(BrixWiring.class.getName());

    private final BrixConfiguration config;
    private final BrixService service;
    private final SchedulerPort scheduler;
    private final com.uxplima.uxmskyblock.bukkit.brix.BrixModes modes;
    private final com.uxplima.uxmskyblock.bukkit.brix.BrixRules rules;
    private final com.uxplima.uxmskyblock.core.application.brix.BrixSkies skies;
    private final com.uxplima.uxmskyblock.bukkit.brix.PlotSkyCommands skyCommands;
    private final com.uxplima.uxmskyblock.bukkit.brix.PlotReset reset;
    private final com.uxplima.uxmskyblock.bukkit.brix.BrixContestPlay contests;
    private final com.uxplima.uxmskyblock.bukkit.brix.BrixGallery gallery;
    private @org.jspecify.annotations.Nullable AutoCloseable beat;
    private @org.jspecify.annotations.Nullable AutoCloseable contestBeat;

    public BrixWiring(
            ConfigurationWiring configuration,
            PersistenceBootstrap persistence,
            SchedulerPort scheduler,
            com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener islands,
            java.util.function.Predicate<org.bukkit.entity.Player> inPlay) {
        this.config = Objects.requireNonNull(configuration.brixConfig(), "brixConfig must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.service = new BrixService(persistence.brixPlotsPort());
        this.skies = new com.uxplima.uxmskyblock.core.application.brix.BrixSkies(persistence.brixSkiesPort());
        this.modes = new com.uxplima.uxmskyblock.bukkit.brix.BrixModes(
                service,
                islands,
                scheduler,
                config.modes(),
                new com.uxplima.uxmskyblock.bukkit.creative.SealedInventory(
                        new com.uxplima.uxmskyblock.bukkit.creative.ServerInventoryCodec(), "brix", inPlay),
                configuration.messages(),
                configuration.effectsConfig(),
                new com.uxplima.uxmskyblock.bukkit.effect.InteractionEffectPlayer(scheduler, configuration.messages()),
                skies);
        this.skyCommands = new com.uxplima.uxmskyblock.bukkit.brix.PlotSkyCommands(
                service, skies, islands, scheduler, configuration.messages());
        this.reset = new com.uxplima.uxmskyblock.bukkit.brix.PlotReset(
                service,
                islands,
                persistence.islandStoragePort()::findLocationByIslandId,
                scheduler,
                config.ground(),
                configuration.messages(),
                java.time.Clock.systemUTC());
        this.rules = new com.uxplima.uxmskyblock.bukkit.brix.BrixRules(
                service,
                islands,
                config.rules(),
                () -> persistence.gridAllocator().gridSpacing(),
                config.ground().radius());
        com.uxplima.uxmskyblock.core.application.brix.BrixContests held =
                new com.uxplima.uxmskyblock.core.application.brix.BrixContests(
                        persistence.brixContestsPort(), java.time.Clock.systemUTC());
        this.gallery = new com.uxplima.uxmskyblock.bukkit.brix.BrixGallery(
                service,
                held,
                persistence.islandStoragePort()::findIslandById,
                scheduler,
                configuration.messages(),
                id -> org.bukkit.Bukkit.getOfflinePlayer(id).getName());
        this.contests = new com.uxplima.uxmskyblock.bukkit.brix.BrixContestPlay(
                held,
                service,
                islands,
                persistence.islandStoragePort()::findIslandById,
                id -> java.util.Optional.ofNullable(
                                org.bukkit.Bukkit.getOfflinePlayer(id).getName())
                        .orElse(id.toString().substring(0, 8)),
                scheduler,
                configuration.messages(),
                config.contests(),
                new com.uxplima.uxmskyblock.bukkit.effect.InteractionEffectPlayer(scheduler, configuration.messages()),
                org.bukkit.Bukkit::getOnlinePlayers,
                bound -> java.util.concurrent.ThreadLocalRandom.current().nextInt(bound),
                java.time.Clock.systemUTC());
        if (config.enabled()) {
            this.beat = modes.start();
            this.contestBeat = contests.start();
        }
        scheduler.async(() -> {
            try {
                int count = service.prime();
                int set = skies.prime();
                LOGGER.fine(() -> count + " Brix plots are in memory, " + set + " with a sky of their own.");
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "The Brix plots could not be read ahead.", e);
            }
        });
    }

    public BrixService service() {
        return service;
    }

    /** Who plays a plot in which mode, and what they brought, kept aside. */
    public com.uxplima.uxmskyblock.bukkit.brix.BrixModes modes() {
        return modes;
    }

    /** {@code /is plot time} and {@code /is plot weather}. */
    public com.uxplima.uxmskyblock.bukkit.brix.PlotSkyCommands skyCommands() {
        return skyCommands;
    }

    /** {@code /is plot reset}. */
    public com.uxplima.uxmskyblock.bukkit.brix.PlotReset reset() {
        return reset;
    }

    /** {@code /is plots}. */
    public com.uxplima.uxmskyblock.bukkit.brix.BrixGallery gallery() {
        return gallery;
    }

    /** The build contests: their commands, their beat and their payouts. */
    public com.uxplima.uxmskyblock.bukkit.brix.BrixContestPlay contests() {
        return contests;
    }

    /** What cannot happen on a plot. */
    public com.uxplima.uxmskyblock.bukkit.brix.BrixRules rules() {
        return rules;
    }

    public BrixConfiguration config() {
        return config;
    }

    public boolean enabled() {
        return config.enabled();
    }

    /** The action that makes an island a plot, while Brix is enabled, and none otherwise. */
    public List<CreationActionProvider<IslandStart>> startActions() {
        return config.enabled() ? List.of(new PlotStart(service, scheduler, config.ground())) : List.of();
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
                LOGGER.log(Level.WARNING, "Stopping the Brix modes failed.", e);
            }
        }
        AutoCloseable looking = contestBeat;
        contestBeat = null;
        if (looking != null) {
            try {
                looking.close();
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Stopping the Brix contest beat failed.", e);
            }
        }
    }
}
