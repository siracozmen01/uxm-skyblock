package com.uxplima.uxmskyblock.bukkit.stranger;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;

import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import org.jspecify.annotations.Nullable;

/**
 * The Upside Down follows a StrangerRealms island as far as the island reaches.
 *
 * <p>The mirror was laid once, when the island was made, as far as {@code upside-down.radius}. The island grows
 * past that with every member and every size it buys, and the land it grew into had no Upside Down: the Nether
 * that was there stayed. Now a chunk of the island's land is mirrored the first time it loads on either side of
 * the veil, and only while its Upside Down has never been made. A chunk the Upside Down already has, mirrored at
 * creation or walked into before, is never laid over, so nothing anybody built there is lost.
 *
 * <p>Land mirrored this late may carry what the island's players put on it, where the land of a new island did
 * not. A block that is worth something, or one that holds things, would be had twice, once on each side, so the
 * Upside Down gets air in its place.
 */
public final class UpsideDownReach implements Listener {

    /** Whether the world has made the chunk at these coordinates, loaded or not. */
    @FunctionalInterface
    public interface Made {
        boolean made(World world, int chunkX, int chunkZ);
    }

    private final Realms realms;
    private final UpsideDownStart mirror;
    private final SchedulerPort scheduler;
    private final Function<String, @Nullable World> worlds;
    private final Made made;
    private final Predicate<Material> copied;
    private final Set<String> asked = ConcurrentHashMap.newKeySet();
    private final Set<String> mirroring = ConcurrentHashMap.newKeySet();

    public UpsideDownReach(
            Realms realms,
            UpsideDownStart mirror,
            SchedulerPort scheduler,
            Function<String, @Nullable World> worlds,
            Made made,
            Predicate<Material> copied) {
        this.realms = Objects.requireNonNull(realms, "realms must not be null");
        this.mirror = Objects.requireNonNull(mirror, "mirror must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.worlds = Objects.requireNonNull(worlds, "worlds must not be null");
        this.made = Objects.requireNonNull(made, "made must not be null");
        this.copied = Objects.requireNonNull(copied, "copied must not be null");
    }

    /**
     * What a late mirror copies: every block but those {@code valuable} names by key, such as
     * {@code minecraft:diamond_block}, and those {@code holdsThings} says carry contents.
     */
    public static Predicate<Material> copiedUnless(Set<String> valuable, Predicate<Material> holdsThings) {
        Set<String> named = Set.copyOf(valuable);
        return material -> !named.contains(material.getKey().asString()) && !holdsThings.test(material);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        World world = event.getWorld();
        int chunkX = event.getChunk().getX();
        int chunkZ = event.getChunk().getZ();
        Optional<Realms.Place> found = placeOf(world.getName(), chunkX, chunkZ);
        if (found.isEmpty()) {
            return;
        }
        Realms.Place place = found.get();
        String key = place.landWorld() + ":" + chunkX + ":" + chunkZ;
        if (place.upsideDown()) {
            // The Upside Down just made this chunk out of Nether: it has never been mirrored.
            if (!event.isNewChunk() || mirroring.contains(key)) {
                return;
            }
            World land = worlds.apply(place.landWorld());
            if (land != null) {
                scheduler.onRegion(
                        land.getName(), chunkX, chunkZ, () -> mirror.mirror(land, world, chunkX, chunkZ, copied));
            }
            return;
        }
        World upsideDown = worlds.apply(realms.upsideDownWorld());
        if (upsideDown == null || !asked.add(key)) {
            return;
        }
        scheduler.onRegion(upsideDown.getName(), chunkX, chunkZ, () -> {
            if (made.made(upsideDown, chunkX, chunkZ)) {
                return;
            }
            // Laying the mirror makes the chunk, and the Upside Down's own load of it must not lay it again.
            mirroring.add(key);
            scheduler.onRegion(
                    world.getName(), chunkX, chunkZ, () -> mirror.mirror(world, upsideDown, chunkX, chunkZ, copied));
        });
    }

    /**
     * The StrangerRealms island the chunk belongs to: one whose land takes in its middle or any corner. A chunk on
     * the island's edge is partly the island's, and the part that is must be mirrored.
     */
    private Optional<Realms.Place> placeOf(String world, int chunkX, int chunkZ) {
        int minX = chunkX << 4;
        int minZ = chunkZ << 4;
        int[][] spots = {
            {minX + 8, minZ + 8}, {minX, minZ}, {minX + 15, minZ}, {minX, minZ + 15}, {minX + 15, minZ + 15}
        };
        for (int[] spot : spots) {
            Optional<Realms.Place> place = realms.placeOf(world, spot[0], spot[1]);
            if (place.isPresent()) {
                return place;
            }
        }
        return Optional.empty();
    }
}
