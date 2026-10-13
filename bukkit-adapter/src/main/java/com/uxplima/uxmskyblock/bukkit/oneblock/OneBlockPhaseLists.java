package com.uxplima.uxmskyblock.bukkit.oneblock;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

import org.bukkit.entity.Player;

import com.uxplima.uxmlib.condition.action.ActionList;
import com.uxplima.uxmskyblock.bukkit.config.OneBlockConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.OneBlockLists;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffectPlayer;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffects;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandMember;
import org.jspecify.annotations.Nullable;

/**
 * Runs what a OneBlock phase file writes for when the phase begins, finishes or first finishes: its breaker list once
 * for the player whose break it was, and its members list for every member of the island who is online, each on
 * their own thread.
 */
public final class OneBlockPhaseLists {

    private final Supplier<OneBlockConfiguration> config;
    private final SchedulerPort scheduler;
    private final Function<UUID, @Nullable Player> online;

    /** @param online the player of a uuid when they are on this server */
    public OneBlockPhaseLists(
            Supplier<OneBlockConfiguration> config, SchedulerPort scheduler, Function<UUID, @Nullable Player> online) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.online = Objects.requireNonNull(online, "online must not be null");
    }

    /** Whether a break of {@code phase} is lucky, for a roll from 0 to 1: as often as its file's chance says. */
    public boolean lucky(String phase, double roll) {
        java.util.OptionalDouble chance = config.get().luckOf(phase);
        return chance.isPresent() && roll < chance.getAsDouble();
    }

    /**
     * Runs the lists {@code phase} writes for {@code event}, on the breaker's thread. Answers whether the phase wrote
     * any, so the caller runs the shipped list of modules/effects.conf when it wrote none.
     */
    public boolean run(
            InteractionEffectPlayer effects,
            String phase,
            String event,
            Island island,
            Player breaker,
            Map<String, String> says) {
        java.util.Optional<OneBlockLists> written = config.get().listsOf(phase, event);
        if (written.isEmpty()) {
            return false;
        }
        String interaction = "oneblock phase " + phase + ", " + event;
        fire(effects, interaction, written.get().breaker(), breaker, says);
        ActionList members = written.get().members();
        if (!members.actions().isEmpty()) {
            for (IslandMember member : island.members().values()) {
                UUID uuid = member.playerUuid().value();
                Player player = online.apply(uuid);
                if (player != null) {
                    scheduler.onEntity(uuid, () -> fire(effects, interaction, members, player, says));
                }
            }
        }
        return true;
    }

    private static void fire(
            InteractionEffectPlayer effects,
            String interaction,
            ActionList list,
            Player player,
            Map<String, String> says) {
        effects.fire(new InteractionEffects(Map.of(interaction, list)), interaction, player, says);
    }
}
