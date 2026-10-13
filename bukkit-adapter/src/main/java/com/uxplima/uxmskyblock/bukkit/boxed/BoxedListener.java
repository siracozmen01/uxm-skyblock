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
import org.bukkit.advancement.Advancement;
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

import com.uxplima.uxmskyblock.bukkit.arrival.Arrival;
import com.uxplima.uxmskyblock.bukkit.arrival.ArrivalCause;
import com.uxplima.uxmskyblock.bukkit.arrival.ArrivalGate;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffectPlayer;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffects;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.core.application.boxed.BoxedService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.boxed.AdvancementFacts;
import com.uxplima.uxmskyblock.core.domain.boxed.BoxOffset;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.island.IslandPermission;

/**
 * A Boxed island's box: it grows when one of the island's players makes an advancement, and in a
 * world Boxed islands are made in nobody builds, breaks, uses or walks where no box reaches.
 *
 * <p>Inside a box the island's own protection answers, as on any island. What this adds is the land
 * between boxes, which on generated terrain is land, not empty air.
 */
public final class BoxedListener implements Listener, ArrivalGate {

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
    private volatile boolean visitorsEarn;

    /** Moves an island's spawn to a spot when it stands outside a box. Off the main thread: it writes a row. */
    @FunctionalInterface
    public interface SpawnMover {
        void moveInto(IslandId islandId, IslandBounds box, double x, double y, double z);
    }

    private volatile SpawnMover spawnMover = (islandId, box, x, y, z) -> {};

    /** What moves an island's spawn after its box, when the box left it behind. */
    public void useSpawnMover(SpawnMover mover) {
        this.spawnMover = Objects.requireNonNull(mover, "mover must not be null");
    }

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

    /** Whether an advancement a player makes while on another island grows their own box. */
    public void letVisitorsEarn(boolean earn) {
        this.visitorsEarn = earn;
    }

    /** What a box needs to know of {@code advancement}: its key, how deep in its tab it lies and whether it is shown. */
    static AdvancementFacts facts(Advancement advancement) {
        int depth = 0;
        for (Advancement above = advancement.getParent(); above != null && depth < 256; above = above.getParent()) {
            depth++;
        }
        return new AdvancementFacts(advancement.getKey().asString(), depth, advancement.getDisplay() != null);
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
        AdvancementFacts advancement = facts(event.getAdvancement());
        if (service.rules().blocksFor(advancement) <= 0) {
            return;
        }
        Location at = player.getLocation();
        Optional<IslandId> standingOn =
                at == null ? Optional.empty() : islands.findIslandAt(at).map(Island::id);
        Optional<ProfileId> profile = activeProfile.apply(player.getUniqueId());
        if (profile.isEmpty()) {
            return;
        }
        UUID playerId = player.getUniqueId();
        scheduler.async(() -> {
            try {
                Optional<IslandId> own = islandOf.apply(profile.get());
                if (!visitorsEarn
                        && own.isPresent()
                        && standingOn.isPresent()
                        && !standingOn.get().equals(own.get())) {
                    if (service.isBoxed(own.get())) {
                        scheduler.onEntity(PlayerUuid.of(playerId), () -> messages.send(player, "boxed.visitor"));
                    }
                    return;
                }
                own.flatMap(islandId -> service.earn(islandId, advancement)).ifPresent(grown -> {
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
                LOGGER.log(Level.WARNING, e, () -> "An advancement could not grow a box: " + advancement.key());
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
        Optional<Island> island = islands.findIslandAt(at);
        if (island.isEmpty()) {
            return true;
        }
        // On a Boxed island only its box is played, which a pearl may have moved off the island's centre.
        return boxOf(service, island.get())
                .map(box -> !box.contains(at.getBlockX(), at.getBlockZ()))
                .orElse(false);
    }

    /** Where the island's box stands now: around its centre, moved as far as a pearl moved it. Memory only. */
    static Optional<IslandBounds> boxOf(BoxedService service, Island island) {
        java.util.OptionalInt radius = service.radius(island.id());
        if (radius.isEmpty()) {
            return Optional.empty();
        }
        BoxOffset offset = service.offset(island.id());
        return Optional.of(IslandBounds.fromCenterAndRadius(
                island.bounds().centerX() + offset.dx(), island.bounds().centerZ() + offset.dz(), radius.getAsInt()));
    }

    /**
     * An ender pearl, whether the server announced its teleport or the arrival watch found the player moved by it. One
     * thrown from inside a Boxed island's box by a player whose role may move it moves the box to where it lands, as
     * far as a box reaches from the island's centre. Any other pearl that carries a player out of every box is
     * refused, as a step out is.
     */
    @Override
    public boolean refuses(Arrival arrival) {
        Location from = arrival.from();
        if (arrival.cause() != ArrivalCause.PEARL || from == null) {
            return false;
        }
        Player player = arrival.player();
        Location to = arrival.to();
        Optional<Island> island = islands.findIslandAt(from).filter(found -> service.isBoxed(found.id()));
        boolean thrownInside = island.isPresent()
                && to.getWorld() != null
                && to.getWorld().equals(from.getWorld())
                && boxOf(service, island.get())
                        .map(box -> box.contains(from.getBlockX(), from.getBlockZ()))
                        .orElse(false);
        if (thrownInside && mayMoveTheBox(player, island.get())) {
            Island moved = island.get();
            BoxOffset offset = new BoxOffset(
                    to.getBlockX() - moved.bounds().centerX(),
                    to.getBlockZ() - moved.bounds().centerZ());
            if (!service.canMove(moved.id(), offset)) {
                messages.send(player, "boxed.move.too_far");
                return true;
            }
            UUID playerId = player.getUniqueId();
            Location landed = to.clone();
            scheduler.async(() -> moveTheBox(player, playerId, moved, offset, landed));
            return false;
        }
        if (outsideEveryBox(player, to)) {
            tell(player);
            return true;
        }
        return false;
    }

    private boolean mayMoveTheBox(Player player, Island island) {
        Optional<ProfileId> profile = activeProfile.apply(player.getUniqueId());
        // An owner's role kept from before the box could be moved does not name it, and an owner may all the same.
        return profile.isPresent()
                && (island.isOwner(profile.get()) || island.hasPermission(profile.get(), IslandPermission.BOX_MOVE));
    }

    private void moveTheBox(Player player, UUID playerId, Island island, BoxOffset offset, Location landed) {
        IslandId islandId = island.id();
        try {
            var moved = service.move(islandId, offset);
            if (!moved.isOk()) {
                scheduler.onEntity(PlayerUuid.of(playerId), () -> messages.send(player, moved.errorOrThrow()));
                return;
            }
            boxChanged.accept(islandId);
            // A spawn the box left behind follows it, to where the pearl landed, so /is home lands in the box.
            boxOf(service, island)
                    .ifPresent(box -> spawnMover.moveInto(islandId, box, landed.getX(), landed.getY(), landed.getZ()));
            scheduler.onEntity(PlayerUuid.of(playerId), () -> {
                messages.send(player, "boxed.moved");
                effectPlayer.fire(
                        effects,
                        "boxed-moved",
                        player,
                        java.util.Map.of("x", Integer.toString(offset.dx()), "z", Integer.toString(offset.dz())));
            });
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, e, () -> "The box of island " + islandId + " could not be moved.");
        }
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
