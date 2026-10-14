package com.uxplima.uxmskyblock.bukkit.islandeffect;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.ToIntBiFunction;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import com.uxplima.uxmskyblock.bukkit.config.IslandEffectsConfiguration;
import com.uxplima.uxmskyblock.bukkit.effect.PotionEffectLines;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.upgrade.UpgradeDefinition;
import com.uxplima.uxmskyblock.core.domain.upgrade.UpgradeId;
import com.uxplima.uxmskyblock.core.domain.upgrade.UpgradeTier;

/**
 * Puts the potion effects of an island's effect upgrades on the players standing on it, on a beat.
 *
 * <p>Each effect is put on for a little longer than the beat, so it never flickers while a player stays and wears
 * off soon after they leave; nothing has to take it away. Where two upgrades name one effect, the higher level is
 * worn.
 */
public final class IslandEffects {

    /** How much longer than the beat an effect lasts, in ticks. */
    static final int OVERLAP_TICKS = 40;

    private final IslandEffectsConfiguration config;
    private final Function<Location, Optional<Island>> islandAt;
    private final Function<UUID, Optional<ProfileId>> activeProfile;
    private final Supplier<Map<UpgradeId, UpgradeDefinition>> definitions;
    private final ToIntBiFunction<IslandId, UpgradeId> bought;
    private final SchedulerPort scheduler;
    private final Supplier<? extends Iterable<? extends Player>> online;

    /**
     * @param bought the number of tiers of an upgrade an island has bought
     * @param online the players on the server
     */
    public IslandEffects(
            IslandEffectsConfiguration config,
            Function<Location, Optional<Island>> islandAt,
            Function<UUID, Optional<ProfileId>> activeProfile,
            Supplier<Map<UpgradeId, UpgradeDefinition>> definitions,
            ToIntBiFunction<IslandId, UpgradeId> bought,
            SchedulerPort scheduler,
            Supplier<? extends Iterable<? extends Player>> online) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.islandAt = Objects.requireNonNull(islandAt, "islandAt must not be null");
        this.activeProfile = Objects.requireNonNull(activeProfile, "activeProfile must not be null");
        this.definitions = Objects.requireNonNull(definitions, "definitions must not be null");
        this.bought = Objects.requireNonNull(bought, "bought must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.online = Objects.requireNonNull(online, "online must not be null");
    }

    /** Starts the beat, or nothing when the effects are off. Closing what it returns stops it. */
    public AutoCloseable start() {
        if (!config.enabled()) {
            return () -> {};
        }
        Duration every = Duration.ofSeconds(config.everySeconds());
        return scheduler.repeatGlobal(this::round, every, every);
    }

    private void round() {
        for (Player player : online.get()) {
            scheduler.onEntity(PlayerUuid.of(player.getUniqueId()), () -> wear(player));
        }
    }

    /** Puts on {@code player}, on their own thread, the effects of the island they stand on, and answers them. */
    public Map<PotionEffectType, Integer> wear(Player player) {
        Map<PotionEffectType, Integer> levels = owed(player);
        int ticks = config.everySeconds() * 20 + OVERLAP_TICKS;
        levels.forEach((type, level) -> player.addPotionEffect(new PotionEffect(type, ticks, level - 1, true, false)));
        return levels;
    }

    /** The effects and their levels the island under {@code player} owes them. */
    Map<PotionEffectType, Integer> owed(Player player) {
        Map<PotionEffectType, Integer> levels = new HashMap<>();
        Location at = player.getLocation();
        if (!config.enabled() || at == null) {
            return levels;
        }
        Optional<Island> island = islandAt.apply(at);
        if (island.isEmpty()) {
            return levels;
        }
        Optional<ProfileId> profile = activeProfile.apply(player.getUniqueId());
        boolean member = profile.isPresent()
                && (island.get().isMember(profile.get()) || island.get().isOwner(profile.get()));
        if (!member && !config.visitors()) {
            return levels;
        }
        Map<UpgradeId, UpgradeDefinition> known = definitions.get();
        for (UpgradeId upgrade : config.upgrades()) {
            UpgradeDefinition definition = known.get(upgrade);
            if (definition == null) {
                continue;
            }
            Optional<UpgradeTier> tier = definition.getTier(
                    definition.effectiveTier(bought.applyAsInt(island.get().id(), upgrade)));
            tier.ifPresent(held -> held.properties().forEach((name, level) -> {
                PotionEffectType type = PotionEffectLines.named(name);
                if (type != null && level >= 1) {
                    levels.merge(type, (int) Math.floor(level), Math::max);
                }
            }));
        }
        return levels;
    }
}
