package com.uxplima.uxmskyblock.bukkit.acid;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;

import com.uxplima.uxmskyblock.bukkit.config.AcidIslandConfiguration;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffectPlayer;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffects;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.core.application.acid.AcidIslandService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.island.Island;

/**
 * Moves the acid sea with the tide: on a fixed beat, the sea around each AcidIsland island a player is on is brought
 * to the tide's level, water added above the sea's surface as it rises and taken away as it falls.
 *
 * <p>Only a column of sea is touched: one whose surface and the block below it are water. An island's own blocks and
 * a pond on it are left alone. Each chunk is changed on the thread of the region that owns it, and only while it is
 * loaded; a chunk that loads later is brought to the tide as it loads. Each player on an AcidIsland island hears
 * the tide at each block it moves, and is told as it turns.
 */
public final class AcidTideRunner implements Listener {

    /** Whether a chunk is loaded, asked on the thread that owns it. */
    @FunctionalInterface
    public interface Loaded {
        boolean chunk(World world, int chunkX, int chunkZ);
    }

    private final AcidIslandService service;
    private final IslandProtectionListener islands;
    private final SchedulerPort scheduler;
    private final AcidIslandConfiguration.Sea sea;
    private final AcidIslandConfiguration.Tide tide;
    private final InteractionEffects effects;
    private final InteractionEffectPlayer effectPlayer;
    private final Messages messages;
    private final LongSupplier clock;
    private final Loaded loaded;
    private final Map<IslandId, Integer> brought = new ConcurrentHashMap<>();
    // The chunks of the open sea brought to the level openLevel names, forgotten as the level moves on, so a sea
    // that runs on as far as players sail is remembered only around them, for one level at a time.
    private final java.util.Set<String> broughtOpen = ConcurrentHashMap.newKeySet();
    private volatile int openLevel = -1;
    private final AcidWaters waters;
    private volatile int level = -1;
    private volatile int heading;

    /** @param clock the wall clock, in milliseconds since the epoch */
    @SuppressWarnings("TooManyParameters")
    public AcidTideRunner(
            AcidIslandService service,
            IslandProtectionListener islands,
            SchedulerPort scheduler,
            AcidIslandConfiguration config,
            InteractionEffects effects,
            InteractionEffectPlayer effectPlayer,
            Messages messages,
            LongSupplier clock,
            Loaded loaded) {
        this.service = Objects.requireNonNull(service, "service must not be null");
        this.islands = Objects.requireNonNull(islands, "islands must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.sea = config.sea();
        this.tide = config.tide();
        this.effects = Objects.requireNonNull(effects, "effects must not be null");
        this.effectPlayer = Objects.requireNonNull(effectPlayer, "effectPlayer must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.loaded = Objects.requireNonNull(loaded, "loaded must not be null");
        this.waters = new AcidWaters(service, islands, config.between());
    }

    /** Starts the beat, while the sea moves at all. Closing what it returns stops it. */
    public AutoCloseable start() {
        if (!tide.tide().moves()) {
            return () -> {};
        }
        return scheduler.repeatGlobal(this::round, tide.checkEvery(), tide.checkEvery());
    }

    /** One beat: works out the tide, then has each player's own thread look at the island they are on. */
    void round() {
        int now = tide.tide().level(clock.getAsLong());
        int before = level;
        level = now;
        int moved = before < 0 ? 0 : Integer.signum(now - before);
        boolean turned = moved != 0 && moved != heading;
        if (moved != 0) {
            heading = moved;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            scheduler.onEntity(PlayerUuid.of(player.getUniqueId()), () -> look(player, now, moved, turned));
        }
    }

    /** On the player's thread: brings the sea of the island they are on to the tide, and lets them hear it move. */
    void look(Player player, int now, int moved, boolean turned) {
        Location at = player.getLocation();
        if (at == null) {
            return;
        }
        Optional<Island> island = islands.findIslandAt(at);
        if (island.isPresent() && service.isAcid(island.get().id())) {
            bring(island.get(), at.getWorld(), now);
        } else if (waters.openSea(at.getWorld())) {
            bringAround(at, now);
        } else {
            return;
        }
        if (moved == 0) {
            return;
        }
        Map<String, String> says = Map.of("level", Integer.toString(now));
        if (moved > 0) {
            effectPlayer.fire(effects, "acid-tide-rising", player, says);
        } else {
            effectPlayer.fire(effects, "acid-tide-falling", player, says);
        }
        if (turned) {
            messages.send(player, moved > 0 ? "acid.tide.rising" : "acid.tide.falling");
        }
    }

    /** Brings the island's sea to {@code now}, once for each level, each chunk on its own region. */
    private void bring(Island island, World world, int now) {
        Integer before = brought.put(island.id(), now);
        OptionalInt surface = service.seaLevel(island.id());
        if ((before != null && before == now) || surface.isEmpty()) {
            return;
        }
        int centreX = island.bounds().centerX();
        int centreZ = island.bounds().centerZ();
        int radius = sea.radius();
        for (int chunkX = (centreX - radius) >> 4; chunkX <= (centreX + radius) >> 4; chunkX++) {
            for (int chunkZ = (centreZ - radius) >> 4; chunkZ <= (centreZ + radius) >> 4; chunkZ++) {
                int cx = chunkX;
                int cz = chunkZ;
                scheduler.onRegion(world.getName(), cx, cz, () -> {
                    if (loaded.chunk(world, cx, cz)) {
                        setOnIsland(world, cx, cz, centreX, centreZ, surface.getAsInt(), now);
                    }
                });
            }
        }
    }

    /**
     * Brings the open sea around a player to {@code now}, as far as an island's sea reaches, each chunk once for each
     * level, on its own region.
     */
    private void bringAround(Location at, int now) {
        World world = at.getWorld();
        int surface = waters.between().surface();
        int reach = (sea.radius() >> 4) + 1;
        for (int chunkX = (at.getBlockX() >> 4) - reach; chunkX <= (at.getBlockX() >> 4) + reach; chunkX++) {
            for (int chunkZ = (at.getBlockZ() >> 4) - reach; chunkZ <= (at.getBlockZ() >> 4) + reach; chunkZ++) {
                if (!rememberOpen(world.getName() + ":" + chunkX + ":" + chunkZ, now)) {
                    continue;
                }
                int cx = chunkX;
                int cz = chunkZ;
                scheduler.onRegion(world.getName(), cx, cz, () -> {
                    if (loaded.chunk(world, cx, cz)) {
                        set(world, cx << 4, (cx << 4) + 15, cz << 4, (cz << 4) + 15, surface, now);
                    }
                });
            }
        }
    }

    /** Remembers a chunk of the open sea brought to {@code level}; whether it was not already. */
    private boolean rememberOpen(String chunk, int level) {
        if (openLevel != level) {
            broughtOpen.clear();
            openLevel = level;
        }
        return broughtOpen.add(chunk);
    }

    /** How many chunks of the open sea are remembered. */
    int rememberedOpen() {
        return broughtOpen.size();
    }

    /** A chunk of an AcidIsland island's sea that loads is brought to the tide there and then, on its own thread. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        if (!tide.tide().moves()) {
            // Nothing to bring a chunk to, and no reason to look the island up at every chunk that loads.
            return;
        }
        World world = event.getWorld();
        int chunkX = event.getChunk().getX();
        int chunkZ = event.getChunk().getZ();
        Optional<Island> island = islands.findIslandAt(new Location(world, (chunkX << 4) + 8, 0, (chunkZ << 4) + 8));
        if (waters.openSea(world)) {
            // The whole world is one sea, at one surface.
            int level = tide.tide().level(clock.getAsLong());
            rememberOpen(world.getName() + ":" + chunkX + ":" + chunkZ, level);
            set(
                    world,
                    chunkX << 4,
                    (chunkX << 4) + 15,
                    chunkZ << 4,
                    (chunkZ << 4) + 15,
                    waters.between().surface(),
                    level);
            return;
        }
        if (island.isEmpty()) {
            return;
        }
        // Only an AcidIsland island has a sea level.
        OptionalInt surface = service.seaLevel(island.get().id());
        if (surface.isEmpty()) {
            return;
        }
        setOnIsland(
                world,
                chunkX,
                chunkZ,
                island.get().bounds().centerX(),
                island.get().bounds().centerZ(),
                surface.getAsInt(),
                tide.tide().level(clock.getAsLong()));
    }

    /** Sets the tide's water over each column of sea in the chunk, and takes away what stands above the tide. */
    private void setOnIsland(World world, int chunkX, int chunkZ, int centreX, int centreZ, int surface, int now) {
        set(
                world,
                Math.max(chunkX << 4, centreX - sea.radius()),
                Math.min((chunkX << 4) + 15, centreX + sea.radius()),
                Math.max(chunkZ << 4, centreZ - sea.radius()),
                Math.min((chunkZ << 4) + 15, centreZ + sea.radius()),
                surface,
                now);
    }

    /** Sets the tide's water over each column of sea in the area, and takes away what stands above the tide. */
    private void set(World world, int fromX, int toX, int fromZ, int toZ, int surface, int now) {
        for (int x = fromX; x <= toX; x++) {
            for (int z = fromZ; z <= toZ; z++) {
                if (!isSea(world.getBlockAt(x, surface, z)) || !isSea(world.getBlockAt(x, surface - 1, z))) {
                    continue;
                }
                for (int above = 1; above <= tide.tide().rise(); above++) {
                    Block block = world.getBlockAt(x, surface + above, z);
                    if (above <= now && block.getType().isAir()) {
                        block.setType(Material.WATER, false);
                    } else if (above > now && block.getType() == Material.WATER) {
                        block.setType(Material.AIR, false);
                    }
                }
            }
        }
    }

    private static boolean isSea(Block block) {
        Material type = block.getType();
        return type == Material.WATER || type == Material.BUBBLE_COLUMN;
    }
}
