package com.uxplima.uxmskyblock.bukkit.booster;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import com.uxplima.uxmskyblock.bukkit.config.BoosterConfiguration;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.core.application.booster.IslandBoosterService;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.booster.BoosterCategory;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandMember;
import org.jspecify.annotations.Nullable;

/**
 * Inbound Bukkit listener managing booster pause-on-idle lifecycles and in-game multipliers.
 */
public final class IslandBoosterListener implements Listener {

    private final IslandStoragePort islandStoragePort;
    private final IslandBoosterService boosterService;
    private final BoosterConfiguration configuration;
    private final @Nullable PlayerSessionCoordinator sessionCoordinator;
    /**
     * Where the reads go.
     *
     * <p>It used to be optional, and every place that used it carried a branch for its absence that
     * read the database where it stood. Only the tests ever took that branch, so the tests were the
     * only thing exercising a shape production never runs, and three queries on an event thread
     * lived behind it. A listener without somewhere to put its reads has no business existing.
     */
    private final SchedulerPort schedulerPort;

    private final Clock clock;
    /**
     * The island each player's kills are boosted by, and when that was read.
     *
     * <p>It was read once and kept until the player left, so a member kicked from an island kept
     * its experience booster for the rest of their session. It is read again, off the thread the
     * mob died on, once it is as old as {@code boosters.multiplier-cache-ttl}.
     */
    private final Map<UUID, CachedIsland> playerIslandCache = new ConcurrentHashMap<>();

    /** Players whose island is already being read again, so a farm does not queue one read per mob. */
    private final Set<UUID> resolving = ConcurrentHashMap.newKeySet();

    private record CachedIsland(IslandId islandId, Instant readAt) {
        boolean isFreshAt(Instant now, Duration ttl) {
            return fresh(readAt, now, ttl);
        }
    }

    private static boolean fresh(Instant readAt, Instant now, Duration ttl) {
        // An expiry of zero is an operator saying they want no cache, so nothing is ever fresh.
        return !ttl.isZero() && !readAt.plus(ttl).isBefore(now);
    }

    /**
     * The last multiplier read for an island and a category, and when it was read.
     *
     * <p>A mob death has to set the dropped experience before the event returns, so it cannot wait
     * for a query. It used to run one per kill, on the region thread, for every death that dropped
     * experience: a mob farm was a query per mob. This holds the answer for
     * {@code boosters.multiplier-cache-ttl} and refreshes it off the thread the mob died on.
     */
    private final Map<MultiplierKey, CachedMultiplier> multiplierCache = new ConcurrentHashMap<>();

    /** Islands whose refresh is already in flight, so a farm does not queue one task per mob. */
    private final Set<MultiplierKey> refreshing = ConcurrentHashMap.newKeySet();

    private record MultiplierKey(IslandId islandId, BoosterCategory category) {}

    private record CachedMultiplier(double value, Instant readAt) {
        boolean isFreshAt(Instant now, Duration ttl) {
            return fresh(readAt, now, ttl);
        }
    }

    public IslandBoosterListener(
            IslandStoragePort islandStoragePort,
            IslandBoosterService boosterService,
            BoosterConfiguration configuration,
            @Nullable PlayerSessionCoordinator sessionCoordinator,
            SchedulerPort schedulerPort,
            Clock clock) {
        this.islandStoragePort = Objects.requireNonNull(islandStoragePort, "islandStoragePort must not be null");
        this.boosterService = Objects.requireNonNull(boosterService, "boosterService must not be null");
        this.configuration = Objects.requireNonNull(configuration, "configuration must not be null");
        this.sessionCoordinator = sessionCoordinator;
        this.schedulerPort = Objects.requireNonNull(schedulerPort, "schedulerPort must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public IslandBoosterListener(
            IslandStoragePort islandStoragePort,
            IslandBoosterService boosterService,
            BoosterConfiguration configuration,
            @Nullable PlayerSessionCoordinator sessionCoordinator,
            SchedulerPort schedulerPort) {
        this(islandStoragePort, boosterService, configuration, sessionCoordinator, schedulerPort, Clock.systemUTC());
    }

    /**
     * Resumes an island's paused boosters when a member arrives.
     *
     * <p>Called once the player's session is made. It ran on the join event, when the player has no
     * profile yet, so the island was never found and its boosters stayed paused with members on it.
     */
    public void onSessionActive(Player player) {
        if (!configuration.pauseWhenEmpty()) {
            return;
        }

        UUID playerUuid = player.getUniqueId();
        Runnable task = () -> {
            findIslandIdForPlayer(playerUuid).ifPresent(islandId -> {
                playerIslandCache.put(playerUuid, new CachedIsland(islandId, clock.instant()));
                // The count is handed over rather than taken here, so the service can ask it again
                // inside its lock. A join and a quit on one island arrive on this same pool, and
                // whichever of them runs second has to be the one that decides.
                boosterService.followOccupancy(
                        islandId, () -> countOnlineIslandMembers(islandId, null), clock.instant());
            });
        };

        schedulerPort.async(task);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (!configuration.pauseWhenEmpty()) {
            return;
        }

        leftIsland(event.getPlayer().getUniqueId());
    }

    /** A player who switched profile has left the island the old profile belonged to. */
    public void onProfileLeft(Player player, ProfileId left) {
        if (!configuration.pauseWhenEmpty()) {
            return;
        }
        leftIsland(player.getUniqueId());
    }

    private void leftIsland(UUID playerUuid) {
        Runnable task = () -> {
            CachedIsland remembered = playerIslandCache.get(playerUuid);
            IslandId islandId = remembered == null ? null : remembered.islandId();
            if (islandId == null) {
                islandId = findIslandIdForPlayer(playerUuid).orElse(null);
            }
            if (islandId != null) {
                IslandId island = islandId;
                boosterService.followOccupancy(
                        island, () -> countOnlineIslandMembers(island, playerUuid), clock.instant());
            }
            invalidatePlayer(playerUuid);
        };

        schedulerPort.async(task);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        if (event.getDroppedExp() <= 0) {
            return;
        }

        Player killer = event.getEntity().getKiller();
        if (killer == null
                && event.getDamageSource() != null
                && event.getDamageSource().getCausingEntity() instanceof Player p) {
            killer = p;
        }
        if (killer == null) {
            return;
        }

        UUID killerUuid = killer.getUniqueId();
        CachedIsland remembered = playerIslandCache.get(killerUuid);
        if (remembered == null) {
            // Nothing on this path may touch the database. The island is resolved off the thread the
            // mob died on, and this kill goes unboosted: one mob's experience is a cheaper price
            // than a query on a region thread, and the next kill will find the answer waiting.
            resolveIslandLater(killerUuid);
            return;
        }
        if (!remembered.isFreshAt(clock.instant(), configuration.multiplierCacheTtl())) {
            // Read again off this thread, keeping the island it had until the answer is in.
            resolveIslandLater(killerUuid);
        }
        IslandId islandId = remembered.islandId();

        double multiplier = cachedMultiplier(islandId, BoosterCategory.MOB_EXP);
        if (multiplier > 1.0) {
            int originalExp = event.getDroppedExp();
            int boostedExp = (int) Math.round(originalExp * multiplier);
            event.setDroppedExp(boostedExp);
        }
    }

    /**
     * The multiplier as of the last read, refreshing it off this thread when it has gone stale.
     *
     * <p>Returns 1.0 while the first read is still running. A booster is a bonus, and briefly not
     * applying one is a smaller wrong than stalling the region thread on every mob that dies.
     */
    public double cachedMultiplier(IslandId islandId, BoosterCategory category) {
        MultiplierKey key = new MultiplierKey(islandId, category);
        Instant now = clock.instant();
        CachedMultiplier cached = multiplierCache.get(key);
        if (cached != null && cached.isFreshAt(now, configuration.multiplierCacheTtl())) {
            return cached.value();
        }
        refreshLater(key);
        return cached != null ? cached.value() : 1.0;
    }

    /** Reads the multiplier and stores it. Runs on a scheduler thread, never on an event thread. */
    private double refreshNow(MultiplierKey key) {
        double value = boosterService.getEffectiveMultiplier(key.islandId(), key.category(), clock.instant());
        multiplierCache.put(key, new CachedMultiplier(value, clock.instant()));
        return value;
    }

    private void refreshLater(MultiplierKey key) {
        if (!refreshing.add(key)) {
            return;
        }
        schedulerPort.async(() -> {
            try {
                refreshNow(key);
            } finally {
                refreshing.remove(key);
            }
        });
    }

    private void resolveIslandLater(UUID playerUuid) {
        // Built as a value and handed over, the way the join and quit handlers do it, so the one
        // branch that runs it in place is the wiring with no scheduler at all rather than a second
        // shape that has to be read on its own.
        if (!resolving.add(playerUuid)) {
            return;
        }
        Runnable task = () -> {
            try {
                Optional<IslandId> found = findIslandIdForPlayer(playerUuid);
                if (found.isEmpty()) {
                    // Kicked, gone or never on one: nothing boosts this player's kills any more.
                    playerIslandCache.remove(playerUuid);
                    return;
                }
                playerIslandCache.put(playerUuid, new CachedIsland(found.get(), clock.instant()));
                refreshLater(new MultiplierKey(found.get(), BoosterCategory.MOB_EXP));
            } finally {
                resolving.remove(playerUuid);
            }
        };
        schedulerPort.async(task);
    }

    /** Forgets what is remembered about a player, for a profile switch or a quit. */
    public void invalidatePlayer(UUID playerUuid) {
        CachedIsland remembered = playerIslandCache.remove(playerUuid);
        if (remembered != null) {
            multiplierCache.keySet().removeIf(key -> key.islandId().equals(remembered.islandId()));
        }
    }

    public Optional<IslandId> findIslandIdForPlayer(UUID playerUuid) {
        if (sessionCoordinator == null) {
            return Optional.empty();
        }
        Optional<ProfileId> optProfile = sessionCoordinator.activeProfile(playerUuid);
        if (optProfile.isEmpty()) {
            return Optional.empty();
        }
        return islandStoragePort.findIslandIdByProfileId(optProfile.get());
    }

    public int countOnlineIslandMembers(IslandId islandId, @Nullable UUID excludingPlayerUuid) {
        Optional<Island> optIsland = islandStoragePort.findIslandById(islandId);
        if (optIsland.isEmpty()) {
            return 0;
        }

        Island island = optIsland.get();
        int count = 0;

        // Check owner
        UUID ownerUuid = island.ownerPlayerUuid().value();
        if (!ownerUuid.equals(excludingPlayerUuid) && Bukkit.getPlayer(ownerUuid) != null) {
            count++;
        }

        // Check members
        for (IslandMember member : island.members().values()) {
            UUID memberUuid = member.playerUuid().value();
            if (!memberUuid.equals(ownerUuid) && !memberUuid.equals(excludingPlayerUuid)) {
                if (Bukkit.getPlayer(memberUuid) != null) {
                    count++;
                }
            }
        }

        return count;
    }

    public Clock clock() {
        return clock;
    }
}
