package com.uxplima.uxmskyblock.bukkit.boxed;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffectPlayer;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffects;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.core.application.boxed.BoxedService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;

/**
 * A Boxed island's box: it grows when one of the island's players makes an advancement, and in a
 * world Boxed islands are made in nobody builds, breaks, uses or walks where no box reaches.
 *
 * <p>Inside a box the island's own protection answers, as on any island. What this adds is the land
 * between boxes, which on generated terrain is land, not empty air.
 */
public final class BoxedListener implements Listener {

    private static final Logger LOGGER = Logger.getLogger(BoxedListener.class.getName());

    /** How often one player is told they reached the edge, so walking along it is not a flood. */
    private static final long TOLD_EVERY_MILLIS = 2_000;

    private final BoxedService service;
    private final IslandProtectionListener islands;
    private final SchedulerPort scheduler;
    private final Messages messages;
    private final InteractionEffects effects;
    private final InteractionEffectPlayer effectPlayer;
    private volatile com.uxplima.uxmskyblock.bukkit.mission.ModeMissions missions =
            com.uxplima.uxmskyblock.bukkit.mission.ModeMissions.NONE;
    private final Function<UUID, Optional<ProfileId>> activeProfile;
    private final Function<ProfileId, Optional<IslandId>> islandOf;
    private final Consumer<IslandId> boxChanged;
    private final Set<String> boxedWorlds;
    private final String bypassPermission;
    private final Map<UUID, Long> lastTold = new ConcurrentHashMap<>();

    @SuppressWarnings("TooManyParameters")
    public BoxedListener(
            BoxedService service,
            IslandProtectionListener islands,
            SchedulerPort scheduler,
            Messages messages,
            InteractionEffects effects,
            InteractionEffectPlayer effectPlayer,
            Function<UUID, Optional<ProfileId>> activeProfile,
            Function<ProfileId, Optional<IslandId>> islandOf,
            Consumer<IslandId> boxChanged,
            Set<String> boxedWorlds,
            String bypassPermission) {
        this.service = Objects.requireNonNull(service, "service must not be null");
        this.islands = Objects.requireNonNull(islands, "islands must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
        this.effects = Objects.requireNonNull(effects, "effects must not be null");
        this.effectPlayer = Objects.requireNonNull(effectPlayer, "effectPlayer must not be null");
        this.activeProfile = Objects.requireNonNull(activeProfile, "activeProfile must not be null");
        this.islandOf = Objects.requireNonNull(islandOf, "islandOf must not be null");
        this.boxChanged = Objects.requireNonNull(boxChanged, "boxChanged must not be null");
        this.boxedWorlds = Set.copyOf(boxedWorlds);
        this.bypassPermission = Objects.requireNonNull(bypassPermission, "bypassPermission must not be null");
    }

    /** Tells it where a box grown counts, for the missions that ask for it. */
    public void useMissions(com.uxplima.uxmskyblock.bukkit.mission.ModeMissions missions) {
        this.missions = Objects.requireNonNull(missions, "missions must not be null");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        Player player = event.getPlayer();
        if (com.uxplima.uxmskyblock.bukkit.state.PlayerStates.isSwapping(player)) {
            // Handed back as the player crossed into another group of worlds: earned before, counted then.
            return;
        }
        String advancement = event.getAdvancement().getKey().asString();
        if (service.rules().blocksFor(advancement) <= 0) {
            return;
        }
        Optional<ProfileId> profile = activeProfile.apply(player.getUniqueId());
        if (profile.isEmpty()) {
            return;
        }
        UUID playerId = player.getUniqueId();
        scheduler.async(() -> {
            try {
                islandOf.apply(profile.get())
                        .flatMap(islandId -> service.earn(islandId, advancement))
                        .ifPresent(grown -> {
                            boxChanged.accept(grown.islandId());
                            scheduler.onEntity(PlayerUuid.of(playerId), () -> {
                                int across = grown.toRadius() * 2 + 1;
                                messages.send(
                                        player,
                                        "boxed.grew",
                                        Placeholder.unparsed("blocks", Integer.toString(grown.blocks())),
                                        Placeholder.unparsed("size", Integer.toString(across)));
                                effectPlayer.fire(
                                        effects,
                                        "boxed-grew",
                                        player,
                                        java.util.Map.of(
                                                "blocks", Integer.toString(grown.blocks()),
                                                "size", Integer.toString(across)));
                                missions.counted(
                                        player,
                                        com.uxplima.uxmskyblock.core.domain.mission.MissionTriggerType.BOXED_GROW,
                                        "*",
                                        grown.blocks());
                            });
                        });
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, e, () -> "An advancement could not grow a box: " + advancement);
            }
        });
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        refuseOutside(event.getPlayer(), event.getBlock().getLocation(), event);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        refuseOutside(event.getPlayer(), event.getBlock().getLocation(), event);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent event) {
        refuseOutside(
                event.getPlayer(),
                event.getBlockClicked().getRelative(event.getBlockFace()).getLocation(),
                event);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getClickedBlock() != null) {
            refuseOutside(event.getPlayer(), event.getClickedBlock().getLocation(), event);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location to = event.getTo();
        Location from = event.getFrom();
        if (to.getBlockX() == from.getBlockX() && to.getBlockZ() == from.getBlockZ()) {
            return;
        }
        // Stepping out of a box is refused. A player already outside one, arriving from somewhere,
        // is not held in place where they stand.
        if (outsideEveryBox(event.getPlayer(), to) && !outsideEveryBox(event.getPlayer(), from)) {
            event.setCancelled(true);
            tell(event.getPlayer());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastTold.remove(event.getPlayer().getUniqueId());
    }

    private void refuseOutside(Player player, Location at, Cancellable event) {
        if (outsideEveryBox(player, at)) {
            event.setCancelled(true);
            tell(player);
        }
    }

    /** Whether the spot is in a world Boxed islands are made in and no box reaches it. Memory only. */
    boolean outsideEveryBox(Player player, Location at) {
        if (at.getWorld() == null || !boxedWorlds.contains(at.getWorld().getName())) {
            return false;
        }
        if (!bypassPermission.isEmpty() && player.hasPermission(bypassPermission)) {
            return false;
        }
        return islands.findIslandAt(at).isEmpty();
    }

    private void tell(Player player) {
        long now = System.currentTimeMillis();
        Long last = lastTold.get(player.getUniqueId());
        if (last != null && now - last < TOLD_EVERY_MILLIS) {
            return;
        }
        lastTold.put(player.getUniqueId(), now);
        messages.send(player, "boxed.outside");
    }
}
