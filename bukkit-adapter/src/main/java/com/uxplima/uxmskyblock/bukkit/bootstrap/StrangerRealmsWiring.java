package com.uxplima.uxmskyblock.bukkit.bootstrap;

import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.event.entity.CreatureSpawnEvent;

import com.uxplima.uxmskyblock.bukkit.config.StrangerRealmsConfiguration;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.schematic.IslandStart;
import com.uxplima.uxmskyblock.bukkit.stranger.UpsideDownSpawns;
import com.uxplima.uxmskyblock.bukkit.stranger.UpsideDownStart;
import com.uxplima.uxmskyblock.core.application.gamemode.CreationActionProvider;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.stranger.StrangerRealmsService;
import com.uxplima.uxmskyblock.core.domain.dimension.DimensionMapping;
import com.uxplima.uxmskyblock.core.domain.dimension.IslandDimensionType;
import com.uxplima.uxmskyblock.persistence.bootstrap.PersistenceBootstrap;

/** The StrangerRealms game mode, while the operator lets islands be StrangerRealms islands. */
public final class StrangerRealmsWiring implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(StrangerRealmsWiring.class.getName());

    private final StrangerRealmsConfiguration config;
    private final StrangerRealmsService service;
    private final String upsideDownWorld;
    private final UpsideDownSpawns spawns;
    private final com.uxplima.uxmskyblock.bukkit.stranger.Glimmer glimmer;
    private final com.uxplima.uxmskyblock.bukkit.stranger.WarpedCompass compass;
    private boolean recipeAdded;
    private @org.jspecify.annotations.Nullable AutoCloseable beat;
    private @org.jspecify.annotations.Nullable AutoCloseable borderBeat;

    private final com.uxplima.uxmskyblock.core.application.stranger.StrangerClaims claims;
    private final UpsideDownStart mirror;
    private final com.uxplima.uxmskyblock.bukkit.stranger.UpsideDownReach reach;

    public StrangerRealmsWiring(
            ConfigurationWiring configuration,
            PersistenceBootstrap persistence,
            SchedulerPort scheduler,
            IslandProtectionListener islands) {
        this.config =
                Objects.requireNonNull(configuration.strangerRealmsConfig(), "strangerRealmsConfig must not be null");
        Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.service = new StrangerRealmsService(persistence.strangerRealmsPort());
        this.claims = new com.uxplima.uxmskyblock.core.application.stranger.StrangerClaims(
                service, persistence.islandStoragePort(), config.claim());
        DimensionMapping nether = configuration.dimensionConfig().mappings().get(IslandDimensionType.NETHER);
        this.upsideDownWorld = nether == null
                ? com.uxplima.uxmskyblock.bukkit.config.DimensionConfiguration.DEFAULT_NETHER_WORLD
                : nether.worldName();
        com.uxplima.uxmskyblock.bukkit.stranger.Realms realms = new com.uxplima.uxmskyblock.bukkit.stranger.Realms(
                service, islands, () -> upsideDownWorld, configuration::islandWorlds);
        this.spawns = new UpsideDownSpawns(
                realms,
                config.mobs(),
                (at, type) -> at.getWorld().spawnEntity(at, type, CreatureSpawnEvent.SpawnReason.CUSTOM));
        this.glimmer = new com.uxplima.uxmskyblock.bukkit.stranger.Glimmer(realms, scheduler, config.glimmer());
        this.mirror = new UpsideDownStart(service, scheduler, config.upsideDown(), () -> upsideDownWorld);
        this.reach = new com.uxplima.uxmskyblock.bukkit.stranger.UpsideDownReach(
                realms,
                mirror,
                scheduler,
                org.bukkit.Bukkit::getWorld,
                org.bukkit.World::isChunkGenerated,
                com.uxplima.uxmskyblock.bukkit.stranger.UpsideDownReach.copiedOnly(
                        config.upsideDown().lateCopy()));
        reach.useEffects(
                new com.uxplima.uxmskyblock.bukkit.effect.InteractionEffectPlayer(scheduler, configuration.messages()),
                configuration.effectsConfig());
        this.compass = new com.uxplima.uxmskyblock.bukkit.stranger.WarpedCompass(
                realms, scheduler, configuration.messages(), config.compass());
        if (config.enabled() && config.compass().enabled()) {
            this.beat = compass.start();
        }
        if (config.enabled() && config.border().enabled()) {
            java.util.List<String> bordered = borderedWorlds(configuration);
            this.borderBeat = new com.uxplima.uxmskyblock.bukkit.stranger.RealmBorders(
                            service, scheduler, config.border(), () -> bordered, org.bukkit.Bukkit::getWorld)
                    .start();
        }
        scheduler.async(() -> {
            try {
                int count = service.prime();
                LOGGER.fine(() -> count + " StrangerRealms islands are in memory.");
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "The StrangerRealms islands could not be read ahead.", e);
            }
        });
    }

    public StrangerRealmsService service() {
        return service;
    }

    public StrangerRealmsConfiguration config() {
        return config;
    }

    /** What the Upside Down makes of the creatures born in it, while StrangerRealms is enabled. */
    public UpsideDownSpawns spawns() {
        return spawns;
    }

    /** The Upside Down following the island's land past where it was first laid. */
    public com.uxplima.uxmskyblock.bukkit.stranger.UpsideDownReach reach() {
        return reach;
    }

    /** The glimmer between the land and the Upside Down. */
    public com.uxplima.uxmskyblock.bukkit.stranger.Glimmer glimmer() {
        return glimmer;
    }

    /** The warped compass, which points across the veil. */
    public com.uxplima.uxmskyblock.bukkit.stranger.WarpedCompass compass() {
        return compass;
    }

    /** Adds the compass's recipe, once, while the plugin enables. */
    public void addRecipe(org.bukkit.Server server) {
        if (!config.enabled() || !config.compass().enabled() || recipeAdded) {
            return;
        }
        compass.recipe().ifPresent(recipe -> {
            server.addRecipe(recipe);
            recipeAdded = true;
        });
    }

    /**
     * The worlds the border is set in: those the operator names, or else every world that only
     * StrangerRealms presets make islands in. A world another preset shares is never bordered.
     */
    static java.util.List<String> borderedWorlds(ConfigurationWiring configuration) {
        StrangerRealmsConfiguration config = configuration.strangerRealmsConfig();
        if (!config.border().worlds().isEmpty()) {
            return config.border().worlds();
        }
        String islandWorld = configuration.nodeConfig().worldName();
        java.util.Set<String> stranger = new java.util.LinkedHashSet<>();
        java.util.Set<String> shared = new java.util.HashSet<>();
        for (com.uxplima.uxmskyblock.core.domain.preset.StarterPreset preset :
                configuration.presetConfig().presets()) {
            String world = preset.worldOr(islandWorld);
            if (preset.mode() == com.uxplima.uxmskyblock.core.domain.gamemode.GameModeType.STRANGER_REALMS) {
                stranger.add(world);
            } else {
                shared.add(world);
            }
        }
        stranger.removeAll(shared);
        return java.util.List.copyOf(stranger);
    }

    /** Takes the recipe away and stops the compass turning, before the server stops. */
    @Override
    public void close() {
        if (recipeAdded) {
            recipeAdded = false;
            org.bukkit.Bukkit.removeRecipe(com.uxplima.uxmskyblock.bukkit.stranger.WarpedCompass.RECIPE);
        }
        AutoCloseable running = beat;
        beat = null;
        if (running != null) {
            try {
                running.close();
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Stopping the warped compass failed.", e);
            }
        }
        AutoCloseable moving = borderBeat;
        borderBeat = null;
        if (moving != null) {
            try {
                moving.close();
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Stopping the StrangerRealms border failed.", e);
            }
        }
    }

    /**
     * How far the island reaches when its size gives it {@code base}: grown by its members for a
     * StrangerRealms island, {@code base} for any other. Reads the island, so off the main thread.
     */
    public int claimRadius(com.uxplima.uxmskyblock.core.domain.identity.IslandId islandId, int base) {
        return claims.radius(islandId, base);
    }

    public boolean enabled() {
        return config.enabled();
    }

    /** The action that mirrors the land into the Upside Down, while StrangerRealms is enabled. */
    public List<CreationActionProvider<IslandStart>> startActions() {
        return config.enabled() ? List.<CreationActionProvider<IslandStart>>of(mirror) : List.of();
    }
}
