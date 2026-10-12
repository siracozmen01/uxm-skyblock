package com.uxplima.uxmskyblock.bukkit.mission;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerFishEvent;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.mission.IslandMissionService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.mission.MissionDefinition;
import com.uxplima.uxmskyblock.core.domain.mission.MissionProgress;
import com.uxplima.uxmskyblock.core.domain.mission.MissionTriggerType;

/**
 * Event-driven zero-friction listener intercepting player actions and updating
 * mission progress asynchronously without stalling server region tick loops.
 */
public final class IslandMissionListener implements Listener, ModeMissions {

    /**
     * What this interaction fires, as the operator wrote it.
     *
     * <p>The sound was written into this file, so a server that wanted a different note, or none,
     * or a title as well, had nowhere to say so. A node built without a list fires nothing.
     */
    private volatile com.uxplima.uxmskyblock.bukkit.effect.@org.jspecify.annotations.Nullable InteractionEffects
            effects;

    private volatile com.uxplima.uxmskyblock.bukkit.effect.@org.jspecify.annotations.Nullable InteractionEffectPlayer
            effectPlayer;

    /** Tells this rule what the operator wrote for it. */
    public void useEffects(
            com.uxplima.uxmskyblock.bukkit.effect.@org.jspecify.annotations.Nullable InteractionEffects effects,
            com.uxplima.uxmskyblock.bukkit.effect.@org.jspecify.annotations.Nullable InteractionEffectPlayer player) {
        this.effects = effects;
        this.effectPlayer = player;
    }

    private static final Set<Material> CROPS = Set.of(
            Material.WHEAT,
            Material.CARROTS,
            Material.POTATOES,
            Material.BEETROOTS,
            Material.NETHER_WART,
            Material.SUGAR_CANE,
            Material.CACTUS,
            Material.MELON,
            Material.PUMPKIN);

    private final IslandMissionService missionService;
    private final IslandStoragePort islandStoragePort;
    private final PlayerSessionCoordinator sessionCoordinator;
    private final SchedulerPort schedulerPort;
    private final ConcurrentMap<ProfileId, IslandId> profileIslandCache = new ConcurrentHashMap<>();
    private final Messages messages;

    public IslandMissionListener(
            IslandMissionService missionService,
            IslandStoragePort islandStoragePort,
            PlayerSessionCoordinator sessionCoordinator,
            SchedulerPort schedulerPort,
            Messages messages) {
        this.missionService = Objects.requireNonNull(missionService, "missionService must not be null");
        this.islandStoragePort = Objects.requireNonNull(islandStoragePort, "islandStoragePort must not be null");
        this.sessionCoordinator = Objects.requireNonNull(sessionCoordinator, "sessionCoordinator must not be null");
        this.schedulerPort = Objects.requireNonNull(schedulerPort, "schedulerPort must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Material mat = event.getBlock().getType();
        boolean isCrop = CROPS.contains(mat);
        MissionTriggerType trigger = isCrop ? MissionTriggerType.CROP_HARVEST : MissionTriggerType.BLOCK_BREAK;
        String target = normalizeMaterialName(mat);

        dispatchTrigger(player, trigger, target, 1L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        String target = normalizeMaterialName(event.getBlock().getType());
        dispatchTrigger(player, MissionTriggerType.BLOCK_PLACE, target, 1L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer == null) {
            return;
        }
        String mobName = event.getEntityType().name().toUpperCase(Locale.ROOT);
        dispatchTrigger(killer, MissionTriggerType.MOB_KILL, mobName, 1L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (event.getState() == PlayerFishEvent.State.CAUGHT_FISH) {
            dispatchTrigger(event.getPlayer(), MissionTriggerType.FISHING, "*", 1L);
        }
    }

    private volatile java.util.function.Function<
                    IslandId, List<com.uxplima.uxmskyblock.core.domain.island.IslandMember>>
            members = island -> List.of();
    private volatile java.util.function.Function<UUID, @org.jspecify.annotations.Nullable Player> online = uuid -> null;

    /** Tells it who is on an island, and which of them is on this server, for the missions an island reaches. */
    public void useMembers(
            java.util.function.Function<IslandId, List<com.uxplima.uxmskyblock.core.domain.island.IslandMember>>
                    members,
            java.util.function.Function<UUID, @org.jspecify.annotations.Nullable Player> online) {
        this.members = Objects.requireNonNull(members, "members must not be null");
        this.online = Objects.requireNonNull(online, "online must not be null");
    }

    /**
     * The island stands at {@code reached} of what {@code trigger} counts, a level or a balance: every member's
     * missions that count it move up to it, and a member here is told of one it finished.
     */
    public void reached(IslandId islandId, MissionTriggerType trigger, long reached) {
        schedulerPort.async(() -> {
            for (com.uxplima.uxmskyblock.core.domain.island.IslandMember member : members.apply(islandId)) {
                List<MissionProgress> updated =
                        missionService.handleReach(islandId, member.profileId(), trigger, reached, Instant.now());
                UUID uuid = member.playerUuid().value();
                for (MissionProgress progress : updated) {
                    if (progress.completed()) {
                        // The player is looked up on their own thread, where they may be touched.
                        schedulerPort.onEntity(new PlayerUuid(uuid), () -> {
                            Player player = online.apply(uuid);
                            if (player != null) {
                                notifyCompletion(player, uuid, progress);
                            }
                        });
                    }
                }
            }
        });
    }

    /** A mode's own event, counted as any other trigger is. */
    @Override
    public void counted(Player player, MissionTriggerType trigger, String target, long amount) {
        dispatchTrigger(player, trigger, target, amount);
    }

    private void dispatchTrigger(Player player, MissionTriggerType trigger, String target, long amount) {
        UUID uuid = player.getUniqueId();
        Optional<ProfileId> optProfile = sessionCoordinator.activeProfile(uuid);
        if (optProfile.isEmpty()) {
            return;
        }
        ProfileId profileId = optProfile.get();
        IslandId cachedIslandId = profileIslandCache.get(profileId);
        if (cachedIslandId != null) {
            schedulerPort.async(() -> executeTrigger(player, uuid, cachedIslandId, profileId, trigger, target, amount));
            return;
        }

        schedulerPort.async(() -> {
            Optional<IslandId> optIsland = islandStoragePort.findIslandIdByProfileId(profileId);
            if (optIsland.isEmpty()) {
                return;
            }
            IslandId islandId = optIsland.get();
            profileIslandCache.put(profileId, islandId);
            executeTrigger(player, uuid, islandId, profileId, trigger, target, amount);
        });
    }

    private void executeTrigger(
            Player player,
            UUID uuid,
            IslandId islandId,
            ProfileId profileId,
            MissionTriggerType trigger,
            String target,
            long amount) {
        List<MissionProgress> updated =
                missionService.handleTrigger(islandId, profileId, trigger, target, amount, Instant.now());
        for (MissionProgress progress : updated) {
            if (progress.completed()) {
                notifyCompletion(player, uuid, progress);
            }
        }
    }

    public void setProfileIsland(ProfileId profileId, IslandId islandId) {
        Objects.requireNonNull(profileId, "profileId must not be null");
        Objects.requireNonNull(islandId, "islandId must not be null");
        profileIslandCache.put(profileId, islandId);
    }

    public void invalidateProfile(ProfileId profileId) {
        Objects.requireNonNull(profileId, "profileId must not be null");
        profileIslandCache.remove(profileId);
    }

    public void clearCache() {
        profileIslandCache.clear();
    }

    @SuppressWarnings("EmptyCatch")
    private void notifyCompletion(Player player, UUID uuid, MissionProgress progress) {
        Optional<MissionDefinition> optDef = missionService.findMission(progress.missionId());
        if (optDef.isEmpty()) {
            return;
        }
        MissionDefinition def = optDef.get();

        schedulerPort.onEntity(new PlayerUuid(uuid), () -> {
            if (!player.isOnline()) {
                return;
            }
            player.sendMessage(messages.render(
                    player,
                    "missions.completed_announce",
                    Placeholder.unparsed("mission", messages.words(player, def.displayName()))));
            try {
                Location loc = player.getLocation();
                com.uxplima.uxmskyblock.bukkit.effect.InteractionEffects written = this.effects;
                com.uxplima.uxmskyblock.bukkit.effect.InteractionEffectPlayer plays = this.effectPlayer;
                if (loc != null && written != null && plays != null) {
                    plays.fire(written, "mission-completed", player);
                }
            } catch (Throwable ignored) {
                // Sound playback failure is non-fatal
            }
        });
    }

    private String normalizeMaterialName(Material mat) {
        String name = mat.name().toUpperCase(Locale.ROOT);
        if (name.equals("CARROTS")) return "CARROT";
        if (name.equals("POTATOES")) return "POTATO";
        if (name.equals("BEETROOTS")) return "BEETROOT";
        return name;
    }
}
