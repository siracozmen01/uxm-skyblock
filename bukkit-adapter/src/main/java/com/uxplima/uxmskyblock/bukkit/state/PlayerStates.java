package com.uxplima.uxmskyblock.bukkit.state;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.entity.Player;

import com.uxplima.uxmskyblock.bukkit.config.CarriedStateConfiguration;
import com.uxplima.uxmskyblock.bukkit.creative.InventoryCodec;
import com.uxplima.uxmskyblock.bukkit.creative.SealedInventory;
import com.uxplima.uxmskyblock.bukkit.inventory.BukkitInventorySerializer;
import com.uxplima.uxmskyblock.bukkit.session.ActiveSession;
import com.uxplima.uxmskyblock.core.application.inventory.CarriedStatePort;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.inventory.ProfileInventoryRecord;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import org.jspecify.annotations.Nullable;

/**
 * Keeps what a player carries apart for each group of worlds, and swaps it when they cross from one group to
 * another.
 *
 * <p>What the player carries is their profile's inventory, as the session writes it. What they put down in each
 * group they left is kept for the profile beside it, and so is the group they carry for. A crossing is written
 * before the player is given anything, in one transaction with the inventory and under the session: until it
 * lands the session is out of play and nothing the player holds can be used, and a write of what they held
 * before it is refused once it has landed. A crash on either side leaves the profile whole, and the join puts
 * back what was written.
 *
 * <p>Every call is made on the player's own thread.
 */
public final class PlayerStates {

    private static final Logger LOGGER = Logger.getLogger(PlayerStates.class.getName());
    private static final Set<UUID> SWAPPING = ConcurrentHashMap.newKeySet();

    /** A player's advancements, read and set as {@code advancement|criterion} lines, the recipe ones left out. */
    public interface Advancements {
        Set<String> earned(Player player);

        void earn(Player player, Set<String> criteria);
    }

    /** The worlds a group is found by. */
    public interface Worlds {
        /** The worlds the presets make islands in, each a group when the file names none. */
        List<String> presetWorlds();

        /** The server's own island world, whose group a world no group names belongs to. */
        String islandWorld();

        /**
         * The home world of the island a place in a dimension world belongs to, or nothing outside a dimension
         * world or away from every island.
         */
        Optional<String> homeOf(org.bukkit.Location place);
    }

    /** The sessions a crossing is written under. */
    public interface Sessions {
        @Nullable ActiveSession session(UUID player);

        /** Whether the player holds their session's state here and may use it. */
        boolean inPlay(UUID player);

        ServerNodeId node();
    }

    private final Supplier<CarriedStateConfiguration> config;
    private final Worlds worlds;
    private final InventoryCodec codec;
    private final Advancements advancements;
    private final CarriedStatePort store;
    private final Sessions sessions;
    private final SchedulerPort scheduler;
    private final Map<UUID, String> carried = new ConcurrentHashMap<>();
    private final Set<UUID> reading = ConcurrentHashMap.newKeySet();

    public PlayerStates(
            Supplier<CarriedStateConfiguration> config,
            Worlds worlds,
            InventoryCodec codec,
            Advancements advancements,
            CarriedStatePort store,
            Sessions sessions,
            SchedulerPort scheduler) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.worlds = Objects.requireNonNull(worlds, "worlds must not be null");
        this.codec = Objects.requireNonNull(codec, "codec must not be null");
        this.advancements = Objects.requireNonNull(advancements, "advancements must not be null");
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.sessions = Objects.requireNonNull(sessions, "sessions must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
    }

    /**
     * Whether the player's things are being swapped right now. An advancement awarded back in a swap is one they
     * had already, and nothing that rewards earning one should answer it.
     */
    public static boolean isSwapping(Player player) {
        return SWAPPING.contains(player.getUniqueId());
    }

    /** The group whose things the player carries, or nothing before it is read for their session. */
    public Optional<String> carried(Player player) {
        return Optional.ofNullable(carried.get(player.getUniqueId()));
    }

    /**
     * The player's session holds their state now: reads the group their profile carries for, recording the one
     * they stand in when it carries for none yet, and swaps if they stand in another.
     */
    public void sessionActive(Player player) {
        ActiveSession session = sessions.session(player.getUniqueId());
        if (!config.get().enabled() || session == null) {
            return;
        }
        carried.remove(player.getUniqueId());
        read(player, session);
    }

    /**
     * Reads the group the player's profile carries for. Until it is read the player is held in every world a group
     * names, and a read that fails is tried again on their next arrival.
     */
    private void read(Player player, ActiveSession session) {
        UUID uuid = player.getUniqueId();
        if (!reading.add(uuid)) {
            return;
        }
        ProfileId profile = session.activeProfileId();
        String here = groupHere(player, config.get());
        scheduler.async(() -> {
            Optional<String> group;
            try {
                group = Optional.of(store.carriedGroup(profile).orElseGet(() -> store.carryFirst(profile, here)));
            } catch (RuntimeException e) {
                reading.remove(uuid);
                LOGGER.log(Level.WARNING, e, () -> "The group " + profile + " carries for could not be read");
                return;
            }
            scheduler.onEntity(PlayerUuid.of(uuid), () -> {
                reading.remove(uuid);
                if (group.isPresent()
                        && player.isOnline()
                        && session.activeProfileId().equals(profile)
                        && !session.isFenced()) {
                    carried.put(uuid, group.get());
                    arrived(player);
                }
            });
        });
    }

    /** The player left, or left the profile: what they carry is read again for the next session. */
    public void left(Player player) {
        carried.remove(player.getUniqueId());
        reading.remove(player.getUniqueId());
    }

    /**
     * The player stands in a world they may not have carried their things to. Starts the crossing when the world
     * is in another group than the one they carry for. Returns whether it started one.
     */
    public boolean arrived(Player player) {
        CarriedStateConfiguration settings = config.get();
        if (!settings.enabled()) {
            return false;
        }
        String to = groupHere(player, settings);
        UUID uuid = player.getUniqueId();
        ActiveSession session = sessions.session(uuid);
        String from = carried.get(uuid);
        if (from == null) {
            if (session != null && !session.isFenced()) {
                read(player, session);
            }
            return false;
        }
        if (from.equals(to) || session == null || session.isFenced() || !sessions.inPlay(uuid)) {
            // Out of play the player's state is about to be replaced, by a join, a profile switch or a crossing.
            return false;
        }
        // Closed before anything is read: a window that writes the player's state itself writes as it closes,
        // and that write, still to land, holds what they held before. The crossing waits for it.
        player.closeInventory();
        if (session.selfWriteOutstanding()) {
            return false;
        }
        SWAPPING.add(uuid);
        cross(player, session, from, to, settings);
        return true;
    }

    private void cross(
            Player player, ActiveSession session, String from, String to, CarriedStateConfiguration settings) {
        UUID uuid = player.getUniqueId();
        // A creative place's seal is lifted first, so what was made there never reaches another group.
        SealedInventory.lift(player, codec);
        // Nothing the player holds is used until what they pick up replaces it.
        session.leavePlay();
        ProfileInventoryRecord leaving = BukkitInventorySerializer.snapshotPlayer(
                player, session.activeProfileId(), session.lastDurableVersion());
        CarriedStatePort.Crossing crossing = new CarriedStatePort.Crossing(
                PlayerUuid.of(uuid),
                session.activeProfileId(),
                sessions.node(),
                session.sessionEpoch(),
                from,
                to,
                leaving,
                List.copyOf(advancements.earned(player)),
                settings.keeps().parts());
        scheduler.async(() -> {
            CarriedStatePort.Crossed crossed;
            session.writes().lock();
            try {
                crossed = store.cross(crossing);
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, e, () -> player.getName() + " could not cross from " + from + " to " + to);
                crossed = new CarriedStatePort.Crossed.Refused(String.valueOf(e.getMessage()));
            } finally {
                session.writes().unlock();
            }
            CarriedStatePort.Crossed landed = crossed;
            scheduler.onEntity(PlayerUuid.of(uuid), () -> land(player, session, crossing, landed));
        });
    }

    /** Gives the player what the crossing wrote, or lets them play on with what they held when it was refused. */
    private void land(
            Player player,
            ActiveSession session,
            CarriedStatePort.Crossing crossing,
            CarriedStatePort.Crossed crossed) {
        UUID uuid = player.getUniqueId();
        try {
            if (crossed instanceof CarriedStatePort.Crossed.Done done) {
                if (!player.isOnline() || session.isFenced()) {
                    // Written already: the next join puts it on them.
                    return;
                }
                // Advancements come back first. An advancement's own reward, experience or loot, is paid each time
                // it is given back, and everything set after overwrites what it paid, so crossing between groups is
                // no way to earn it again. A group that keeps none apart is given back what the player held.
                advancements.earn(player, Set.copyOf(done.advancements()));
                BukkitInventorySerializer.applyToPlayer(player, done.carried());
                // What a seal still held was written as what the player put down, and is not given twice.
                SealedInventory.forget(player);
                session.setLastDurableVersion(done.carried().version());
                carried.put(uuid, crossing.to());
            } else if (crossed instanceof CarriedStatePort.Crossed.Refused refused) {
                LOGGER.fine(() -> player.getName() + " stays with " + crossing.from() + ": " + refused.why());
            }
        } finally {
            session.enterPlay();
            SWAPPING.remove(uuid);
        }
    }

    /**
     * Whether the player stands where another group than the one whose things they carry plays, or anywhere before
     * the group they carry for is read, or is crossing now. Nothing they carry may be put down there before the
     * swap.
     */
    public boolean pending(Player player) {
        CarriedStateConfiguration settings = config.get();
        if (!settings.enabled()) {
            return false;
        }
        if (SWAPPING.contains(player.getUniqueId())) {
            return true;
        }
        String from = carried.get(player.getUniqueId());
        // Before the group is read nothing is known to be the player's here, so they are held.
        return from == null || !groupHere(player, settings).equals(from);
    }

    /**
     * The group of where the player stands. Every world belongs to one: the one the file or the presets name it
     * in; in a dimension world, that of the island the place belongs to; else that of the server's own island
     * world. A world shared by two groups would be a way to carry things from one to the other.
     */
    private String groupHere(Player player, CarriedStateConfiguration settings) {
        List<String> presets = worlds.presetWorlds();
        Optional<String> named = settings.groupOf(player.getWorld().getName(), presets);
        if (named.isPresent()) {
            return named.get();
        }
        org.bukkit.Location place = player.getLocation();
        Optional<String> island = place == null
                ? Optional.empty()
                : worlds.homeOf(place).flatMap(home -> settings.groupOf(home, presets));
        if (island.isPresent()) {
            return island.get();
        }
        String own = worlds.islandWorld();
        return settings.groupOf(own, presets).orElse(own);
    }
}
