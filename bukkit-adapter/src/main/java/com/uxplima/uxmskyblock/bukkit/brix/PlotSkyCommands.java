package com.uxplima.uxmskyblock.bukkit.brix;

import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.core.application.brix.BrixService;
import com.uxplima.uxmskyblock.core.application.brix.BrixSkies;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.brix.PlotSky;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.island.Island;

/**
 * {@code /is plot time} and {@code /is plot weather}: a plot's team sets the time and weather everyone standing on
 * their plot sees, or gives them back to the world.
 */
public final class PlotSkyCommands {

    private static final Logger LOGGER = Logger.getLogger(PlotSkyCommands.class.getName());

    private final BrixService plots;
    private final BrixSkies skies;
    private final IslandProtectionListener islands;
    private final SchedulerPort scheduler;
    private final Messages messages;

    public PlotSkyCommands(
            BrixService plots,
            BrixSkies skies,
            IslandProtectionListener islands,
            SchedulerPort scheduler,
            Messages messages) {
        this.plots = Objects.requireNonNull(plots, "plots must not be null");
        this.skies = Objects.requireNonNull(skies, "skies must not be null");
        this.islands = Objects.requireNonNull(islands, "islands must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
    }

    /** Sets the time of the plot the player stands on, written as a word or a number of ticks. */
    public void time(Player player, String written) {
        Optional<Island> plot = teamPlot(player);
        if (plot.isEmpty()) {
            return;
        }
        Optional<Integer> ticks = PlotSky.timeOf(written);
        if (ticks.isEmpty()) {
            messages.send(player, "brix.sky.unknown_time", Placeholder.unparsed("written", written));
            return;
        }
        PlotSky sky = skies.of(plot.get().id()).withTime(ticks.get());
        keep(player, plot.get(), sky, "brix.sky.time_set", written);
    }

    /** Sets the weather of the plot the player stands on: clear, rain, or the world's. */
    public void weather(Player player, String written) {
        Optional<Island> plot = teamPlot(player);
        if (plot.isEmpty()) {
            return;
        }
        Optional<PlotSky.Weather> weather = PlotSky.weatherOf(written);
        if (weather.isEmpty()) {
            messages.send(player, "brix.sky.unknown_weather", Placeholder.unparsed("written", written));
            return;
        }
        PlotSky sky = skies.of(plot.get().id()).withWeather(weather.get());
        keep(player, plot.get(), sky, "brix.sky.weather_set", written);
    }

    private void keep(Player player, Island plot, PlotSky sky, String said, String written) {
        scheduler.async(() -> {
            try {
                skies.set(plot.id(), sky);
                scheduler.onEntity(
                        PlayerUuid.of(player.getUniqueId()),
                        () -> messages.send(player, said, Placeholder.unparsed("written", written)));
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "The sky of Brix plot " + plot.id() + " could not be kept.", e);
                scheduler.onEntity(PlayerUuid.of(player.getUniqueId()), () -> messages.send(player, "brix.sky.failed"));
            }
        });
    }

    /** The plot the player stands on, when they are of its team; having told them why not otherwise. */
    private Optional<Island> teamPlot(Player player) {
        Location at = player.getLocation();
        Optional<Island> island = at == null ? Optional.empty() : islands.findIslandAt(at);
        if (island.isEmpty() || !plots.isPlot(island.get().id())) {
            messages.send(player, "brix.not_plot");
            return Optional.empty();
        }
        PlayerUuid who = PlayerUuid.of(player.getUniqueId());
        if (island.get().members().values().stream()
                .noneMatch(member -> member.playerUuid().equals(who))) {
            messages.send(player, "brix.not_team");
            return Optional.empty();
        }
        return island;
    }
}
