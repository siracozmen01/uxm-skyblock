package com.uxplima.uxmskyblock.bukkit.chunkblock;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;

import org.bukkit.entity.Player;

import com.uxplima.uxmskyblock.bukkit.config.EffectsConfiguration;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffectPlayer;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffects;
import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkTier;
import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkTiers;

/**
 * What opening a chunk of each grade fires for the player who opened it: the grade's action lines, read once by the
 * family's action engine, in the order the operator wrote them.
 */
public final class ChunkRewards implements BiConsumer<Player, ChunkTier> {

    private final InteractionEffects rewards;
    private final InteractionEffectPlayer player;

    public ChunkRewards(ChunkTiers tiers, InteractionEffectPlayer player) {
        Objects.requireNonNull(tiers, "tiers must not be null");
        this.player = Objects.requireNonNull(player, "player must not be null");
        Map<String, com.uxplima.uxmlib.condition.action.ActionList> byTier = new LinkedHashMap<>();
        for (ChunkTier tier : tiers.all()) {
            byTier.put(
                    tier.id(), EffectsConfiguration.parseAll("the rewards of the grade " + tier.id(), tier.rewards()));
        }
        this.rewards = new InteractionEffects(byTier);
    }

    /** Fires the rewards of {@code tier} for {@code opener}, on the opener's own thread. */
    @Override
    public void accept(Player opener, ChunkTier tier) {
        player.fire(rewards, tier.id(), opener);
    }

    /** How many lines the grade {@code id} fires, the ones the engine could read. */
    int lines(String id) {
        return rewards.of(id).actions().size();
    }
}
