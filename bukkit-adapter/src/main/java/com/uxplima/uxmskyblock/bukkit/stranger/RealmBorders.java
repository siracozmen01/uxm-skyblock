package com.uxplima.uxmskyblock.bukkit.stranger;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.World;
import org.bukkit.WorldBorder;

import com.uxplima.uxmskyblock.bukkit.config.StrangerRealmsConfiguration;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.stranger.StrangerRealmsService;
import org.jspecify.annotations.Nullable;

/**
 * The border of the StrangerRealms land: wide enough to hold the furthest StrangerRealms island with
 * room around it, growing as islands are made anywhere on the network.
 *
 * <p>With a width for each player, the border also widens as players come onto the network, and narrows as they
 * leave, never below what holds the islands. How far the islands reach, and how many players are on, is read off the
 * database on the scheduler, which every server shares,
 * and the border is moved on the global thread, which owns it.
 */
public final class RealmBorders {

    private static final Logger LOGGER = Logger.getLogger(RealmBorders.class.getName());

    private final StrangerRealmsService service;
    private final SchedulerPort scheduler;
    private final StrangerRealmsConfiguration.Border config;
    private final Supplier<List<String>> worlds;
    private final Function<String, @Nullable World> worldNamed;
    private final java.util.function.IntSupplier playersOnline;

    /** @param worlds the worlds the border is set in */
    public RealmBorders(
            StrangerRealmsService service,
            SchedulerPort scheduler,
            StrangerRealmsConfiguration.Border config,
            Supplier<List<String>> worlds,
            Function<String, @Nullable World> worldNamed,
            java.util.function.IntSupplier playersOnline) {
        this.playersOnline = Objects.requireNonNull(playersOnline, "playersOnline must not be null");
        this.service = Objects.requireNonNull(service, "service must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.worlds = Objects.requireNonNull(worlds, "worlds must not be null");
        this.worldNamed = Objects.requireNonNull(worldNamed, "worldNamed must not be null");
    }

    /** Starts the beat, the first round at once. Closing what it returns stops it. */
    public AutoCloseable start() {
        return scheduler.repeatGlobal(this::round, java.time.Duration.ofSeconds(1), config.checkEvery());
    }

    /** Reads how far the islands reach, then moves the border to hold them. */
    public void round() {
        scheduler.async(() -> {
            double size;
            try {
                int players = config.rule().byPlayers() ? playersOnline.getAsInt() : 0;
                size = config.rule().size(service.farthestReach(), players);
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "How far the StrangerRealms islands reach could not be read.", e);
                return;
            }
            scheduler.onGlobal(() -> apply(size));
        });
    }

    /** Moves the border of every world it is set in to {@code size}. On the global thread. */
    void apply(double size) {
        for (String name : worlds.get()) {
            World world = worldNamed.apply(name);
            if (world == null) {
                continue;
            }
            WorldBorder border = world.getWorldBorder();
            if (border.getCenter().getX() != config.centerX()
                    || border.getCenter().getZ() != config.centerZ()) {
                border.setCenter(config.centerX(), config.centerZ());
            }
            if (Math.abs(border.getSize() - size) >= 1) {
                border.changeSize(size, config.transition().toMillis() / 50);
            }
        }
    }
}
