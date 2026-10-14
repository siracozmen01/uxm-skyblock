package com.uxplima.uxmskyblock.bukkit.cave;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

import com.uxplima.uxmskyblock.bukkit.config.CaveBlockConfiguration;
import com.uxplima.uxmskyblock.bukkit.schematic.IslandStart;
import com.uxplima.uxmskyblock.core.application.gamemode.CreationActionProvider;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.cave.CavePlan;
import com.uxplima.uxmskyblock.core.domain.cave.OreTable;

/**
 * The creation action that encloses a CaveBlock island in rock: a shell around it, rock and ores inside
 * it, and the room, tunnels and ravines of its cave carved out.
 *
 * <p>The rock spans many chunks, and on Folia each chunk belongs to the region that owns it, so each
 * chunk's column of rock is laid on that region's thread. Every chunk reads the same plan, so a tunnel
 * runs on unbroken from one chunk into the next. Only air is filled: the platform and whatever the
 * island already has are kept. The rock of each dimension is its own, so the same action encloses an
 * island in the Nether in netherrack and in the End in end stone.
 */
public final class CavernStart implements CreationActionProvider<IslandStart> {

    /** The name a preset's start list writes for this action. */
    public static final String ACTION = "uxm:cavern";

    /** Mixed into a chunk's seed for the rolls of its zones' looks. */
    private static final long LOOKS = 0x5EEDC0DEL;

    /** Mixed into the island's seed for where its pools lie. */
    private static final long POOLS = 0x9001L;

    private static final Logger LOGGER = Logger.getLogger(CavernStart.class.getName());

    private final SchedulerPort scheduler;
    private final CaveBlockConfiguration config;
    private final Map<String, Optional<Material>> blocks = new ConcurrentHashMap<>();
    private final @org.jspecify.annotations.Nullable CaveStructures structures;

    public CavernStart(SchedulerPort scheduler, CaveBlockConfiguration config) {
        this(scheduler, config, null);
    }

    /** @param structures the structures laid in the rock after it, or nothing for rock alone */
    public CavernStart(
            SchedulerPort scheduler,
            CaveBlockConfiguration config,
            @org.jspecify.annotations.Nullable CaveStructures structures) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.structures = structures;
    }

    @Override
    public String actionId() {
        return ACTION;
    }

    /** The cave reaches as far as caveblock.conf says. */
    @Override
    public java.util.List<com.uxplima.uxmskyblock.core.application.gamemode.CreationReach> reach(IslandStart start) {
        return java.util.List.of(new com.uxplima.uxmskyblock.core.application.gamemode.CreationReach(
                start.world().getName(), config.shape().radius()));
    }

    @Override
    public void apply(IslandStart start) {
        World world = start.world();
        CaveBlockConfiguration.Palette palette = config.palette(dimensionOf(world));
        CavePlan.Shape shape = config.shape();
        long seed = start.islandId().value().getMostSignificantBits()
                ^ start.islandId().value().getLeastSignificantBits();
        CavePlan plan = CavePlan.plan(seed, start.centerX(), start.y(), start.centerZ(), shape);
        Rock rock = new Rock(
                block(config.shell(), Material.BEDROCK),
                block(palette.rock(), Material.STONE),
                resolved(palette.ores()),
                block(palette.deepRock(), block(palette.rock(), Material.STONE)),
                palette.deepBelow() == Integer.MAX_VALUE ? Integer.MIN_VALUE : start.y() - palette.deepBelow(),
                resolved(palette.deepOres()),
                depth -> palette.scaleAt(depth, shape.below()));
        Zoning zoning = zoning(world, seed);
        List<Pocket> pockets = pockets(world, start, seed);
        int minX = start.centerX() - shape.radius();
        int maxX = start.centerX() + shape.radius();
        int minZ = start.centerZ() - shape.radius();
        int maxZ = start.centerZ() + shape.radius();
        for (int chunkX = minX >> 4; chunkX <= maxX >> 4; chunkX++) {
            for (int chunkZ = minZ >> 4; chunkZ <= maxZ >> 4; chunkZ++) {
                int fromX = Math.max(minX, chunkX << 4);
                int toX = Math.min(maxX, (chunkX << 4) + 15);
                int fromZ = Math.max(minZ, chunkZ << 4);
                int toZ = Math.min(maxZ, (chunkZ << 4) + 15);
                CavePlan part = plan.within(fromX, toX, fromZ, toZ);
                Chunk chunk =
                        new Chunk(fromX, toX, fromZ, toZ, seed ^ (((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL)));
                scheduler.onRegion(
                        world.getName(), chunkX, chunkZ, () -> fill(world, start, chunk, part, rock, zoning, pockets));
            }
        }
        // Queued after the rock of every chunk, so the rock never fills a hollow a structure carved.
        CaveStructures held = structures;
        if (held != null) {
            held.lay(start, rock.shell());
        }
    }

    private void fill(
            World world,
            IslandStart start,
            Chunk chunk,
            CavePlan part,
            Rock rock,
            Zoning zoning,
            List<Pocket> pockets) {
        CavePlan.Shape shape = config.shape();
        int bottom = start.y() - shape.below();
        int top = start.y() + shape.above();
        SplittableRandom random = new SplittableRandom(chunk.seed());
        // Its own rolls, so a zone's look leaves the rock and ores the same as without zones.
        SplittableRandom looks = new SplittableRandom(chunk.seed() ^ LOOKS);
        for (int x = chunk.fromX(); x <= chunk.toX(); x++) {
            for (int z = chunk.fromZ(); z <= chunk.toZ(); z++) {
                boolean wall = Math.abs(x - start.centerX()) == shape.radius()
                        || Math.abs(z - start.centerZ()) == shape.radius();
                Look look = zoning.at(x - start.centerX(), z - start.centerZ());
                for (int y = bottom; y <= top; y++) {
                    double roll = random.nextDouble();
                    if (look != null && look.biome() != null && !wall && y != bottom && y != top) {
                        world.setBiome(x, y, z, look.biome());
                    }
                    Material material;
                    // Never in the wall: a pool lies three blocks inside it.
                    Pocket pocket = pocketAt(pockets, x, y, z);
                    if (wall || y == bottom || y == top) {
                        material = rock.shell();
                    } else if (pocket != null) {
                        setIfAir(
                                world.getBlockAt(x, y, z), y <= pocket.surface() ? pocket.liquid() : Material.CAVE_AIR);
                        continue;
                    } else if (part.carved(x, y, z)) {
                        continue;
                    } else if (y < rock.deepFrom() || (look != null && look.deep())) {
                        material = rock.deepOres()
                                .pick(roll, start.y() - y, rock.scale().applyAsDouble(start.y() - y))
                                .map(this::known)
                                .orElse(rock.deepRock());
                    } else {
                        material = rock.ores()
                                .pick(roll, start.y() - y, rock.scale().applyAsDouble(start.y() - y))
                                .map(this::known)
                                .orElse(rock.rock());
                    }
                    Block block = world.getBlockAt(x, y, z);
                    setIfAir(block, material);
                    // Never the shell: nothing is carved within two blocks of it, so it is no floor and no ceiling.
                    if (look != null && block.getType() == material) {
                        dress(block, look, looks, part.carved(x, y + 1, z), part.carved(x, y - 1, z));
                    }
                }
            }
        }
    }

    /**
     * Covers a block of rock in its zone's look: a floor, with something stood on it, when the cave opens above it; a
     * ceiling, with something hung under it, when the cave opens below it. A block that is both keeps the floor's
     * cover, and still has something hung under it.
     */
    private void dress(Block rock, Look look, SplittableRandom rolls, boolean floor, boolean ceiling) {
        boolean covered = false;
        if (floor) {
            Optional<Material> cover = look.floor().pick(rolls.nextDouble()).map(this::known);
            cover.ifPresent(block -> rock.setType(block, false));
            covered = cover.isPresent();
            look.onFloor()
                    .pick(rolls.nextDouble())
                    .map(this::known)
                    .ifPresent(stood -> hang(rock.getRelative(BlockFace.UP), stood, BlockFace.UP));
        }
        if (ceiling) {
            Optional<Material> cover = look.ceiling().pick(rolls.nextDouble()).map(this::known);
            if (!covered) {
                cover.ifPresent(block -> rock.setType(block, false));
            }
            look.underCeiling()
                    .pick(rolls.nextDouble())
                    .map(this::known)
                    .ifPresent(hung -> hang(rock.getRelative(BlockFace.DOWN), hung, BlockFace.DOWN));
        }
    }

    /** Puts a block into the cave beside the rock, pointing away from it when it points: a dripstone up or down. */
    private static void hang(Block into, Material material, BlockFace away) {
        if (!into.getType().isAir()) {
            return;
        }
        org.bukkit.block.data.BlockData data = material.createBlockData();
        if (data.getAsString().contains("vertical_direction=")) {
            try {
                data = material.createBlockData("[vertical_direction=" + (away == BlockFace.UP ? "up" : "down") + "]");
            } catch (IllegalArgumentException unread) {
                // A server that cannot read the state keeps the block's own; it still stands, pointing its own way.
            }
        }
        into.setBlockData(data, false);
    }

    private static @org.jspecify.annotations.Nullable Pocket pocketAt(List<Pocket> pockets, int x, int y, int z) {
        for (Pocket pocket : pockets) {
            if (pocket.holds(x, y, z)) {
                return pocket;
            }
        }
        return null;
    }

    /** How many ways round the island a pool is tried before it is left out. */
    private static final int TURNS = 24;

    /**
     * The pools of the world's dimension, each laid where the island's seed puts it: round the island beyond the room,
     * inside the rock with two blocks to spare before the shell, and apart from every other pool. One that cannot fit
     * there is not laid.
     */
    List<Pocket> pockets(World world, IslandStart start, long seed) {
        CavePlan.Shape shape = config.shape();
        SplittableRandom random = new SplittableRandom(seed ^ POOLS);
        List<Pocket> pockets = new java.util.ArrayList<>();
        for (CaveBlockConfiguration.Pool pool : config.poolsOf(dimensionOf(world))) {
            Material liquid = block(pool.block(), Material.AIR);
            int high = Math.max(1, pool.radius() / 2);
            int nearest = shape.room() + 3 + pool.radius();
            int farthest = shape.radius() - 3 - pool.radius();
            int deepest = Math.min(pool.depthTo(), shape.below() - 3 - high);
            int highest = Math.max(pool.depthFrom(), -shape.above() + 3 + high);
            for (int one = 0; one < pool.count(); one++) {
                if (liquid == Material.AIR || farthest < nearest || deepest < highest) {
                    LOGGER.warning(() -> "modules/caveblock.conf pools: '" + pool.block()
                            + "' is no block, or the pool does not fit in the rock. It is not laid.");
                    break;
                }
                double angle = random.nextDouble() * Math.PI * 2;
                int distance = nearest + random.nextInt(farthest - nearest + 1);
                int middleY = start.y() - (highest + random.nextInt(deepest - highest + 1));
                int surface = middleY - high - 1 + (int) Math.round(pool.fill() * (2 * high + 1));
                // Two pools that touch share their blocks and each its own surface: water would stand on air. A pool
                // is turned round the island until it touches none, and one that touches each way is not laid.
                Pocket laid = null;
                for (int turn = 0; turn < TURNS && laid == null; turn++) {
                    double at = angle + turn * Math.PI * 2 / TURNS;
                    Pocket tried = new Pocket(
                            distance,
                            start.centerX() + (int) Math.round(Math.cos(at) * distance),
                            middleY,
                            start.centerZ() + (int) Math.round(Math.sin(at) * distance),
                            pool.radius(),
                            high,
                            liquid,
                            surface);
                    if (pockets.stream().noneMatch(tried::touches)) {
                        laid = tried;
                    }
                }
                if (laid == null) {
                    LOGGER.fine(() -> "A pool of '" + pool.block() + "' found no room apart from the others.");
                    continue;
                }
                pockets.add(laid);
            }
        }
        return List.copyOf(pockets);
    }

    /**
     * One pool: a flattened sphere, its middle {@code distance} from the island's centre, its liquid up to and
     * including {@code surface}, air above.
     */
    record Pocket(int distance, int x, int y, int z, int radius, int high, Material liquid, int surface) {

        /** Whether the two share a block or stand side by side, a block of rock between them at the least. */
        boolean touches(Pocket other) {
            double apart = Math.hypot(x - other.x, z - other.z);
            return apart < radius + other.radius + 2 && Math.abs(y - other.y) <= high + other.high + 1;
        }

        boolean holds(int atX, int atY, int atZ) {
            double across = (Math.pow(atX - x, 2) + Math.pow(atZ - z, 2)) / ((double) radius * radius);
            double up = Math.pow(atY - y, 2) / ((double) high * high);
            return across + up <= 1;
        }
    }

    /** The zones of the world's dimension, each given its share of the round, turned by the island. */
    private Zoning zoning(World world, long seed) {
        List<Look> looks = config.zonesOf(dimensionOf(world)).stream()
                .map(zone -> new Look(
                        biome(zone.biome()),
                        zone.deep(),
                        resolved(zone.floor()),
                        resolved(zone.onFloor()),
                        resolved(zone.ceiling()),
                        resolved(zone.underCeiling())))
                .toList();
        double turn = new SplittableRandom(seed ^ LOOKS).nextDouble() * Math.PI * 2;
        return new Zoning(looks, turn, config.shape().room() + 2);
    }

    private org.bukkit.block.@org.jspecify.annotations.Nullable Biome biome(String written) {
        if (written.isBlank()) {
            return null;
        }
        org.bukkit.NamespacedKey key = org.bukkit.NamespacedKey.fromString(written);
        org.bukkit.block.Biome biome = key == null
                ? null
                : io.papermc.paper.registry.RegistryAccess.registryAccess()
                        .getRegistry(io.papermc.paper.registry.RegistryKey.BIOME)
                        .get(key);
        if (biome == null && blocks.putIfAbsent("biome:" + written, Optional.empty()) == null) {
            LOGGER.warning(() -> "modules/caveblock.conf zones name the biome '" + written
                    + "', which the server does not know. The island's own is kept.");
        }
        return biome;
    }

    /** One zone's look, its blocks read. */
    record Look(
            org.bukkit.block.@org.jspecify.annotations.Nullable Biome biome,
            boolean deep,
            OreTable floor,
            OreTable onFloor,
            OreTable ceiling,
            OreTable underCeiling) {}

    /** Which zone a column is in, by its angle round the island; none in and around the room players arrive in. */
    record Zoning(List<Look> looks, double turn, int room) {

        @org.jspecify.annotations.Nullable Look at(int dx, int dz) {
            if (looks.isEmpty() || dx * dx + dz * dz <= room * room) {
                return null;
            }
            double angle = Math.atan2(dz, dx) - turn;
            double round = Math.PI * 2;
            angle = ((angle % round) + round) % round;
            return looks.get(Math.min(looks.size() - 1, (int) (angle / (round / looks.size()))));
        }
    }

    private static void setIfAir(Block block, Material material) {
        if (block.getType().isAir()) {
            // No physics: rock laid a chunk at a time must not fall or flow into the chunk laid next.
            block.setType(material, false);
        }
    }

    /** Keeps only the ores that name a block, having said once which do not. */
    private OreTable resolved(OreTable table) {
        return new OreTable(table.ores().stream()
                .filter(ore -> block(ore.block(), Material.AIR) != Material.AIR)
                .toList());
    }

    private Material known(String name) {
        return block(name, Material.STONE);
    }

    private Material block(String name, Material fallback) {
        Optional<Material> found = blocks.computeIfAbsent(name, written -> {
            Material material = Material.matchMaterial(written);
            if (material == null || !material.isBlock()) {
                LOGGER.warning(() -> "modules/caveblock.conf names '" + written + "', which is no block.");
                return Optional.empty();
            }
            return Optional.of(material);
        });
        return found.orElse(fallback);
    }

    /** The dimension a world is: its own key when a palette names it, and its kind otherwise. */
    private String dimensionOf(World world) {
        String key = world.getKey().asString();
        if (config.palettes().containsKey(key)) {
            return key;
        }
        return switch (world.getEnvironment()) {
            case NETHER -> CaveBlockConfiguration.NETHER;
            case THE_END -> CaveBlockConfiguration.END;
            default -> CaveBlockConfiguration.OVERWORLD;
        };
    }

    private record Rock(
            Material shell,
            Material rock,
            OreTable ores,
            Material deepRock,
            int deepFrom,
            OreTable deepOres,
            java.util.function.IntToDoubleFunction scale) {}

    private record Chunk(int fromX, int toX, int fromZ, int toZ, long seed) {}
}
