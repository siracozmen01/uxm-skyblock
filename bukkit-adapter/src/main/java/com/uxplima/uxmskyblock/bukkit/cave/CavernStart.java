package com.uxplima.uxmskyblock.bukkit.cave;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

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

    private static final Logger LOGGER = Logger.getLogger(CavernStart.class.getName());

    private final SchedulerPort scheduler;
    private final CaveBlockConfiguration config;
    private final Map<String, Optional<Material>> blocks = new ConcurrentHashMap<>();

    public CavernStart(SchedulerPort scheduler, CaveBlockConfiguration config) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");
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
                resolved(palette.deepOres()));
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
                scheduler.onRegion(world.getName(), chunkX, chunkZ, () -> fill(world, start, chunk, part, rock));
            }
        }
    }

    private void fill(World world, IslandStart start, Chunk chunk, CavePlan part, Rock rock) {
        CavePlan.Shape shape = config.shape();
        int bottom = start.y() - shape.below();
        int top = start.y() + shape.above();
        SplittableRandom random = new SplittableRandom(chunk.seed());
        for (int x = chunk.fromX(); x <= chunk.toX(); x++) {
            for (int z = chunk.fromZ(); z <= chunk.toZ(); z++) {
                boolean wall = Math.abs(x - start.centerX()) == shape.radius()
                        || Math.abs(z - start.centerZ()) == shape.radius();
                for (int y = bottom; y <= top; y++) {
                    double roll = random.nextDouble();
                    Material material;
                    if (wall || y == bottom || y == top) {
                        material = rock.shell();
                    } else if (part.carved(x, y, z)) {
                        continue;
                    } else if (y < rock.deepFrom()) {
                        material = rock.deepOres().pick(roll).map(this::known).orElse(rock.deepRock());
                    } else {
                        material = rock.ores().pick(roll).map(this::known).orElse(rock.rock());
                    }
                    setIfAir(world.getBlockAt(x, y, z), material);
                }
            }
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
            Material shell, Material rock, OreTable ores, Material deepRock, int deepFrom, OreTable deepOres) {}

    private record Chunk(int fromX, int toX, int fromZ, int toZ, long seed) {}
}
