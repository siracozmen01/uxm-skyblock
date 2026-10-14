package com.uxplima.uxmskyblock.bukkit.cave;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.EntityType;

import com.uxplima.uxmskyblock.bukkit.config.CaveBlockConfiguration;
import com.uxplima.uxmskyblock.bukkit.poseidon.ChestLoot;
import com.uxplima.uxmskyblock.bukkit.poseidon.WreckTemplate;
import com.uxplima.uxmskyblock.bukkit.poseidon.WreckTemplates;
import com.uxplima.uxmskyblock.bukkit.schematic.IslandStart;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.cave.CavePlan;
import org.jspecify.annotations.Nullable;

/**
 * Lays the structures a CaveBlock island's rock holds, after the rock: each kind spread evenly around the island at its
 * distance, its floor at its depth below the arrival. A structure replaces whatever rock it meets, never the shell,
 * and its hollow is carved out; a kind that would reach past the shell is not laid. Each chest and barrel gets the next
 * loot table of its kind, and each spawner the creature its kind names.
 *
 * <p>Every block is laid on the thread of the region that owns its chunk, queued after the rock of that chunk, so the
 * rock never fills a hollow carved before it.
 */
public final class CaveStructures {

    private static final Logger LOGGER = Logger.getLogger(CaveStructures.class.getName());

    private final Map<String, CaveBlockConfiguration.Structure> kinds;
    private final CavePlan.Shape shape;
    private final WreckTemplates templates;
    private final ChestLoot loot;
    private final SchedulerPort scheduler;
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    public CaveStructures(
            Map<String, CaveBlockConfiguration.Structure> kinds,
            CavePlan.Shape shape,
            WreckTemplates templates,
            ChestLoot loot,
            SchedulerPort scheduler) {
        this.kinds = Map.copyOf(kinds);
        this.shape = Objects.requireNonNull(shape, "shape must not be null");
        this.templates = Objects.requireNonNull(templates, "templates must not be null");
        this.loot = Objects.requireNonNull(loot, "loot must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
    }

    /** Every template the kinds name, for reading once the server is up. */
    public List<String> templateKeys() {
        return kinds.values().stream()
                .flatMap(kind -> kind.templates().stream())
                .distinct()
                .toList();
    }

    /** The turns tried for a structure whose own place is taken, each a twenty-fourth of the way round. */
    private static final int TURNS = 24;

    /**
     * Lays every kind around the island, each block on its region's thread. Answers how many structures.
     *
     * <p>Every structure of every kind shares one round: each is given its own share of the way round the island, so
     * a dungeon and a geode at nearly the same depth stand apart. One whose place is taken by another, or reaches past
     * the shell, is turned further round until it fits, and is not laid when nowhere does.
     */
    public int lay(IslandStart start, Material shell) {
        long seed = start.islandId().value().getMostSignificantBits()
                ^ start.islandId().value().getLeastSignificantBits();
        Random random = new Random(seed);
        List<Planned> planned = new ArrayList<>();
        for (String name : kinds.keySet().stream().sorted().toList()) {
            CaveBlockConfiguration.Structure kind = Objects.requireNonNull(kinds.get(name));
            if (kind.templates().isEmpty()) {
                continue;
            }
            for (int one = 0; one < kind.count(); one++) {
                String key =
                        kind.templates().get(random.nextInt(kind.templates().size()));
                long chestSeed = random.nextLong();
                templates
                        .load(key)
                        .ifPresent(template -> planned.add(new Planned(name, key, kind, template, chestSeed)));
            }
        }
        double turn = random.nextDouble() * Math.PI * 2;
        List<Box> taken = new ArrayList<>();
        int laid = 0;
        for (int slot = 0; slot < planned.size(); slot++) {
            Planned one = planned.get(slot);
            Optional<Box> place = Optional.empty();
            for (int tried = 0; tried < TURNS && place.isEmpty(); tried++) {
                double angle = turn + slot * Math.PI * 2 / planned.size() + tried * Math.PI * 2 / TURNS;
                Box box = boxAt(start, one, angle);
                if (fits(start, one.template(), box.minX(), box.minY(), box.minZ())
                        && taken.stream().noneMatch(box::touches)) {
                    place = Optional.of(box);
                }
            }
            if (place.isEmpty()) {
                warnOnce(one.name(), one.key());
                continue;
            }
            taken.add(place.get());
            place(
                    start.world(),
                    one.template(),
                    place.get().minX(),
                    place.get().minY(),
                    place.get().minZ(),
                    one.kind(),
                    shell,
                    one.seed());
            laid++;
        }
        return laid;
    }

    /** Where a structure stands with its middle at its distance from the centre, at that angle. */
    private static Box boxAt(IslandStart start, Planned one, double angle) {
        WreckTemplate template = one.template();
        int middleX =
                start.centerX() + (int) Math.round(Math.cos(angle) * one.kind().distance());
        int middleZ =
                start.centerZ() + (int) Math.round(Math.sin(angle) * one.kind().distance());
        int minX = middleX - template.sizeX() / 2;
        int minZ = middleZ - template.sizeZ() / 2;
        int floor = start.y() - one.kind().depth();
        return new Box(
                minX, floor, minZ, minX + template.sizeX() - 1, floor + height(template), minZ + template.sizeZ() - 1);
    }

    private static int height(WreckTemplate template) {
        return template.pieces().stream().mapToInt(WreckTemplate.Piece::y).max().orElse(0);
    }

    private record Planned(
            String name, String key, CaveBlockConfiguration.Structure kind, WreckTemplate template, long seed) {}

    /** The blocks a structure takes, its corners included. */
    private record Box(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

        /** Whether two structures share a block or stand wall to wall, which would break one's wall into the other. */
        boolean touches(Box other) {
            return minX <= other.maxX + 1
                    && other.minX <= maxX + 1
                    && minY <= other.maxY + 1
                    && other.minY <= maxY + 1
                    && minZ <= other.maxZ + 1
                    && other.minZ <= maxZ + 1;
        }
    }

    /** Whether the template stands inside the shell, which it never replaces. */
    boolean fits(IslandStart start, WreckTemplate template, int originX, int floor, int originZ) {
        int height = template.pieces().stream()
                .mapToInt(WreckTemplate.Piece::y)
                .max()
                .orElse(0);
        int inner = shape.radius() - 1;
        return originX >= start.centerX() - inner
                && originX + template.sizeX() - 1 <= start.centerX() + inner
                && originZ >= start.centerZ() - inner
                && originZ + template.sizeZ() - 1 <= start.centerZ() + inner
                && floor > start.y() - shape.below()
                && floor + height < start.y() + shape.above();
    }

    private void place(
            World world,
            WreckTemplate template,
            int originX,
            int floor,
            int originZ,
            CaveBlockConfiguration.Structure kind,
            Material shell,
            long seed) {
        Map<Long, List<Runnable>> byChunk = new HashMap<>();
        int chests = 0;
        for (WreckTemplate.Piece piece : template.pieces()) {
            if (piece.marker() != null) {
                continue;
            }
            BlockData data = piece.data();
            String table = null;
            if (isContainer(data.getMaterial()) && !kind.loot().isEmpty()) {
                table = kind.loot().get(chests % kind.loot().size());
                chests++;
            }
            int x = originX + piece.x();
            int y = floor + piece.y();
            int z = originZ + piece.z();
            String lootKey = table;
            long chestSeed = seed + chests;
            byChunk.computeIfAbsent(((long) (x >> 4) << 32) | ((z >> 4) & 0xFFFFFFFFL), key -> new ArrayList<>())
                    .add(() -> lay(world.getBlockAt(x, y, z), data, shell, lootKey, chestSeed, kind.spawner()));
        }
        for (Map.Entry<Long, List<Runnable>> chunk : byChunk.entrySet()) {
            int chunkX = (int) (chunk.getKey() >> 32);
            int chunkZ = (int) (long) chunk.getKey();
            scheduler.onRegion(
                    world.getName(), chunkX, chunkZ, () -> chunk.getValue().forEach(Runnable::run));
        }
    }

    private void lay(Block block, BlockData data, Material shell, @Nullable String table, long seed, String spawner) {
        if (block.getType() == shell) {
            return;
        }
        Material type = data.getMaterial();
        // Air in a template the server holds is the hollow it carves, as cave air is in a schematic.
        block.setBlockData(type == Material.AIR ? Material.CAVE_AIR.createBlockData() : data, false);
        if (table != null) {
            loot.give(block, table, seed);
        }
        if (!spawner.isEmpty() && (type == Material.SPAWNER || type == Material.TRIAL_SPAWNER)) {
            // Set by its type too, which makes the block's state a spawner on every server; a spawner has no data.
            block.setType(type, false);
            spawn(block, spawner);
        }
    }

    /** Sets what a spawner or a trial spawner spawns. */
    private void spawn(Block block, String spawner) {
        EntityType creature = org.bukkit.Registry.ENTITY_TYPE.get(NamespacedKey.minecraft(spawner));
        if (creature == null) {
            warnOnce("spawner", spawner);
            return;
        }
        BlockState state = block.getState();
        if (state instanceof CreatureSpawner cage) {
            cage.setSpawnedType(creature);
            cage.update(true, false);
        } else if (state instanceof org.bukkit.block.TrialSpawner trial) {
            trial.getNormalConfiguration().setSpawnedType(creature);
            trial.getOminousConfiguration().setSpawnedType(creature);
            trial.update(true, false);
        }
    }

    private void warnOnce(String kind, String what) {
        if (warned.add(kind + "/" + what)) {
            LOGGER.warning(() -> "modules/caveblock.conf structures: " + kind + " cannot use '" + what
                    + "': it is no creature, or the structure would not fit inside the shell. It is not laid.");
        }
    }

    private static boolean isContainer(Material type) {
        return type == Material.CHEST || type == Material.TRAPPED_CHEST || type == Material.BARREL;
    }
}
