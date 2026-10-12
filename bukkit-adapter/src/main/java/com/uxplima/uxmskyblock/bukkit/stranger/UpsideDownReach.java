package com.uxplima.uxmskyblock.bukkit.stranger;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;

import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffectPlayer;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffects;
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
 * not, and a block copied across would be had twice, once on each side. So a late mirror copies only the plain
 * ground the operator lists in {@code upside-down.late-copy}, and lays air for everything else.
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
    private volatile @Nullable InteractionEffectPlayer effectPlayer;
    private volatile InteractionEffects effects = InteractionEffects.none();

    /** How near the mirrored chunk's middle a player in the Upside Down stands to hear it, in blocks. */
    static final int HEARD_WITHIN = 48;

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
     * Hands it what {@code modules/effects.conf} fires, as {@code upside-down-mirrored}, for each player in the
     * Upside Down near a chunk when it is first mirrored. Without it a mirror fires nothing.
     */
    public void useEffects(InteractionEffectPlayer player, InteractionEffects written) {
        this.effects = Objects.requireNonNull(written, "written");
        this.effectPlayer = Objects.requireNonNull(player, "player");
    }

    /**
     * What a late mirror copies: only the blocks {@code written} names, each a name such as {@code STONE} or a name
     * with one {@code *} such as {@code *_LOG}. Anything else, whatever it is worth, is laid as air.
     */
    public static Predicate<Material> copiedOnly(List<String> written) {
        List<String> names = written.stream()
                .map(name -> name.strip().toUpperCase(Locale.ROOT))
                .filter(name -> !name.isEmpty())
                .toList();
        Map<Material, Boolean> known = new ConcurrentHashMap<>();
        return material ->
                known.computeIfAbsent(material, block -> names.stream().anyMatch(name -> matches(name, block.name())));
    }

    private static boolean matches(String written, String name) {
        int star = written.indexOf('*');
        if (star < 0) {
            return written.equals(name);
        }
        String before = written.substring(0, star);
        String after = written.substring(star + 1);
        return name.length() >= before.length() + after.length() && name.startsWith(before) && name.endsWith(after);
    }

    /**
     * Fires {@code upside-down-mirrored} for each player in {@code upsideDown} near the chunk, on their own thread.
     * Says {@code %chunk_x%} and {@code %chunk_z%}.
     */
    private void heard(World upsideDown, int chunkX, int chunkZ) {
        InteractionEffectPlayer fires = effectPlayer;
        if (fires == null) {
            return;
        }
        InteractionEffects written = effects;
        double middleX = (chunkX << 4) + 8;
        double middleZ = (chunkZ << 4) + 8;
        Map<String, String> says = Map.of("chunk_x", Integer.toString(chunkX), "chunk_z", Integer.toString(chunkZ));
        for (Player player : upsideDown.getPlayers()) {
            scheduler.onEntity(player.getUniqueId(), () -> {
                Location at = player.getLocation();
                if (player.isOnline() && at != null && near(at, upsideDown, middleX, middleZ)) {
                    fires.fire(written, "upside-down-mirrored", player, says);
                }
            });
        }
    }

    private static boolean near(Location at, World world, double x, double z) {
        double dx = at.getX() - x;
        double dz = at.getZ() - z;
        return world.equals(at.getWorld()) && dx * dx + dz * dz <= (double) HEARD_WITHIN * HEARD_WITHIN;
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
                scheduler.onRegion(land.getName(), chunkX, chunkZ, () -> {
                    mirror.mirror(land, world, chunkX, chunkZ, copied);
                    heard(world, chunkX, chunkZ);
                });
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
            scheduler.onRegion(world.getName(), chunkX, chunkZ, () -> {
                mirror.mirror(world, upsideDown, chunkX, chunkZ, copied);
                heard(upsideDown, chunkX, chunkZ);
            });
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
