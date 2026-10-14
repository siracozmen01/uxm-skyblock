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
                resolved(palette.deepOres()));
        Zoning zoning = zoning(world, seed);
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
                        world.getName(), chunkX, chunkZ, () -> fill(world, start, chunk, part, rock, zoning));
            }
        }
        // Queued after the rock of every chunk, so the rock never fills a hollow a structure carved.
        CaveStructures held = structures;
        if (held != null) {
            held.lay(start, rock.shell());
        }
    }

    private void fill(World world, IslandStart start, Chunk chunk, CavePlan part, Rock rock, Zoning zoning) {
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
                    if (wall || y == bottom || y == top) {
                        material = rock.shell();
                    } else if (part.carved(x, y, z)) {
                        continue;
                    } else if (y < rock.deepFrom() || (look != null && look.deep())) {
                        material = rock.deepOres().pick(roll).map(this::known).orElse(rock.deepRock());
                    } else {
                        material = rock.ores().pick(roll).map(this::known).orElse(rock.rock());
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
            Material shell, Material rock, OreTable ores, Material deepRock, int deepFrom, OreTable deepOres) {}

    private record Chunk(int fromX, int toX, int fromZ, int toZ, long seed) {}
}
