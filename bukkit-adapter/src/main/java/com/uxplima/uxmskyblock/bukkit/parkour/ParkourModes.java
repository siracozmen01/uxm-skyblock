package com.uxplima.uxmskyblock.bukkit.parkour;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import com.uxplima.uxmskyblock.bukkit.config.ParkourConfiguration;
import com.uxplima.uxmskyblock.bukkit.creative.SealedInventory;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffectPlayer;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffects;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.core.application.parkour.ParkourService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.island.Island;

/**
 * How a course is played: its own team builds it in one game mode, and everybody running it, and
 * everybody else on it, plays in another. Whatever a player brings to a course is kept aside while they
 * are on it: they have it back, and their mode with it, off the course, off the server, or after the
 * server stopped, and nothing the team made in creative leaves the course.
 *
 * <p>A run is run empty handed: the course's own inventory is emptied as it starts, so a builder does not
 * run their own course with what creative gave them.
 *
 * <p>A beat hands each player to their own thread, where their mode is set; a run that starts sets it
 * at once rather than waiting for the beat.
 */
public final class ParkourModes implements Listener {

    private final ParkourService service;
    private final IslandProtectionListener islands;
    private final SchedulerPort scheduler;
    private final ParkourConfiguration.Modes modes;
    private final Predicate<Player> running;
    private final SealedInventory sealed;
    private final Messages messages;
    private final InteractionEffects effects;
    private final InteractionEffectPlayer effectPlayer;

    /** @param running whether the player has a run under way */
    public ParkourModes(
            ParkourService service,
            IslandProtectionListener islands,
            SchedulerPort scheduler,
            ParkourConfiguration.Modes modes,
            Predicate<Player> running,
            SealedInventory sealed,
            Messages messages,
            InteractionEffects effects,
            InteractionEffectPlayer effectPlayer) {
        this.service = Objects.requireNonNull(service, "service must not be null");
        this.islands = Objects.requireNonNull(islands, "islands must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.modes = Objects.requireNonNull(modes, "modes must not be null");
        this.running = Objects.requireNonNull(running, "running must not be null");
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

    /** Sets the player's mode for where they stand, on their own thread. Returns the mode they are in. */
    public GameMode check(Player player) {
        Location at = player.getLocation();
        Optional<Island> island = at == null ? Optional.empty() : islands.findIslandAt(at);
        if (island.isEmpty() || !service.isCourse(island.get().id())) {
            if (sealed.unseal(player)) {
                messages.send(player, "parkour.given_back");
                effectPlayer.fire(effects, "parkour-course-left", player);
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
            messages.send(player, "parkour.kept");
            effectPlayer.fire(effects, "parkour-course-entered", player);
        }
        if (now == GameMode.SPECTATOR) {
            return now;
        }
        GameMode wanted = running.test(player) || !isTeam(island.get(), player) ? modes.play() : modes.build();
        if (now != wanted) {
            player.setGameMode(wanted);
        }
        return wanted;
    }

    /** Sets the mode of a runner whose run just started, and empties the course's inventory they hold. */
    public GameMode startRun(Player runner) {
        GameMode mode = check(runner);
        if (sealed.isSealed(runner)) {
            runner.getInventory().clear();
        }
        return mode;
    }

    /** Gives a player leaving the server what they came with, so it is what the server saves. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        sealed.unseal(event.getPlayer());
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
