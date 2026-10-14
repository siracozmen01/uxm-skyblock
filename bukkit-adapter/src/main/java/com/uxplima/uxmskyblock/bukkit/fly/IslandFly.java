package com.uxplima.uxmskyblock.bukkit.fly;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.ToIntBiFunction;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataType;

import com.uxplima.uxmskyblock.bukkit.arrival.Arrival;
import com.uxplima.uxmskyblock.bukkit.arrival.ArrivalObserver;
import com.uxplima.uxmskyblock.bukkit.config.IslandFlyConfiguration;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandPermission;
import com.uxplima.uxmskyblock.core.domain.upgrade.UpgradeId;

/**
 * {@code /is fly}: a member flies on an island they belong to, and is set down on leaving it.
 *
 * <p>Only the flight this gives is taken back. A player who flies in creative or by another plugin is never set
 * down here, since nothing here gave them their wings. A walk off the island is seen by its move; a teleport,
 * which Folia announces by no event, by the arrival watch.
 */
public final class IslandFly implements Listener, ArrivalObserver {

    /**
     * The mark of wings this gave, kept on the player. The game saves a player's flight with them, so a server that
     * stopped without a quit, or a plugin taken off, would leave wings nothing here remembers to take back; a player
     * who joins still marked is set down.
     */
    static final NamespacedKey MARK = new NamespacedKey("uxmskyblock", "island-fly");

    /**
     * The answer of an island whose upgrades are not known yet, and the line that asks to try again: at taking off
     * it refuses, and in the air it keeps a flier up.
     */
    private static final String UNREAD = "fly.reading";

    /** How long after being set down a player's fall is still the one spared. */
    static final Duration SPARED_FOR = Duration.ofSeconds(30);

    private final IslandFlyConfiguration config;
    private final Function<Location, Optional<Island>> islandAt;
    private final Function<UUID, Optional<ProfileId>> activeProfile;
    private final ToIntBiFunction<IslandId, UpgradeId> tierOf;
    private final Messages messages;
    private final BiConsumer<String, Player> effects;
    private final Clock clock;

    /** The players flying on wings this gave them. */
    private final Set<UUID> flying = ConcurrentHashMap.newKeySet();

    private final Map<UUID, Instant> spared = new ConcurrentHashMap<>();

    /**
     * @param tierOf the tier of an upgrade an island holds, zero for none, or below zero while it is not known
     * @param effects fires an interaction of {@code modules/effects.conf} for a player
     */
    public IslandFly(
            IslandFlyConfiguration config,
            Function<Location, Optional<Island>> islandAt,
            Function<UUID, Optional<ProfileId>> activeProfile,
            ToIntBiFunction<IslandId, UpgradeId> tierOf,
            Messages messages,
            BiConsumer<String, Player> effects,
            Clock clock) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.islandAt = Objects.requireNonNull(islandAt, "islandAt must not be null");
        this.activeProfile = Objects.requireNonNull(activeProfile, "activeProfile must not be null");
        this.tierOf = Objects.requireNonNull(tierOf, "tierOf must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
        this.effects = Objects.requireNonNull(effects, "effects must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /** Whether the player is flying on wings this gave them. */
    public boolean flies(Player player) {
        return flying.contains(player.getUniqueId());
    }

    /** {@code /is fly}: takes the player up where they may fly, or sets them down if they fly by it already. */
    public void toggle(Player player) {
        Objects.requireNonNull(player, "player must not be null");
        if (flying.remove(player.getUniqueId())) {
            ground(player);
            messages.send(player, "fly.off");
            fireMilestone("island-fly-off", player);
            return;
        }
        Location here = player.getLocation();
        Optional<String> refused = here == null ? Optional.of("fly.not_here") : refusal(player, here);
        if (refused.isPresent()) {
            messages.send(player, refused.get());
            return;
        }
        if (player.getAllowFlight()) {
            messages.send(player, "fly.already");
            return;
        }
        flying.add(player.getUniqueId());
        player.getPersistentDataContainer().set(MARK, PersistentDataType.BYTE, (byte) 1);
        player.setAllowFlight(true);
        player.setFlying(true);
        messages.send(player, "fly.on");
        fireMilestone("island-fly-on", player);
    }

    /** Why the player may not fly at {@code at}, as the key of the line that says so, or nothing when they may. */
    Optional<String> refusal(Player player, Location at) {
        if (!config.enabled()) {
            return Optional.of("fly.disabled");
        }
        if (!config.permission().isEmpty() && !player.hasPermission(config.permission())) {
            return Optional.of("fly.no_permission");
        }
        Optional<Island> island = islandAt.apply(at);
        Optional<ProfileId> profile = activeProfile.apply(player.getUniqueId());
        if (island.isEmpty()
                || profile.isEmpty()
                || (!island.get().isMember(profile.get()) && !island.get().isOwner(profile.get()))) {
            return Optional.of("fly.not_here");
        }
        // The owner always may: an island kept before the permission existed holds roles without it.
        if (!island.get().isOwner(profile.get())
                && !island.get().hasPermission(profile.get(), IslandPermission.ISLAND_FLY)) {
            return Optional.of("fly.role");
        }
        UpgradeId upgrade = config.requiredUpgrade();
        if (upgrade != null) {
            int tier = tierOf.applyAsInt(island.get().id(), upgrade);
            if (tier < 0) {
                return Optional.of(UNREAD);
            }
            if (tier < 1) {
                return Optional.of("fly.upgrade");
            }
        }
        return Optional.empty();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (!flying.contains(player.getUniqueId()) || !event.hasChangedBlock()) {
            return;
        }
        check(player, event.getTo());
    }

    @Override
    public void arrived(Arrival arrival) {
        if (flying.contains(arrival.player().getUniqueId())) {
            check(arrival.player(), arrival.to());
        }
    }

    /** Sets the player down if they may not fly where they now are. */
    private void check(Player player, Location at) {
        Optional<String> refused = refusal(player, at);
        // An island whose upgrades are still being read keeps the flier up: a purchase forgets them for a moment.
        if (refused.isEmpty() || refused.get().equals(UNREAD) || !flying.remove(player.getUniqueId())) {
            return;
        }
        ground(player);
        if (config.spareFall()) {
            spared.put(player.getUniqueId(), clock.instant());
        }
        messages.send(player, "fly.lost");
        fireMilestone("island-fly-lost", player);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFall(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.FALL || !(event.getEntity() instanceof Player player)) {
            return;
        }
        Instant at = spared.remove(player.getUniqueId());
        if (at != null && !clock.instant().isAfter(at.plus(SPARED_FOR))) {
            event.setCancelled(true);
        }
    }

    /** A player who joins with the mark of wings given before is set down: nothing here gave them this time. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!flying.contains(player.getUniqueId())
                && player.getPersistentDataContainer().has(MARK)) {
            ground(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        spared.remove(player.getUniqueId());
        if (flying.remove(player.getUniqueId())) {
            ground(player);
        }
    }

    /** Takes back the wings this gave, unless the player's game mode gives them their own. */
    private static void ground(Player player) {
        player.getPersistentDataContainer().remove(MARK);
        GameMode mode = player.getGameMode();
        if (mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR) {
            return;
        }
        player.setFlying(false);
        player.setAllowFlight(false);
    }

    private void fireMilestone(String interaction, Player player) {
        effects.accept(interaction, player);
    }
}
