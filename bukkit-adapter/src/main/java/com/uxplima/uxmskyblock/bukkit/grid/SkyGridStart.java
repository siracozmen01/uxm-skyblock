package com.uxplima.uxmskyblock.bukkit.grid;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import org.bukkit.Location;
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
import com.uxplima.uxmskyblock.bukkit.poseidon.WreckTemplate;
import com.uxplima.uxmskyblock.bukkit.poseidon.WreckTemplates;
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

    /** Mixed into a block's roll for whether it is a structure. */
    private static final long STRUCTURES = 0x6A1DL;

    private static final Logger LOGGER = Logger.getLogger(SkyGridStart.class.getName());

    private final SchedulerPort scheduler;
    private final SkyGridConfiguration config;
    private final Map<String, Optional<Material>> known = new ConcurrentHashMap<>();
    private final Set<String> warned = ConcurrentHashMap.newKeySet();
    private final WreckTemplates templates;
    private final ChestTable loot;

    /** Fills a chest from a loot table by its name; answers false when there is no such table. */
    @FunctionalInterface
    public interface ChestTable {
        boolean fill(Inventory inventory, String table, java.util.Random random, Location at);
    }

    /** A grid with no structures, its chests filled from the chest items. */
    public SkyGridStart(SchedulerPort scheduler, SkyGridConfiguration config) {
        this(scheduler, config, key -> Optional.empty(), (inventory, table, random, at) -> false);
    }

    public SkyGridStart(
            SchedulerPort scheduler, SkyGridConfiguration config, WreckTemplates templates, ChestTable loot) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.templates = Objects.requireNonNull(templates, "templates must not be null");
        this.loot = Objects.requireNonNull(loot, "loot must not be null");
    }

    /** Every template the palettes' structures name, for reading once the server is up. */
    public java.util.List<String> templateKeys() {
        return config.palettes().values().stream()
                .flatMap(palette -> palette.structures().stream())
                .map(SkyGridConfiguration.Mini::template)
                .distinct()
                .toList();
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
        paint(world, palette, cy, fromX, toX, fromZ, toZ);
        for (int dx = layout.alignUp(fromX - cx); dx <= toX - cx; dx += layout.spacing()) {
            for (int dz = layout.alignUp(fromZ - cz); dz <= toZ - cz; dz += layout.spacing()) {
                for (int dy = layout.alignUp(-layout.below()); dy <= layout.above(); dy += layout.spacing()) {
                    if ((dx == 0 && dy == 0 && dz == 0) || !layout.isNode(dx, dy, dz)) {
                        continue;
                    }
                    long node =
                            seed ^ (dx * 0x9E3779B97F4A7C15L) ^ (dy * 0xBF58476D1CE4E5B9L) ^ (dz * 0x94D049BB133111EBL);
                    SplittableRandom random = new SplittableRandom(node);
                    Block block = world.getBlockAt(cx + dx, cy + dy, cz + dz);
                    if (!block.getType().isAir()) {
                        continue;
                    }
                    // Its own roll, so the blocks of the grid stand as they did before structures.
                    SplittableRandom rare = new SplittableRandom(node ^ STRUCTURES);
                    if (built(block, palette, rare, fromX, toX, fromZ, toZ)) {
                        continue;
                    }
                    Material material = pick(palette.blocks(), random);
                    block.setType(material, false);
                    dress(block, material, palette, random);
                }
            }
        }
    }

    /** Fills a chest or sets a spawner the grid laid. */
    private void dress(Block block, Material material, SkyGridConfiguration.Palette palette, SplittableRandom random) {
        if (material == Material.CHEST) {
            stock(block, palette, random);
        } else if (material == Material.SPAWNER) {
            raise(block, palette.spawners(), random);
        }
    }

    /**
     * Lays one of the palette's structures at a block of the grid, by chance: stood on the block's place, centred on
     * it, into air only. Answers false, leaving the block to be a block, when none is chosen, or the one chosen is
     * not read, is wider or higher than the gap to the next block, or reaches out of this chunk.
     */
    private boolean built(
            Block at,
            SkyGridConfiguration.Palette palette,
            SplittableRandom rare,
            int fromX,
            int toX,
            int fromZ,
            int toZ) {
        double roll = rare.nextDouble();
        double reached = 0;
        for (SkyGridConfiguration.Mini mini : palette.structures()) {
            reached += mini.chance();
            if (roll >= reached) {
                continue;
            }
            Optional<WreckTemplate> found = templates.load(mini.template());
            if (found.isEmpty()) {
                return false;
            }
            WreckTemplate template = found.get();
            int height = template.pieces().stream()
                            .mapToInt(WreckTemplate.Piece::y)
                            .max()
                            .orElse(0)
                    + 1;
            int spacing = config.layout().spacing();
            int minX = at.getX() - template.sizeX() / 2;
            int minZ = at.getZ() - template.sizeZ() / 2;
            boolean tooBig = template.sizeX() >= spacing || template.sizeZ() >= spacing || height >= spacing;
            if (tooBig && warned.add(mini.template())) {
                LOGGER.warning(() -> "modules/skygrid.conf structure '" + mini.template()
                        + "' is as wide, as long or as high as the gap between one block of the grid and the next,"
                        + " so it is never laid.");
            }
            // Past the chunk the block is another region's: the block stays a block there, as it was meant to.
            if (tooBig
                    || minX < fromX
                    || minX + template.sizeX() - 1 > toX
                    || minZ < fromZ
                    || minZ + template.sizeZ() - 1 > toZ) {
                return false;
            }
            for (WreckTemplate.Piece piece : template.pieces()) {
                Block block = at.getWorld().getBlockAt(minX + piece.x(), at.getY() + piece.y(), minZ + piece.z());
                if (piece.marker() != null
                        || piece.data().getMaterial().isAir()
                        || !block.getType().isAir()) {
                    continue;
                }
                Material material = piece.data().getMaterial();
                // Set by its type first, so a chest's or a spawner's state is one on every server; a spawner has
                // no data of its own.
                block.setType(material, false);
                if (material != Material.SPAWNER) {
                    block.setBlockData(piece.data(), false);
                }
                dress(block, material, palette, rare);
            }
            return true;
        }
        return false;
    }

    /** Sets the palette's biome through the grid's columns of this chunk, a cell of four blocks at a time. */
    private void paint(
            World world, SkyGridConfiguration.Palette palette, int cy, int fromX, int toX, int fromZ, int toZ) {
        org.bukkit.block.Biome biome = biome(palette.biome());
        if (biome == null) {
            return;
        }
        GridLayout layout = config.layout();
        for (int x = fromX & ~3; x <= toX; x += 4) {
            for (int z = fromZ & ~3; z <= toZ; z += 4) {
                for (int y = (cy - layout.below()) & ~3; y <= cy + layout.above(); y += 4) {
                    world.setBiome(x, y, z, biome);
                }
            }
        }
    }

    private org.bukkit.block.@org.jspecify.annotations.Nullable Biome biome(String written) {
        if (written.isBlank()) {
            return null;
        }
        NamespacedKey key = NamespacedKey.fromString(written);
        org.bukkit.block.Biome biome = key == null
                ? null
                : io.papermc.paper.registry.RegistryAccess.registryAccess()
                        .getRegistry(io.papermc.paper.registry.RegistryKey.BIOME)
                        .get(key);
        if (biome == null && warned.add("biome:" + written)) {
            LOGGER.warning(() -> "modules/skygrid.conf names the biome '" + written
                    + "', which the server does not know. The world's own is kept.");
        }
        return biome;
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

    private void stock(Block block, SkyGridConfiguration.Palette palette, SplittableRandom random) {
        if (!(block.getState() instanceof Chest chest)) {
            return;
        }
        Inventory inventory = chest.getSnapshotInventory();
        if (!palette.loot().isBlank()
                && loot.fill(inventory, palette.loot(), new java.util.Random(random.nextLong()), block.getLocation())) {
            chest.update(true, false);
            return;
        }
        if (!palette.loot().isBlank() && warned.add("loot:" + palette.loot())) {
            LOGGER.warning(() -> "modules/skygrid.conf names the loot table '" + palette.loot()
                    + "', which loot/ has not. The chest items are used.");
        }
        List<SkyGridConfiguration.Loot> items = config.chestItems();
        if (items.isEmpty()) {
            return;
        }
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
