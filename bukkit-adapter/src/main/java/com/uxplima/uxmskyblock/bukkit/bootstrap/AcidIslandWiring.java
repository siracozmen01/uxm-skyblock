package com.uxplima.uxmskyblock.bukkit.bootstrap;

import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Server;

import com.uxplima.uxmskyblock.bukkit.acid.AcidHazard;
import com.uxplima.uxmskyblock.bukkit.acid.AcidSeaStart;
import com.uxplima.uxmskyblock.bukkit.acid.AcidWater;
import com.uxplima.uxmskyblock.bukkit.acid.AcidWaterListener;
import com.uxplima.uxmskyblock.bukkit.config.AcidIslandConfiguration;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffectPlayer;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.schematic.IslandStart;
import com.uxplima.uxmskyblock.core.application.acid.AcidIslandService;
import com.uxplima.uxmskyblock.core.application.gamemode.CreationActionProvider;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.persistence.bootstrap.PersistenceBootstrap;
import org.jspecify.annotations.Nullable;

/** The AcidIsland game mode, while the operator lets islands be AcidIsland islands. */
public final class AcidIslandWiring implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(AcidIslandWiring.class.getName());

    private final AcidIslandConfiguration config;
    private final AcidIslandService service;
    private final SchedulerPort scheduler;
    private final AcidHazard hazard;
    private final AcidWaterListener waterListener;
    private boolean recipesAdded;
    private @Nullable AutoCloseable beat;
    private @Nullable AutoCloseable tideBeat;
    private @Nullable AcidSeaStart seaStart;
    private final com.uxplima.uxmskyblock.bukkit.acid.AcidTideRunner tide;

    public AcidIslandWiring(
            ConfigurationWiring configuration,
            PersistenceBootstrap persistence,
            SchedulerPort scheduler,
            IslandProtectionListener islands) {
        this.config = Objects.requireNonNull(configuration.acidIslandConfig(), "acidIslandConfig must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.service = new AcidIslandService(persistence.acidIslandsPort());
        this.hazard = new AcidHazard(
                service,
                islands,
                scheduler,
                config,
                configuration.effectsConfig(),
                new InteractionEffectPlayer(scheduler, configuration.messages()));
        // Items dissolve and creatures burn in the sea, around each player, on the threads that own them.
        hazard.alsoLookAt(new com.uxplima.uxmskyblock.bukkit.acid.AcidSeaLife(
                service,
                islands,
                config,
                configuration.effectsConfig(),
                new InteractionEffectPlayer(scheduler, configuration.messages()),
                System::nanoTime,
                org.bukkit.Bukkit::isOwnedByCurrentRegion,
                configuration.messages(),
                new com.uxplima.uxmskyblock.bukkit.acid.ServerVentLoot(),
                org.bukkit.entity.Item::getThrower));
        this.waterListener = new AcidWaterListener(hazard, configuration.messages());
        // The sea rises and falls with the tide, around the islands players are on.
        this.tide = new com.uxplima.uxmskyblock.bukkit.acid.AcidTideRunner(
                service,
                islands,
                scheduler,
                config,
                configuration.effectsConfig(),
                new InteractionEffectPlayer(scheduler, configuration.messages()),
                configuration.messages(),
                System::currentTimeMillis,
                org.bukkit.World::isChunkLoaded);
        if (config.enabled()) {
            this.beat = hazard.start();
            this.tideBeat = tide.start();
        }
        scheduler.async(() -> {
            try {
                int count = service.prime();
                LOGGER.fine(() -> count + " AcidIsland islands are in memory.");
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "The AcidIsland islands could not be read ahead.", e);
            }
        });
    }

    public AcidIslandService service() {
        return service;
    }

    public AcidIslandConfiguration config() {
        return config;
    }

    public AcidHazard hazard() {
        return hazard;
    }

    /** The tide, which brings a chunk of the sea to its level as the chunk loads. */
    public com.uxplima.uxmskyblock.bukkit.acid.AcidTideRunner tide() {
        return tide;
    }

    public AcidWaterListener waterListener() {
        return waterListener;
    }

    /** Adds the ways water is made clean the operator left on. Once, while the plugin enables. */
    public void addRecipes(Server server) {
        if (!config.enabled() || recipesAdded) {
            return;
        }
        recipesAdded = true;
        if (config.purification().furnace()) {
            server.addRecipe(AcidWater.furnaceRecipe());
            server.addRecipe(AcidWater.furnaceBucketRecipe());
        }
        if (config.purification().brewingWithCoal()) {
            try {
                server.getPotionBrewer().addPotionMix(AcidWater.brewingMix());
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "Brewing acid water clean with coal could not be added.", e);
            }
        }
    }

    public boolean enabled() {
        return config.enabled();
    }

    /** The action that lays the acid sea, while AcidIsland is enabled, and none otherwise. */
    public List<CreationActionProvider<IslandStart>> startActions() {
        return config.enabled() ? List.of(seaStart()) : List.of();
    }

    /** The action that lays the acid sea, made once, which also lays the sea between islands. */
    public synchronized AcidSeaStart seaStart() {
        AcidSeaStart made = seaStart;
        if (made == null) {
            made = new AcidSeaStart(
                    service,
                    scheduler,
                    config.sea(),
                    seaBiome(),
                    AcidIslandWiring::refresh,
                    AcidSeaStart.buried(
                            config.sea(),
                            config.buried(),
                            new com.uxplima.uxmskyblock.bukkit.poseidon.ServerWreckTemplates(),
                            new com.uxplima.uxmskyblock.bukkit.poseidon.ServerChestLoot(),
                            scheduler));
            seaStart = made;
        }
        return made;
    }

    /** The sea between islands, laid as each chunk of its world is made, while it is on. */
    public com.uxplima.uxmskyblock.bukkit.acid.AcidOpenSea openSea() {
        return new com.uxplima.uxmskyblock.bukkit.acid.AcidOpenSea(seaStart(), config.between());
    }

    /** The biome the sea is painted with, or none: none asked for, or one the server does not know, said. */
    private org.bukkit.block.@Nullable Biome seaBiome() {
        if (!config.look().paints()) {
            return null;
        }
        org.bukkit.NamespacedKey key =
                org.bukkit.NamespacedKey.fromString(config.look().biome());
        org.bukkit.block.Biome biome = key == null
                ? null
                : io.papermc.paper.registry.RegistryAccess.registryAccess()
                        .getRegistry(io.papermc.paper.registry.RegistryKey.BIOME)
                        .get(key);
        if (biome == null) {
            LOGGER.warning(() -> "modules/acidisland.conf sea look names the biome "
                    + config.look().biome()
                    + ", which the server does not know. The sea keeps the biome its world made.");
        }
        return biome;
    }

    // The only way the server offers to send a chunk again, so a biome shows without the player leaving it.
    @SuppressWarnings("deprecation")
    private static void refresh(org.bukkit.World world, int chunkX, int chunkZ) {
        world.refreshChunk(chunkX, chunkZ);
    }

    /** Stops the sea and the rain burning, before the server stops. */
    @Override
    public void close() {
        if (recipesAdded) {
            recipesAdded = false;
            Bukkit.removeRecipe(AcidWater.FURNACE);
            Bukkit.removeRecipe(AcidWater.FURNACE_BUCKET);
            try {
                Bukkit.getPotionBrewer().removePotionMix(AcidWater.BREWING);
            } catch (RuntimeException e) {
                LOGGER.log(Level.FINE, "Brewing acid water clean could not be removed.", e);
            }
        }
        AutoCloseable tideRunning = tideBeat;
        tideBeat = null;
        if (tideRunning != null) {
            try {
                tideRunning.close();
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Stopping the acid tide failed.", e);
            }
        }
        AutoCloseable running = beat;
        beat = null;
        if (running != null) {
            try {
                running.close();
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Stopping the acid hazard failed.", e);
            }
        }
    }
}
