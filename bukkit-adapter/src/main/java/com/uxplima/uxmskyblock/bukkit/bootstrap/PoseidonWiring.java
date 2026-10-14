package com.uxplima.uxmskyblock.bukkit.bootstrap;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.uxplima.uxmskyblock.bukkit.config.PoseidonConfiguration;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffectPlayer;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.poseidon.OceanStart;
import com.uxplima.uxmskyblock.bukkit.poseidon.PoseidonHazard;
import com.uxplima.uxmskyblock.bukkit.poseidon.ServerChestLoot;
import com.uxplima.uxmskyblock.bukkit.poseidon.ServerWreckTemplates;
import com.uxplima.uxmskyblock.bukkit.poseidon.WreckStart;
import com.uxplima.uxmskyblock.bukkit.schematic.IslandStart;
import com.uxplima.uxmskyblock.core.application.gamemode.CreationActionProvider;
import com.uxplima.uxmskyblock.core.application.poseidon.PoseidonService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.persistence.bootstrap.PersistenceBootstrap;
import org.jspecify.annotations.Nullable;

/** The Poseidon game mode, while the operator lets islands be Poseidon islands. */
public final class PoseidonWiring implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(PoseidonWiring.class.getName());

    private final PoseidonConfiguration config;
    private final PoseidonService service;
    private final SchedulerPort scheduler;
    private final PoseidonHazard hazard;
    private final com.uxplima.uxmskyblock.bukkit.poseidon.SchematicWreckTemplates templates =
            new com.uxplima.uxmskyblock.bukkit.poseidon.SchematicWreckTemplates(
                    new ServerWreckTemplates(),
                    com.uxplima.uxmskyblock.bukkit.poseidon.SchematicWreckTemplates::parseQuietly);
    private final com.uxplima.uxmskyblock.bukkit.poseidon.DeepBoss boss;
    private @Nullable AutoCloseable beat;
    private @Nullable AutoCloseable bossBeat;

    public PoseidonWiring(
            ConfigurationWiring configuration,
            PersistenceBootstrap persistence,
            SchedulerPort scheduler,
            IslandProtectionListener islands,
            org.bukkit.plugin.Plugin plugin) {
        this.config = Objects.requireNonNull(configuration.poseidonConfig(), "poseidonConfig must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.service = new PoseidonService(persistence.poseidonIslandsPort());
        this.hazard = new PoseidonHazard(
                service,
                islands,
                scheduler,
                config,
                configuration.effectsConfig(),
                new InteractionEffectPlayer(scheduler, configuration.messages()),
                Clock.systemUTC());
        InteractionEffectPlayer effectPlayer = new InteractionEffectPlayer(scheduler, configuration.messages());
        this.boss = new com.uxplima.uxmskyblock.bukkit.poseidon.DeepBoss(
                config.boss(),
                service,
                islands,
                scheduler,
                configuration.messages(),
                configuration::effectsConfig,
                effectPlayer,
                configuration::lootTables,
                new com.uxplima.uxmskyblock.bukkit.loot.LootFiller(
                        effectPlayer,
                        configuration::effectsConfig,
                        com.uxplima.uxmskyblock.bukkit.loot.LootFiller.SERVER),
                (risen, after, task) -> risen.getScheduler()
                        .runDelayed(plugin, ignored -> task.run(), task, Math.max(1, after.toMillis() / 50)),
                Clock.systemUTC(),
                new java.util.Random());
        if (config.enabled()) {
            this.beat = hazard.start();
            this.bossBeat = boss.start();
        }
        scheduler.async(() -> {
            try {
                int count = service.prime();
                LOGGER.fine(() -> count + " Poseidon islands are in memory.");
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "The Poseidon islands could not be read ahead.", e);
            }
        });
    }

    /**
     * Writes the shipped temple where no file stands yet, then reads every schematic a wreck group names. Off the
     * main thread; a group whose file is not read yet lays nothing.
     */
    public java.util.concurrent.CompletableFuture<Integer> primeTemplates(
            com.uxplima.uxmskyblock.bukkit.schematic.IslandSchematics files, int dataVersion) {
        if (!config.enabled()) {
            return java.util.concurrent.CompletableFuture.completedFuture(0);
        }
        java.util.List<String> keys = config.wrecks().values().stream()
                .flatMap(wreck -> wreck.templates().stream())
                .toList();
        return files.writeIfMissing(
                        com.uxplima.uxmskyblock.bukkit.poseidon.MonumentLayout.FILE,
                        com.uxplima.uxmskyblock.bukkit.poseidon.MonumentLayout.shipped(dataVersion))
                .handle((written, failure) -> null)
                .thenCompose(written -> templates.prime(files, keys));
    }

    /** The deep boss, whose death is heard while Poseidon is enabled. */
    public com.uxplima.uxmskyblock.bukkit.poseidon.DeepBoss boss() {
        return boss;
    }

    public PoseidonService service() {
        return service;
    }

    public PoseidonConfiguration config() {
        return config;
    }

    public PoseidonHazard hazard() {
        return hazard;
    }

    public boolean enabled() {
        return config.enabled();
    }

    /** The actions that lay the ocean and each wreck, while Poseidon is enabled, and none otherwise. */
    public List<CreationActionProvider<IslandStart>> startActions() {
        if (!config.enabled()) {
            return List.of();
        }
        List<CreationActionProvider<IslandStart>> actions = new java.util.ArrayList<>();
        actions.add(new OceanStart(service, scheduler, config.ocean()));
        ServerChestLoot loot = new ServerChestLoot();
        config.wrecks()
                .forEach((name, wreck) ->
                        actions.add(new WreckStart("uxm:" + name, wreck, config.ocean(), templates, loot, scheduler)));
        return actions;
    }

    /** Stops the air hurting, before the server stops. */
    @Override
    public void close() {
        AutoCloseable rising = bossBeat;
        bossBeat = null;
        if (rising != null) {
            try {
                rising.close();
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Stopping the deep boss failed.", e);
            }
        }
        AutoCloseable running = beat;
        beat = null;
        if (running != null) {
            try {
                running.close();
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Stopping the Poseidon hazard failed.", e);
            }
        }
    }
}
