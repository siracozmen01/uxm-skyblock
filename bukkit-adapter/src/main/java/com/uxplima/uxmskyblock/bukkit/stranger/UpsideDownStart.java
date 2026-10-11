package com.uxplima.uxmskyblock.bukkit.stranger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Orientable;

import com.uxplima.uxmskyblock.bukkit.config.StrangerRealmsConfiguration;
import com.uxplima.uxmskyblock.bukkit.schematic.IslandStart;
import com.uxplima.uxmskyblock.core.application.gamemode.CreationActionProvider;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.stranger.StrangerRealmsService;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.stranger.DistressPalette;
import org.jspecify.annotations.Nullable;

/**
 * The creation action that makes an island a StrangerRealms island: records it and mirrors its land
 * into the Upside Down, the world that takes the place of the Nether, block for block and distressed.
 *
 * <p>The mirror is laid when the island is made, so the Upside Down is there before anybody opens the
 * way to it. Each chunk is read on the thread of the region that owns it in the island's world, and
 * written on the thread of the region that owns it in the Upside Down. What travels between the two is
 * a copy of the blocks, never the chunk itself.
 *
 * <p>A preset names the action in the Nether too, so the dimension is open to the island. There it lays
 * nothing: the island's creation already mirrored it.
 */
public final class UpsideDownStart implements CreationActionProvider<IslandStart> {

    /** The name a preset's start list writes for this action. */
    public static final String ACTION = "uxm:upside-down";

    private static final Logger LOGGER = Logger.getLogger(UpsideDownStart.class.getName());

    private final StrangerRealmsService service;
    private final SchedulerPort scheduler;
    private final StrangerRealmsConfiguration.UpsideDown upsideDown;
    private final DistressPalette palette;
    private final Supplier<String> upsideDownWorld;
    private final Map<String, @Nullable Material> materials = new ConcurrentHashMap<>();

    /** @param upsideDownWorld the name of the world the Upside Down is, which dimensions.conf gives the Nether */
    public UpsideDownStart(
            StrangerRealmsService service,
            SchedulerPort scheduler,
            StrangerRealmsConfiguration.UpsideDown upsideDown,
            Supplier<String> upsideDownWorld) {
        this.service = Objects.requireNonNull(service, "service must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.upsideDown = Objects.requireNonNull(upsideDown, "upsideDown must not be null");
        this.upsideDownWorld = Objects.requireNonNull(upsideDownWorld, "upsideDownWorld must not be null");
        List<String> unread = new ArrayList<>();
        this.palette = DistressPalette.parse(upsideDown.palette(), unread);
        for (String line : unread) {
            LOGGER.warning(() -> "modules/strangerrealms.conf palette: " + line + " is not FROM:TO.");
        }
    }

    @Override
    public String actionId() {
        return ACTION;
    }

    /** The Upside Down is laid in the Nether, whole chunks as far as strangerrealms.conf says. */
    @Override
    public java.util.List<com.uxplima.uxmskyblock.core.application.gamemode.CreationReach> reach(IslandStart start) {
        String target = upsideDownWorld.get();
        if (start.world().getName().equals(target)) {
            return java.util.List.of();
        }
        // Whole chunks are mirrored, so the copy reaches to the edge of the last chunk the radius touches.
        return java.util.List.of(
                new com.uxplima.uxmskyblock.core.application.gamemode.CreationReach(target, upsideDown.radius() + 15));
    }

    @Override
    public void apply(IslandStart start) {
        String targetName = upsideDownWorld.get();
        World source = start.world();
        if (source.getName().equals(targetName)) {
            // The island's Nether: its creation already mirrored it here.
            return;
        }
        IslandId islandId = start.islandId();
        scheduler.async(() -> {
            try {
                service.start(islandId);
            } catch (RuntimeException e) {
                LOGGER.log(
                        Level.WARNING, e, () -> "Island " + islandId + " could not be made a StrangerRealms island.");
            }
        });
        World target = Bukkit.getWorld(targetName);
        if (target == null) {
            LOGGER.warning(() -> "The Upside Down of island " + islandId + " is not laid: the world " + targetName
                    + ", which dimensions.conf names for the Nether, is not loaded.");
            return;
        }
        int bottom = Math.max(source.getMinHeight(), target.getMinHeight());
        int top = Math.min(source.getMaxHeight(), target.getMaxHeight());
        int radius = upsideDown.radius();
        int centreChunkX = start.centerX() >> 4;
        int centreChunkZ = start.centerZ() >> 4;
        for (int chunkX = (start.centerX() - radius) >> 4; chunkX <= (start.centerX() + radius) >> 4; chunkX++) {
            for (int chunkZ = (start.centerZ() - radius) >> 4; chunkZ <= (start.centerZ() + radius) >> 4; chunkZ++) {
                int cx = chunkX;
                int cz = chunkZ;
                Runnable mirror = () -> {
                    BlockData[] copy = read(source, cx, cz, bottom, top);
                    scheduler.onRegion(targetName, cx, cz, () -> write(target, cx, cz, bottom, top, copy));
                };
                if (cx == centreChunkX && cz == centreChunkZ) {
                    mirror.run();
                } else {
                    scheduler.onRegion(source.getName(), cx, cz, mirror);
                }
            }
        }
    }

    /** The chunk's blocks from the bottom to the top, distressed, on the thread that owns the chunk. */
    BlockData[] read(World source, int chunkX, int chunkZ, int bottom, int top) {
        BlockData[] copy = new BlockData[16 * 16 * (top - bottom)];
        int index = 0;
        for (int y = bottom; y < top; y++) {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    copy[index++] = distress(source.getBlockAt((chunkX << 4) + x, y, (chunkZ << 4) + z)
                            .getBlockData());
                }
            }
        }
        return copy;
    }

    /** Lays the copy into the chunk, touching only the blocks that differ, on the thread that owns it. */
    static void write(World target, int chunkX, int chunkZ, int bottom, int top, BlockData[] copy) {
        int index = 0;
        for (int y = bottom; y < top; y++) {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    BlockData wanted = copy[index++];
                    Block block = target.getBlockAt((chunkX << 4) + x, y, (chunkZ << 4) + z);
                    if (block.getType() != wanted.getMaterial()
                            || !block.getBlockData().equals(wanted)) {
                        block.setBlockData(wanted, false);
                    }
                }
            }
        }
    }

    private BlockData distress(BlockData original) {
        String name = original.getMaterial().name();
        String turned = palette.distressed(name);
        if (turned.equals(name)) {
            return original;
        }
        Material into = materials.computeIfAbsent(turned, UpsideDownStart::blockNamed);
        if (into == null) {
            return original;
        }
        BlockData data = into.createBlockData();
        if (original instanceof Orientable from
                && data instanceof Orientable to
                && to.getAxes().contains(from.getAxis())) {
            to.setAxis(from.getAxis());
        }
        return data;
    }

    private static @Nullable Material blockNamed(String name) {
        Material material = Material.matchMaterial(name);
        if (material == null || !material.isBlock()) {
            LOGGER.warning(() -> "modules/strangerrealms.conf palette names " + name + ", which is no block.");
            return null;
        }
        return material;
    }
}
