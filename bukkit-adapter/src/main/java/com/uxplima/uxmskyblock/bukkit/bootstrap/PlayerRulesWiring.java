package com.uxplima.uxmskyblock.bukkit.bootstrap;

import java.util.Objects;

import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.core.application.lifecycle.LifecycleService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.persistence.bootstrap.PersistenceBootstrap;
import org.jspecify.annotations.Nullable;

/**
 * What happens to players on islands: the operator's lifecycle rules on a leave, a kick, a death and a reset, the
 * watch on where players arrive, flight, the crop, spawner and drop rates and the effects an island pays for, and the
 * payout of a finished mission. Built by {@link GameplayWiring} once the economy and the creation path are.
 */
final class PlayerRulesWiring {

    private final com.uxplima.uxmskyblock.bukkit.lifecycle.PlayerLifecycle playerLifecycle;
    private final com.uxplima.uxmskyblock.bukkit.arrival.ArrivalWatch arrivalWatch;
    private final com.uxplima.uxmskyblock.bukkit.fly.IslandFly islandFly;
    private final com.uxplima.uxmskyblock.bukkit.islandeffect.IslandEffects islandEffects;
    private final com.uxplima.uxmskyblock.bukkit.upgrade.IslandRates islandRates;
    private @Nullable AutoCloseable effectRounds;
    private @Nullable AutoCloseable arrivalRounds;

    PlayerRulesWiring(
            ConfigurationWiring config,
            PersistenceBootstrap persistence,
            AuthorityWiring authority,
            SchedulerPort scheduler,
            IslandProtectionListener protectionListener,
            EconomicWiring economic,
            GameplayCreationWiring creation) {
        Objects.requireNonNull(config, "config must not be null");
        // What a leave, a kick, a death and a reset do, as the operator's lifecycle rules say.
        this.playerLifecycle = new com.uxplima.uxmskyblock.bukkit.lifecycle.PlayerLifecycle(
                new LifecycleService(
                        config.lifecycleConfig().policy(),
                        persistence.lifecycleOwedEffectsPort(),
                        creation.gameModeHierarchyService()::modeOf,
                        creation.gameModeHierarchyService()::knownModeOf,
                        authority.profileTypes()::of,
                        authority.profileTypes()::known),
                scheduler,
                authority.sessionCoordinator()::activeProfile);
        authority.sessionCoordinator().whenSessionActive(this.playerLifecycle::onSessionActive);
        // Folia announces no teleport and no respawn; every rule about where a player arrives listens here.
        this.arrivalWatch = new com.uxplima.uxmskyblock.bukkit.arrival.ArrivalWatch(
                scheduler,
                () -> org.bukkit.Bukkit.getWorlds().getFirst().getSpawnLocation(),
                java.time.Clock.systemUTC());
        // Both run on player threads, so neither may wait on the database for a tier.
        com.uxplima.uxmskyblock.bukkit.upgrade.CachedTiers cachedTiers =
                new com.uxplima.uxmskyblock.bukkit.upgrade.CachedTiers(economic.upgradeService());
        com.uxplima.uxmskyblock.bukkit.effect.InteractionEffectPlayer flyEffects =
                new com.uxplima.uxmskyblock.bukkit.effect.InteractionEffectPlayer(scheduler, config.messages());
        this.islandFly = new com.uxplima.uxmskyblock.bukkit.fly.IslandFly(
                config.flyConfig(),
                protectionListener::findIslandAt,
                authority.sessionCoordinator()::activeProfile,
                cachedTiers::bought,
                config.messages(),
                (interaction, player) -> flyEffects.fire(config.effectsConfig(), interaction, player),
                java.time.Clock.systemUTC());

        com.uxplima.uxmskyblock.bukkit.booster.IslandBoosterListener boosters = economic.boosterListener();
        this.islandRates = new com.uxplima.uxmskyblock.bukkit.upgrade.IslandRates(
                protectionListener::findIslandAt,
                cachedTiers::bought,
                economic.upgradeService()::definitions,
                boosters == null ? (island, kind) -> 1.0 : boosters::cachedMultiplier,
                scheduler,
                () -> java.util.concurrent.ThreadLocalRandom.current().nextDouble());
        // A finished mission's reward was never paid: the service was built with nothing to pay it.
        boolean missionBoosters = config.moduleSettings().isModuleEnabled("boosters");
        com.uxplima.uxmskyblock.core.application.booster.IslandBoosterService missionBoost = economic.boosterService();
        creation.missionService()
                .useRewards(new com.uxplima.uxmskyblock.bukkit.mission.MissionPayout(
                        economic.bankService(),
                        persistence.islandStoragePort(),
                        config.nodeConfig().nodeId(),
                        island -> missionBoosters
                                ? missionBoost.getEffectiveMultiplier(
                                        island,
                                        com.uxplima.uxmskyblock.core.domain.booster.BoosterCategory.MISSION_REWARDS,
                                        java.time.Instant.now())
                                : 1.0,
                        scheduler,
                        player -> java.util.Optional.ofNullable(org.bukkit.Bukkit.getOfflinePlayer(player.value())
                                .getName()),
                        line -> org.bukkit.Bukkit.dispatchCommand(org.bukkit.Bukkit.getConsoleSender(), line)));

        this.islandEffects = new com.uxplima.uxmskyblock.bukkit.islandeffect.IslandEffects(
                config.islandEffectsConfig(),
                protectionListener::findIslandAt,
                authority.sessionCoordinator()::activeProfile,
                economic.upgradeService()::definitions,
                cachedTiers::bought,
                scheduler,
                org.bukkit.Bukkit::getOnlinePlayers);
    }

    /** The crop, spawner and drop rates an island pays for, at work. */
    public com.uxplima.uxmskyblock.bukkit.upgrade.IslandRates islandRates() {
        return islandRates;
    }

    /** {@code /is fly}, and the watch that sets a flier down off their island. */
    public com.uxplima.uxmskyblock.bukkit.fly.IslandFly islandFly() {
        return islandFly;
    }

    /** What carries out the operator's lifecycle rules on players. */
    public com.uxplima.uxmskyblock.bukkit.arrival.ArrivalWatch arrivalWatch() {
        return arrivalWatch;
    }

    /** Starts looking at where players are. Called once every rule is in. */
    public void startArrivals() {
        if (arrivalRounds == null) {
            arrivalRounds = arrivalWatch.start();
        }
        if (effectRounds == null) {
            effectRounds = islandEffects.start();
        }
    }

    /** Stops looking at where players are. */
    public void stopArrivals() {
        AutoCloseable effects = effectRounds;
        effectRounds = null;
        if (effects != null) {
            try {
                effects.close();
            } catch (Exception e) {
                java.util.logging.Logger.getLogger(PlayerRulesWiring.class.getName())
                        .log(java.util.logging.Level.WARNING, "The island effects did not stop cleanly", e);
            }
        }
        AutoCloseable rounds = arrivalRounds;
        arrivalRounds = null;
        if (rounds != null) {
            try {
                rounds.close();
            } catch (Exception e) {
                java.util.logging.Logger.getLogger(PlayerRulesWiring.class.getName())
                        .log(java.util.logging.Level.WARNING, "The arrival watch did not stop cleanly", e);
            }
        }
    }

    public com.uxplima.uxmskyblock.bukkit.lifecycle.PlayerLifecycle playerLifecycle() {
        return playerLifecycle;
    }
}
