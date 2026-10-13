package com.uxplima.uxmskyblock.bukkit.tradewinds;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import com.uxplima.uxmskyblock.bukkit.config.TradeWindsPiracy;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffectPlayer;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffects;
import com.uxplima.uxmskyblock.bukkit.i18n.DurationText;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.session.ActiveSession;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.tradewinds.Piracy;
import com.uxplima.uxmskyblock.core.application.tradewinds.PlunderPort.Plunder;
import com.uxplima.uxmskyblock.core.application.tradewinds.VesselService;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandMember;
import com.uxplima.uxmskyblock.core.domain.island.IslandPermission;
import com.uxplima.uxmskyblock.core.domain.result.Result;
import org.jspecify.annotations.Nullable;

/**
 * {@code /is plunder}: a player who crews a vessel at sea, standing on another vessel at sea off its protected deck,
 * takes part of that vessel's hold into their own. Off unless the operator turns piracy on.
 *
 * <p>Only a member whose role steers their vessel plunders with it. Every plunder is written down and said in the
 * console, and both crews online are told, each firing its effect list.
 */
public final class Boarding {

    private static final Logger LOGGER = Logger.getLogger(Boarding.class.getName());

    private final Piracy piracy;
    private final TradeWindsPiracy config;
    private final VesselService vessels;
    private final Function<Location, Optional<Island>> islandAt;
    private final Function<ProfileId, Optional<IslandId>> islandOfProfile;
    private final Function<IslandId, Optional<Island>> islandOf;
    private final CargoHolds.Sessions sessions;
    private final Function<UUID, @Nullable Player> online;
    private final Function<UUID, String> names;
    private final SchedulerPort scheduler;
    private final Messages messages;
    private volatile @Nullable InteractionEffectPlayer effectPlayer;
    private volatile InteractionEffects effects = InteractionEffects.none();

    /**
     * @param islandOfProfile the island a profile plays on, read from storage
     * @param islandOf an island, read from storage
     * @param names the name a player goes by
     */
    @SuppressWarnings("TooManyParameters")
    public Boarding(
            Piracy piracy,
            TradeWindsPiracy config,
            VesselService vessels,
            Function<Location, Optional<Island>> islandAt,
            Function<ProfileId, Optional<IslandId>> islandOfProfile,
            Function<IslandId, Optional<Island>> islandOf,
            CargoHolds.Sessions sessions,
            Function<UUID, @Nullable Player> online,
            Function<UUID, String> names,
            SchedulerPort scheduler,
            Messages messages) {
        this.piracy = Objects.requireNonNull(piracy, "piracy");
        this.config = Objects.requireNonNull(config, "config");
        this.vessels = Objects.requireNonNull(vessels, "vessels");
        this.islandAt = Objects.requireNonNull(islandAt, "islandAt");
        this.islandOfProfile = Objects.requireNonNull(islandOfProfile, "islandOfProfile");
        this.islandOf = Objects.requireNonNull(islandOf, "islandOf");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.online = Objects.requireNonNull(online, "online");
        this.names = Objects.requireNonNull(names, "names");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.messages = Objects.requireNonNull(messages, "messages");
    }

    /** Hands the boarding what {@code modules/effects.conf} fires for both crews. */
    public void useEffects(InteractionEffectPlayer player, InteractionEffects written) {
        this.effects = Objects.requireNonNull(written, "written");
        this.effectPlayer = Objects.requireNonNull(player, "player");
    }

    /** What a plunder that went through took, and from and for whom. */
    private record Taken(Plunder plunder, Island raider, Island target) {}

    /** {@code player} plunders the vessel they stand on, on their thread. */
    public void plunder(Player player) {
        if (!config.enabled()) {
            messages.send(player, "tradewinds.piracy.off");
            return;
        }
        Location at = player.getLocation();
        Optional<Island> target = at == null ? Optional.empty() : islandAt.apply(at);
        if (at == null || target.isEmpty() || !vessels.isVessel(target.get().id())) {
            messages.send(player, "tradewinds.piracy.no_target");
            return;
        }
        ActiveSession session = sessions.session(player.getUniqueId());
        if (session == null) {
            messages.send(player, "tradewinds.hold.busy");
            return;
        }
        ProfileId profile = session.activeProfileId();
        if (target.get().isMember(profile)) {
            messages.send(player, "tradewinds.piracy.own");
            return;
        }
        long dx = at.getBlockX() - (long) target.get().bounds().centerX();
        long dz = at.getBlockZ() - (long) target.get().bounds().centerZ();
        if (dx * dx + dz * dz <= (long) config.deckRadius() * config.deckRadius()) {
            messages.send(player, "tradewinds.piracy.on_deck");
            return;
        }
        Island boarded = target.get();
        PlayerUuid who = PlayerUuid.of(player.getUniqueId());
        scheduler.async(() -> {
            Result<Taken, String> done;
            try {
                done = take(profile, boarded);
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "A TradeWinds plunder by " + who + " failed", e);
                done = Result.err("tradewinds.market.busy");
            }
            Result<Taken, String> answer = done;
            scheduler.onEntity(who, () -> told(player, answer));
        });
    }

    private Result<Taken, String> take(ProfileId profile, Island target) {
        Optional<Island> raider = islandOfProfile.apply(profile).flatMap(islandOf);
        if (raider.isEmpty() || !vessels.isVessel(raider.get().id())) {
            return Result.err("tradewinds.piracy.no_vessel");
        }
        if (!raider.get().isOwner(profile) && !raider.get().hasPermission(profile, IslandPermission.SETTINGS_MODIFY)) {
            return Result.err("tradewinds.hold.not_allowed");
        }
        Result<Plunder, String> plundered = piracy.plunder(
                raider.get().id(),
                target.id(),
                (hold, own, slots) -> HoldGoods.plunder(hold, own, slots, config.share()));
        if (!plundered.isOk()) {
            return Result.err(plundered.errorOrThrow());
        }
        Plunder plunder = plundered.orElseThrow();
        LOGGER.info(() -> "TradeWinds plunder: the crew of " + raider.get().id() + " took " + plunder.items()
                + " items from the hold of " + target.id() + ".");
        return Result.ok(new Taken(plunder, raider.get(), target));
    }

    private void told(Player player, Result<Taken, String> answer) {
        if (!answer.isOk()) {
            messages.send(
                    player,
                    answer.errorOrThrow(),
                    Placeholder.unparsed("time", DurationText.of(messages, player, config.cooldown())));
            return;
        }
        Taken taken = answer.orElseThrow();
        String count = Integer.toString(taken.plunder().items());
        Map<String, String> says = Map.of("items", count);
        TagResolver[] toRaiders = {
            Placeholder.unparsed("count", count),
            Placeholder.unparsed(
                    "vessel", names.apply(taken.target().ownerPlayerUuid().value()))
        };
        TagResolver[] toTarget = {
            Placeholder.unparsed("count", count),
            Placeholder.unparsed(
                    "vessel", names.apply(taken.raider().ownerPlayerUuid().value()))
        };
        crew(taken.raider(), member -> fire(member, "vessel-plunder", "tradewinds.piracy.taken", says, toRaiders));
        crew(taken.target(), member -> fire(member, "vessel-plundered", "tradewinds.piracy.robbed", says, toTarget));
    }

    private void fire(Player player, String interaction, String key, Map<String, String> says, TagResolver[] tags) {
        messages.send(player, key, tags);
        InteractionEffectPlayer fires = effectPlayer;
        if (fires != null) {
            fires.fire(effects, interaction, player, says);
        }
    }

    private void crew(Island island, java.util.function.Consumer<Player> then) {
        for (IslandMember member : island.members().values()) {
            UUID uuid = member.playerUuid().value();
            Player player = online.apply(uuid);
            if (player != null) {
                scheduler.onEntity(PlayerUuid.of(uuid), () -> then.accept(player));
            }
        }
    }
}
