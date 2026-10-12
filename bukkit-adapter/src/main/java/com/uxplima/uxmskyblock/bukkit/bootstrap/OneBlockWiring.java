package com.uxplima.uxmskyblock.bukkit.bootstrap;

import java.util.Objects;
import java.util.SplittableRandom;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.uxplima.uxmskyblock.bukkit.config.OneBlockConfiguration;
import com.uxplima.uxmskyblock.core.application.oneblock.OneBlockService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.persistence.bootstrap.PersistenceBootstrap;

/**
 * The OneBlock game mode's running parts: the service that counts breaks, and the schedule that writes
 * the count down.
 */
public final class OneBlockWiring implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(OneBlockWiring.class.getName());

    private final OneBlockConfiguration config;
    private final OneBlockService service;
    private final AutoCloseable saving;
    private final com.uxplima.uxmskyblock.bukkit.oneblock.OneBlockListener listener;
    private final com.uxplima.uxmskyblock.bukkit.oneblock.OneBlockPanel panel;
    private final com.uxplima.uxmskyblock.bukkit.oneblock.OneBlockDraws draws;

    public OneBlockWiring(
            ConfigurationWiring configuration,
            PersistenceBootstrap persistence,
            SchedulerPort scheduler,
            com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener islands,
            java.util.function.Function<
                            java.util.UUID, java.util.Optional<com.uxplima.uxmskyblock.core.domain.identity.ProfileId>>
                    activeProfile) {
        this.config = Objects.requireNonNull(configuration.oneBlockConfig(), "oneBlockConfig must not be null");
        this.service = new OneBlockService(persistence.oneBlockProgressPort(), config.phases(), new SplittableRandom());
        this.saving = scheduler.repeatAsync(service::flush, config.saveInterval(), config.saveInterval());
        this.listener = new com.uxplima.uxmskyblock.bukkit.oneblock.OneBlockListener(
                service,
                islands,
                scheduler,
                configuration.messages(),
                configuration.effectsConfig(),
                new com.uxplima.uxmskyblock.bukkit.effect.InteractionEffectPlayer(scheduler, configuration.messages()));
        this.draws =
                new com.uxplima.uxmskyblock.bukkit.oneblock.OneBlockDraws(config::entries, configuration.messages());
        listener.useDraws(draws);
        this.panel = new com.uxplima.uxmskyblock.bukkit.oneblock.OneBlockPanel(
                service, persistence.islandStoragePort(), scheduler, configuration.messages(), activeProfile);
        // Every OneBlock island into memory before players break anything, so no break is a query on
        // the region's thread. A break before it finishes reads its island once.
        scheduler.async(() -> {
            try {
                int count = service.prime();
                LOGGER.fine(() -> count + " OneBlock islands are in memory.");
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "The OneBlock islands could not be read ahead; each is read when needed.", e);
            }
        });
    }

    public OneBlockService service() {
        return service;
    }

    /** How the operator's pool entries are put into the world. */
    public com.uxplima.uxmskyblock.bukkit.oneblock.OneBlockDraws draws() {
        return draws;
    }

    /** Whether the operator lets islands be OneBlock islands, which is when the listener is registered. */
    public boolean enabled() {
        return config.enabled();
    }

    /**
     * The creation actions OneBlock adds: the one that sets an island's block, while the operator lets
     * islands be OneBlock islands, and none otherwise, so a OneBlock preset is not offered.
     */
    public java.util.List<
                    com.uxplima.uxmskyblock.core.application.gamemode.CreationActionProvider<
                            com.uxplima.uxmskyblock.bukkit.schematic.IslandStart>>
            startActions() {
        if (!config.enabled()) {
            return java.util.List.of();
        }
        return java.util.List.of(new com.uxplima.uxmskyblock.bukkit.oneblock.OneBlockStart(service, draws));
    }

    /**
     * The panel behind {@code /is oneblock} and the OneBlock placeholders, drawn from the operator's menu
     * file, or nothing while the operator does not let islands be OneBlock islands.
     */
    public com.uxplima.uxmskyblock.bukkit.oneblock.@org.jspecify.annotations.Nullable OneBlockPanel panel(
            com.uxplima.uxmskyblock.bukkit.menu.@org.jspecify.annotations.Nullable SkyblockMenuEngine engine) {
        if (!config.enabled()) {
            return null;
        }
        panel.useMenuEngine(engine);
        return panel;
    }

    public com.uxplima.uxmskyblock.bukkit.oneblock.OneBlockListener listener() {
        return listener;
    }

    /** Stops the schedule and writes what is still only counted, before the database closes. */
    @Override
    public void close() {
        try {
            saving.close();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Stopping the OneBlock save schedule failed.", e);
        }
        service.flush();
    }
}
