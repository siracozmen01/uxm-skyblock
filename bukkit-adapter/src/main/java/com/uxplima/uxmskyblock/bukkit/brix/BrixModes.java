package com.uxplima.uxmskyblock.bukkit.brix;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.WeatherType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import com.uxplima.uxmskyblock.bukkit.config.BrixConfiguration;
import com.uxplima.uxmskyblock.bukkit.creative.SealedInventory;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffectPlayer;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffects;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.core.application.brix.BrixService;
import com.uxplima.uxmskyblock.core.application.brix.BrixSkies;
import com.uxplima.uxmskyblock.core.application.brix.BrixSkiesPort;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.brix.PlotSky;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.island.Island;

/**
 * How a plot is played: its own team builds on it in one game mode and everybody else looks at it in
 * another, and whatever a player brings is kept aside while they are on it. A player who leaves the plot,
 * or the server, has what they came with back, and nothing they held on the plot leaves it.
 *
 * <p>A beat hands each player to their own thread, where the plot they stand on is read and their mode
 * is set.
 */
public final class BrixModes implements Listener {

    private final BrixService service;
    private final IslandProtectionListener islands;
    private final SchedulerPort scheduler;
    private final BrixConfiguration.Modes modes;
    private final SealedInventory sealed;
    private final Messages messages;
    private final InteractionEffects effects;
    private final InteractionEffectPlayer effectPlayer;
    private final BrixSkies skies;
    /** The players whose time or weather a plot set, to be given the world's back when they leave it. */
    private final Set<UUID> skied = ConcurrentHashMap.newKeySet();

    /** Plots that keep their world's sky. */
    public BrixModes(
            BrixService service,
            IslandProtectionListener islands,
            SchedulerPort scheduler,
            BrixConfiguration.Modes modes,
            SealedInventory sealed,
            Messages messages,
            InteractionEffects effects,
            InteractionEffectPlayer effectPlayer) {
        this(
                service,
                islands,
                scheduler,
                modes,
                sealed,
                messages,
                effects,
                effectPlayer,
                new BrixSkies(new BrixSkiesPort() {
                    @Override
                    public Map<IslandId, PlotSky> findAll() {
                        return Map.of();
                    }

                    @Override
                    public void save(IslandId islandId, PlotSky sky) {
                        // Nothing is kept.
                    }
                }));
    }

    public BrixModes(
            BrixService service,
            IslandProtectionListener islands,
            SchedulerPort scheduler,
            BrixConfiguration.Modes modes,
            SealedInventory sealed,
            Messages messages,
            InteractionEffects effects,
            InteractionEffectPlayer effectPlayer,
            BrixSkies skies) {
        this.skies = Objects.requireNonNull(skies, "skies must not be null");
        this.service = Objects.requireNonNull(service, "service must not be null");
        this.islands = Objects.requireNonNull(islands, "islands must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.modes = Objects.requireNonNull(modes, "modes must not be null");
        this.sealed = Objects.requireNonNull(sealed, "sealed must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
        this.effects = Objects.requireNonNull(effects, "effects must not be null");
        this.effectPlayer = Objects.requireNonNull(effectPlayer, "effectPlayer must not be null");
    }

    /** Starts the beat. Closing what it returns stops it. */
    public AutoCloseable start() {
        return scheduler.repeatGlobal(this::round, Duration.ofSeconds(1), Duration.ofSeconds(1));
    }

    private void round() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            scheduler.onEntity(PlayerUuid.of(player.getUniqueId()), () -> check(player));
        }
    }

    /** Sets the player up for where they stand, on their own thread. Returns the mode they are in. */
    public GameMode check(Player player) {
        Location at = player.getLocation();
        Optional<Island> island = at == null ? Optional.empty() : islands.findIslandAt(at);
        boolean onPlot = island.isPresent() && service.isPlot(island.get().id());
        if (onPlot) {
            sky(player, skies.of(island.get().id()));
        } else {
            sky(player, PlotSky.WORLD);
        }
        if (!onPlot) {
            if (sealed.unseal(player)) {
                messages.send(player, "brix.left");
                effectPlayer.fire(effects, "brix-plot-left", player);
            }
            return player.getGameMode();
        }
        GameMode now = player.getGameMode();
        if (!sealed.isSealed(player)) {
            if (now == GameMode.SPECTATOR
                    || (!modes.keepPermission().isEmpty() && player.hasPermission(modes.keepPermission()))) {
                return now;
            }
            if (!sealed.seal(player)) {
                // Not sealed, so not given the place's mode: at the join the session has not yet put the
                // player's own things on them, and creative before the seal would be sealed as theirs.
                return now;
            }
            messages.send(player, "brix.entered");
            effectPlayer.fire(effects, "brix-plot-entered", player);
        }
        if (now == GameMode.SPECTATOR) {
            return now;
        }
        GameMode wanted = isTeam(island.get(), player) ? modes.build() : modes.visit();
        if (now != wanted) {
            player.setGameMode(wanted);
        }
        return wanted;
    }

    /**
     * Shows the player a plot's sky: its time and weather where it set them, the world's where it did not. Only a
     * time or weather this class set is given back to the world, so another's is left alone.
     */
    private void sky(Player player, PlotSky sky) {
        UUID id = player.getUniqueId();
        boolean ours = skied.contains(id);
        if (sky.time() != PlotSky.WORLD_TIME) {
            if (player.isPlayerTimeRelative() || player.getPlayerTimeOffset() != sky.time()) {
                player.setPlayerTime(sky.time(), false);
            }
        } else if (ours && !player.isPlayerTimeRelative()) {
            player.resetPlayerTime();
        }
        WeatherType wanted =
                switch (sky.weather()) {
                    case CLEAR -> WeatherType.CLEAR;
                    case RAIN -> WeatherType.DOWNFALL;
                    case WORLD -> null;
                };
        if (wanted != null) {
            if (player.getPlayerWeather() != wanted) {
                player.setPlayerWeather(wanted);
            }
        } else if (ours && player.getPlayerWeather() != null) {
            player.resetPlayerWeather();
        }
        if (sky.isWorld()) {
            skied.remove(id);
        } else {
            skied.add(id);
        }
    }

    /** Gives a player leaving the server what they came with, so it is what the server saves. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        sealed.unseal(event.getPlayer());
        if (skied.remove(event.getPlayer().getUniqueId())) {
            event.getPlayer().resetPlayerTime();
            event.getPlayer().resetPlayerWeather();
        }
    }

    /** Sets up a player coming back at once, rather than on the next beat. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        check(event.getPlayer());
    }

    private static boolean isTeam(Island island, Player player) {
        PlayerUuid who = PlayerUuid.of(player.getUniqueId());
        return island.members().values().stream()
                .anyMatch(member -> member.playerUuid().equals(who));
    }
}
