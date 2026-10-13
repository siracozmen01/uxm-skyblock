package com.uxplima.uxmskyblock.bukkit.bootstrap;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import com.uxplima.uxmskyblock.bukkit.boxed.BoxedBorders;
import com.uxplima.uxmskyblock.bukkit.boxed.BoxedListener;
import com.uxplima.uxmskyblock.bukkit.boxed.BoxedStart;
import com.uxplima.uxmskyblock.bukkit.config.BoxedConfiguration;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffectPlayer;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.schematic.IslandStart;
import com.uxplima.uxmskyblock.core.application.boxed.BoxedService;
import com.uxplima.uxmskyblock.core.application.gamemode.CreationActionProvider;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.gamemode.GameModeType;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.persistence.bootstrap.PersistenceBootstrap;
import org.jspecify.annotations.Nullable;

/** The Boxed game mode, while the operator lets islands be Boxed islands. */
public final class BoxedWiring implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(BoxedWiring.class.getName());

    private final BoxedConfiguration config;
    private final BoxedService service;
    private final SchedulerPort scheduler;
    private final BoxedListener listener;
    private volatile Consumer<IslandId> boxChanged = islandId -> {};
    private @Nullable AutoCloseable borders;
    private final com.uxplima.uxmskyblock.bukkit.boxed.BoxedLandCopier land;

    public BoxedWiring(
            ConfigurationWiring configuration,
            PersistenceBootstrap persistence,
            SchedulerPort scheduler,
            IslandProtectionListener islands,
            Function<UUID, Optional<ProfileId>> activeProfile) {
        this.config = Objects.requireNonNull(configuration.boxedConfig(), "boxedConfig must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.service = new BoxedService(persistence.boxedIslandsPort(), config.rules());
        String islandWorld = configuration.nodeConfig().worldName();
        Set<String> boxedWorlds = configuration.presetConfig().presets().stream()
                .filter(preset -> preset.mode() == GameModeType.BOXED)
                .map(preset -> preset.worldOr(islandWorld))
                .collect(Collectors.toUnmodifiableSet());
        this.land = new com.uxplima.uxmskyblock.bukkit.boxed.BoxedLandCopier(
                persistence.boxedLandPort(),
                service,
                persistence.islandStoragePort()::findLocationByIslandId,
                new com.uxplima.uxmskyblock.bukkit.boxed.TemplateChunks(scheduler, org.bukkit.Bukkit::getWorld),
                scheduler,
                config.land(),
                () -> persistence.gridAllocator().gridSpacing());
        this.listener = new BoxedListener(
                service,
                islands,
                scheduler,
                configuration.messages(),
                configuration.effectsConfig(),
                new InteractionEffectPlayer(scheduler, configuration.messages()),
                activeProfile,
                persistence.islandStoragePort()::findIslandIdByProfileId,
                this::boxChanged,
                boxedWorlds,
                config.bypassPermission());
        scheduler.async(() -> {
            try {
                int count = service.prime();
                LOGGER.fine(() -> count + " Boxed islands are in memory.");
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "The Boxed islands could not be read ahead.", e);
            }
        });
        if (config.enabled()) {
            BoxedBorders shown = new BoxedBorders(service, islands, scheduler, config.borderEvery());
            // A player on an island is when its land is brought up to its box, after a stop cut it short.
            shown.whenShown(land::bringUp);
            this.borders = shown.start();
        }
    }

    /** What moves an island's edge to its box, set once the border service exists. */
    public void whenBoxChanged(Consumer<IslandId> moveTheEdge) {
        this.boxChanged = Objects.requireNonNull(moveTheEdge, "moveTheEdge must not be null");
    }

    private void boxChanged(IslandId islandId) {
        boxChanged.accept(islandId);
        land.bringUp(islandId);
    }

    /** Lets go of the land copying for an island that is gone. */
    public void forgetLand(IslandId islandId) {
        land.forget(islandId);
    }

    /** What an operator's change to a box asks for: the island's edge moved to it. */
    public Consumer<IslandId> edgeMover() {
        return this::boxChanged;
    }

    public BoxedService service() {
        return service;
    }

    public BoxedListener listener() {
        return listener;
    }

    public boolean enabled() {
        return config.enabled();
    }

    /** The action that makes an island a Boxed island, while Boxed is enabled, and none otherwise. */
    public List<CreationActionProvider<IslandStart>> startActions() {
        return config.enabled()
                ? List.of(new BoxedStart(service, scheduler, this::boxChanged, land::begin))
                : List.of();
    }

    /** Stops showing players their boxes, before the server stops. */
    @Override
    public void close() {
        AutoCloseable running = borders;
        borders = null;
        if (running != null) {
            try {
                running.close();
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Stopping the Boxed borders failed.", e);
            }
        }
    }
}
