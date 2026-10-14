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
    private @org.jspecify.annotations.Nullable AutoCloseable beat;

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
        this.rules = new com.uxplima.uxmskyblock.bukkit.brix.BrixRules(service, islands, config.rules());
        if (config.enabled()) {
            this.beat = modes.start();
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
    }
}
