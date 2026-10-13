package com.uxplima.uxmskyblock.bukkit.poseidon;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Waterlogged;

import com.uxplima.uxmskyblock.bukkit.config.PoseidonConfiguration;
import com.uxplima.uxmskyblock.bukkit.schematic.IslandStart;
import com.uxplima.uxmskyblock.core.application.gamemode.CreationActionProvider;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import org.jspecify.annotations.Nullable;

/**
 * A creation action that lays wrecks or ruins on the sea floor around a Poseidon island: templates the
 * operator names, set a fixed distance from the centre and spread evenly around it.
 *
 * <p>A template only fills water and air, so the island, the floor and a wreck laid before it are kept.
 * A block that can hold water holds it where it stands in the sea. Each chest gets a loot table from the
 * operator's list, in turn, and a data marker that asks for a chest gets one. Which templates are laid,
 * and where around the island, come from the island's id, so the same island is always laid the same.
 *
 * <p>A sea with a thicker floor may have its templates sunk into it: they then fill the floor's own blocks as well,
 * down to the floor's bottom and no further, so a ruin lies half buried and nothing hangs below the sea.
 *
 * <p>Every chunk a template reaches is laid on the thread of the region that owns it, and the centre
 * chunk at once, on the thread running the creation.
 */
public final class WreckStart implements CreationActionProvider<IslandStart> {

    private static final String CHEST_MARKER = "chest";

    private final String actionId;
    private final PoseidonConfiguration.Wreck wreck;
    private final WreckTemplates templates;
    private final ChestLoot loot;
    private final SchedulerPort scheduler;
    private final Seabed seabed;

    /**
     * Where a template is laid in the sea.
     *
     * @param waterBelowStart how far below the island's start the sea's lowest water lies, where a template's
     *     lowest layer stands
     * @param sink how many blocks a template is laid lower than that, into the floor
     * @param thickness how many blocks thick the floor is; nothing is laid below it
     * @param floor the blocks of the floor a sunk template may fill
     */
    public record Seabed(int waterBelowStart, int sink, int thickness, java.util.Set<Material> floor) {

        public Seabed {
            floor = java.util.Set.copyOf(floor);
            if (sink < 0 || thickness < 1) {
                throw new IllegalArgumentException("a sink of at least 0 into a floor of at least 1");
            }
        }

        /** A floor of one block that nothing is sunk into, as Poseidon's ocean has. */
        public static Seabed over(int waterBelowStart) {
            return new Seabed(waterBelowStart, 0, 1, java.util.Set.of());
        }
    }

    public WreckStart(
            String actionId,
            PoseidonConfiguration.Wreck wreck,
            PoseidonConfiguration.Ocean ocean,
            WreckTemplates templates,
            ChestLoot loot,
            SchedulerPort scheduler) {
        this(actionId, wreck, Seabed.over(ocean.depth()), templates, loot, scheduler);
    }

    public WreckStart(
            String actionId,
            PoseidonConfiguration.Wreck wreck,
            Seabed seabed,
            WreckTemplates templates,
            ChestLoot loot,
            SchedulerPort scheduler) {
        this.seabed = Objects.requireNonNull(seabed, "seabed must not be null");
        this.actionId = Objects.requireNonNull(actionId, "actionId must not be null");
        this.wreck = Objects.requireNonNull(wreck, "wreck must not be null");
        this.templates = Objects.requireNonNull(templates, "templates must not be null");
        this.loot = Objects.requireNonNull(loot, "loot must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
    }

    @Override
    public String actionId() {
        return actionId;
    }

    /** Each wreck lies as far out as poseidon.conf says, and reaches as wide as its widest template past that. */
    @Override
    public java.util.List<com.uxplima.uxmskyblock.core.application.gamemode.CreationReach> reach(IslandStart start) {
        int widest = 0;
        for (String key : wreck.templates()) {
            widest = Math.max(
                    widest,
                    templates
                            .load(key)
                            .map(template -> Math.max(template.sizeX(), template.sizeZ()))
                            .orElse(0));
        }
        return java.util.List.of(new com.uxplima.uxmskyblock.core.application.gamemode.CreationReach(
                start.world().getName(), wreck.distance() + widest));
    }

    @Override
    public void apply(IslandStart start) {
        if (wreck.templates().isEmpty() || wreck.count() < 1) {
            return;
        }
        Random random = new Random(start.islandId().value().getLeastSignificantBits() ^ actionId.hashCode());
        double turn = random.nextDouble() * Math.PI * 2;
        int floorY = start.y() - seabed.waterBelowStart() - seabed.sink();
        for (int laid = 0; laid < wreck.count(); laid++) {
            String key = wreck.templates().get(random.nextInt(wreck.templates().size()));
            long seed = random.nextLong();
            Optional<WreckTemplate> template = templates.load(key);
            if (template.isEmpty()) {
                continue;
            }
            double angle = turn + laid * Math.PI * 2 / wreck.count();
            int x = start.centerX()
                    + (int) Math.round(Math.cos(angle) * wreck.distance())
                    - template.get().sizeX() / 2;
            int z = start.centerZ()
                    + (int) Math.round(Math.sin(angle) * wreck.distance())
                    - template.get().sizeZ() / 2;
            lay(start, template.get(), x, floorY, z, seed);
        }
    }

    private void lay(IslandStart start, WreckTemplate template, int originX, int originY, int originZ, long seed) {
        World world = start.world();
        List<WreckTemplate.Piece> pieces = new ArrayList<>(template.pieces());
        pieces.sort(Comparator.comparingInt(WreckTemplate.Piece::y)
                .thenComparingInt(WreckTemplate.Piece::x)
                .thenComparingInt(WreckTemplate.Piece::z));
        Map<Long, List<Runnable>> byChunk = new HashMap<>();
        int chests = 0;
        // The floor's bottom layer: a template sunk into it reaches no lower, so nothing hangs below the sea.
        int lowest = start.y() - seabed.waterBelowStart() - seabed.thickness();
        for (WreckTemplate.Piece piece : pieces) {
            if (originY + piece.y() < lowest) {
                continue;
            }
            BlockData data = blockOf(piece);
            if (data == null) {
                continue;
            }
            String loot = null;
            if (isContainer(data.getMaterial()) && !wreck.loot().isEmpty()) {
                loot = wreck.loot().get(chests % wreck.loot().size());
                chests++;
            }
            int x = originX + piece.x();
            int y = originY + piece.y();
            int z = originZ + piece.z();
            String lootKey = loot;
            long chestSeed = seed + chests;
            byChunk.computeIfAbsent(chunkKey(x >> 4, z >> 4), key -> new ArrayList<>())
                    .add(() -> place(world.getBlockAt(x, y, z), data, lootKey, chestSeed));
        }
        int centreX = start.centerX() >> 4;
        int centreZ = start.centerZ() >> 4;
        for (Map.Entry<Long, List<Runnable>> chunk : byChunk.entrySet()) {
            int chunkX = (int) (chunk.getKey() >> 32);
            int chunkZ = (int) (long) chunk.getKey();
            Runnable all = () -> chunk.getValue().forEach(Runnable::run);
            if (chunkX == centreX && chunkZ == centreZ) {
                all.run();
            } else {
                scheduler.onRegion(world.getName(), chunkX, chunkZ, all);
            }
        }
    }

    /** The block a piece lays, or null for one that lays nothing: air, and a marker that asks for no chest. */
    private static @Nullable BlockData blockOf(WreckTemplate.Piece piece) {
        if (piece.marker() != null) {
            return CHEST_MARKER.equals(piece.marker().trim()) ? Material.CHEST.createBlockData() : null;
        }
        Material type = piece.data().getMaterial();
        if (type.isAir()
                || type == Material.STRUCTURE_BLOCK
                || type == Material.STRUCTURE_VOID
                || type == Material.JIGSAW) {
            return null;
        }
        return piece.data();
    }

    private void place(Block block, BlockData data, @Nullable String lootKey, long seed) {
        Material there = block.getType();
        boolean water = there == Material.WATER;
        if (!there.isAir() && !water && !seabed.floor().contains(there)) {
            return;
        }
        BlockData laid = data.clone();
        if (laid instanceof Waterlogged waterlogged) {
            waterlogged.setWaterlogged(water);
        }
        block.setBlockData(laid, false);
        if (lootKey != null) {
            loot.give(block, lootKey, seed);
        }
    }

    private static boolean isContainer(Material type) {
        return type == Material.CHEST || type == Material.TRAPPED_CHEST || type == Material.BARREL;
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }
}
