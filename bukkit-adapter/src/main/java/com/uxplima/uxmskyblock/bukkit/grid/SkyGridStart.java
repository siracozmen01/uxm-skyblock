package com.uxplima.uxmskyblock.bukkit.grid;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import com.uxplima.uxmskyblock.bukkit.config.SkyGridConfiguration;
import com.uxplima.uxmskyblock.bukkit.schematic.IslandStart;
import com.uxplima.uxmskyblock.core.application.gamemode.CreationActionProvider;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.grid.GridLayout;
import com.uxplima.uxmskyblock.core.domain.grid.GridPalette;

/**
 * The creation action that makes an island a SkyGrid island: a sparse grid of blocks, one every few
 * blocks along each axis, with players arriving on a safe block at its centre.
 *
 * <p>The block players arrive on is set at once, on the thread that runs the creation, so it is there
 * before anyone is sent to it. The rest of the grid spans many chunks and each chunk's blocks are set on
 * that region's thread. Only air is filled. A chest in the grid holds some of the chest items, and a
 * spawner raises one of its dimension's creatures. The same island always gets the same grid.
 */
public final class SkyGridStart implements CreationActionProvider<IslandStart> {

    /** The name a preset's start list writes for this action. */
    public static final String ACTION = "uxm:skygrid";

    private static final Logger LOGGER = Logger.getLogger(SkyGridStart.class.getName());

    private final SchedulerPort scheduler;
    private final SkyGridConfiguration config;
    private final Map<String, Optional<Material>> known = new ConcurrentHashMap<>();

    public SkyGridStart(SchedulerPort scheduler, SkyGridConfiguration config) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");
    }

    @Override
    public String actionId() {
        return ACTION;
    }

    /** The grid reaches as far as skygrid.conf says, past the border of the island a player starts with. */
    @Override
    public java.util.List<com.uxplima.uxmskyblock.core.application.gamemode.CreationReach> reach(IslandStart start) {
        return java.util.List.of(new com.uxplima.uxmskyblock.core.application.gamemode.CreationReach(
                start.world().getName(), config.layout().radius()));
    }

    @Override
    public void apply(IslandStart start) {
        World world = start.world();
        SkyGridConfiguration.Palette palette = config.palette(dimensionOf(world));
        GridLayout layout = config.layout();
        long seed = start.islandId().value().getMostSignificantBits()
                ^ start.islandId().value().getLeastSignificantBits();
        setIfAir(
                world.getBlockAt(start.centerX(), start.y(), start.centerZ()),
                material(config.spawnBlock()).orElse(Material.GRASS_BLOCK));
        int minX = start.centerX() - layout.radius();
        int maxX = start.centerX() + layout.radius();
        int minZ = start.centerZ() - layout.radius();
        int maxZ = start.centerZ() + layout.radius();
        for (int chunkX = minX >> 4; chunkX <= maxX >> 4; chunkX++) {
            for (int chunkZ = minZ >> 4; chunkZ <= maxZ >> 4; chunkZ++) {
                int fromX = Math.max(minX, chunkX << 4);
                int toX = Math.min(maxX, (chunkX << 4) + 15);
                int fromZ = Math.max(minZ, chunkZ << 4);
                int toZ = Math.min(maxZ, (chunkZ << 4) + 15);
                scheduler.onRegion(
                        world.getName(),
                        chunkX,
                        chunkZ,
                        () -> fill(world, start, palette, seed, fromX, toX, fromZ, toZ));
            }
        }
    }

    private void fill(
            World world,
            IslandStart start,
            SkyGridConfiguration.Palette palette,
            long seed,
            int fromX,
            int toX,
            int fromZ,
            int toZ) {
        GridLayout layout = config.layout();
        int cx = start.centerX();
        int cy = start.y();
        int cz = start.centerZ();
        for (int dx = layout.alignUp(fromX - cx); dx <= toX - cx; dx += layout.spacing()) {
            for (int dz = layout.alignUp(fromZ - cz); dz <= toZ - cz; dz += layout.spacing()) {
                for (int dy = layout.alignUp(-layout.below()); dy <= layout.above(); dy += layout.spacing()) {
                    if ((dx == 0 && dy == 0 && dz == 0) || !layout.isNode(dx, dy, dz)) {
                        continue;
                    }
                    SplittableRandom random = new SplittableRandom(seed
                            ^ (dx * 0x9E3779B97F4A7C15L)
                            ^ (dy * 0xBF58476D1CE4E5B9L)
                            ^ (dz * 0x94D049BB133111EBL));
                    Block block = world.getBlockAt(cx + dx, cy + dy, cz + dz);
                    if (!block.getType().isAir()) {
                        continue;
                    }
                    Material material = pick(palette.blocks(), random);
                    block.setType(material, false);
                    if (material == Material.CHEST) {
                        stock(block, random);
                    } else if (material == Material.SPAWNER) {
                        raise(block, palette.spawners(), random);
                    }
                }
            }
        }
    }

    /** A block by its weight, passing over a name that is no block. */
    private Material pick(GridPalette blocks, SplittableRandom random) {
        for (int tries = 0; tries < 8; tries++) {
            Optional<Material> material = material(blocks.pick(random.nextDouble()));
            if (material.isPresent()) {
                return material.get();
            }
        }
        return Material.STONE;
    }

    private void stock(Block block, SplittableRandom random) {
        List<SkyGridConfiguration.Loot> items = config.chestItems();
        if (items.isEmpty() || !(block.getState() instanceof Chest chest)) {
            return;
        }
        Inventory inventory = chest.getSnapshotInventory();
        for (int roll = 0; roll < config.chestRolls(); roll++) {
            SkyGridConfiguration.Loot loot = items.get(random.nextInt(items.size()));
            Material item = Material.matchMaterial(loot.item());
            if (item == null || !item.isItem()) {
                continue;
            }
            int amount = loot.min() + random.nextInt(loot.max() - loot.min() + 1);
            inventory.setItem(random.nextInt(inventory.getSize()), new ItemStack(item, amount));
        }
        chest.update(true, false);
    }

    private static void raise(Block block, List<String> creatures, SplittableRandom random) {
        if (creatures.isEmpty()) {
            return;
        }
        String name = creatures.get(random.nextInt(creatures.size()));
        NamespacedKey key = NamespacedKey.fromString(name.toLowerCase(java.util.Locale.ROOT));
        EntityType type = key == null ? null : Registry.ENTITY_TYPE.get(key);
        BlockState state = block.getState();
        if (type == null || !(state instanceof CreatureSpawner spawner)) {
            LOGGER.warning(() -> "modules/skygrid.conf names the creature '" + name + "', which is none.");
            return;
        }
        spawner.setSpawnedType(type);
        spawner.update(true, false);
    }

    private static void setIfAir(Block block, Material material) {
        if (block.getType().isAir()) {
            block.setType(material, false);
        }
    }

    private Optional<Material> material(String name) {
        return known.computeIfAbsent(name, written -> {
            Material material = Material.matchMaterial(written);
            if (material == null || !material.isBlock()) {
                LOGGER.warning(() -> "modules/skygrid.conf names '" + written + "', which is no block.");
                return Optional.empty();
            }
            return Optional.of(material);
        });
    }

    /** The dimension a world is: its own key when a palette names it, and its kind otherwise. */
    private String dimensionOf(World world) {
        String key = world.getKey().asString();
        if (config.palettes().containsKey(key)) {
            return key;
        }
        return switch (world.getEnvironment()) {
            case NETHER -> SkyGridConfiguration.NETHER;
            case THE_END -> SkyGridConfiguration.END;
            default -> SkyGridConfiguration.OVERWORLD;
        };
    }
}
