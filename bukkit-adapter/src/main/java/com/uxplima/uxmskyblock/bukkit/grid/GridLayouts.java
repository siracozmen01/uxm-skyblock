package com.uxplima.uxmskyblock.bukkit.grid;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.IntFunction;

import com.uxplima.uxmlib.schematic.Schematic;

/**
 * The small structures a SkyGrid block may be instead, built in code and written as schematics on the plugin's first
 * start, so an operator who wants another edits the file rather than code. Each is three blocks wide, long and high, so
 * it fits between one block of the shipped grid and the next.
 */
public final class GridLayouts {

    /** Each shipped structure by the name its file is written under, as schematics/&lt;name&gt;.schem. */
    public static final Map<String, IntFunction<Schematic>> SHIPPED = shipped();

    static final int SIDE = 3;
    private static final String CHEST = "minecraft:chest[facing=north,type=single,waterlogged=false]";

    private GridLayouts() {}

    private static Map<String, IntFunction<Schematic>> shipped() {
        Map<String, IntFunction<Schematic>> all = new LinkedHashMap<>();
        all.put("grid_tree", GridLayouts::tree);
        all.put("grid_well", GridLayouts::well);
        all.put("grid_fortress", GridLayouts::fortress);
        all.put("grid_pillar", GridLayouts::pillar);
        return all;
    }

    /** A grass block with an oak trunk two high, crowned in leaves. */
    static Schematic tree(int dataVersion) {
        Schematic.Builder builder = Schematic.builder(SIDE, SIDE, SIDE).dataVersion(dataVersion);
        builder.block(1, 0, 1, "minecraft:grass_block[snowy=false]");
        builder.block(1, 1, 1, "minecraft:oak_log[axis=y]");
        for (int x = 0; x < SIDE; x++) {
            for (int z = 0; z < SIDE; z++) {
                builder.block(x, 2, z, "minecraft:oak_leaves[distance=1,persistent=true,waterlogged=false]");
                if ((x == 1) != (z == 1)) {
                    builder.block(x, 1, z, "minecraft:oak_leaves[distance=1,persistent=true,waterlogged=false]");
                }
            }
        }
        builder.block(1, 2, 1, "minecraft:oak_log[axis=y]");
        return builder.build();
    }

    /** A floor of cobblestone with a ring round water, a chest beside it. */
    static Schematic well(int dataVersion) {
        Schematic.Builder builder = Schematic.builder(SIDE, 2, SIDE).dataVersion(dataVersion);
        for (int x = 0; x < SIDE; x++) {
            for (int z = 0; z < SIDE; z++) {
                builder.block(x, 0, z, "minecraft:cobblestone");
                builder.block(x, 1, z, "minecraft:mossy_cobblestone");
            }
        }
        builder.block(1, 1, 1, "minecraft:water[level=0]");
        builder.block(0, 1, 0, CHEST);
        return builder.build();
    }

    /** A floor of nether bricks with a fence round a blaze spawner, a chest in one corner. */
    static Schematic fortress(int dataVersion) {
        Schematic.Builder builder = Schematic.builder(SIDE, 2, SIDE).dataVersion(dataVersion);
        for (int x = 0; x < SIDE; x++) {
            for (int z = 0; z < SIDE; z++) {
                builder.block(x, 0, z, "minecraft:nether_bricks");
                builder.block(
                        x,
                        1,
                        z,
                        "minecraft:nether_brick_fence[east=false,north=false,south=false,waterlogged=false,west=false]");
            }
        }
        builder.block(1, 1, 1, "minecraft:spawner");
        builder.block(2, 1, 2, CHEST);
        return builder.build();
    }

    /** A pillar of obsidian with a chest on it and end rods at its foot. */
    static Schematic pillar(int dataVersion) {
        Schematic.Builder builder = Schematic.builder(SIDE, SIDE, SIDE).dataVersion(dataVersion);
        for (int x = 0; x < SIDE; x++) {
            for (int z = 0; z < SIDE; z++) {
                builder.block(x, 0, z, "minecraft:end_stone_bricks");
            }
        }
        builder.block(1, 1, 1, "minecraft:obsidian");
        builder.block(1, 2, 1, CHEST);
        builder.block(0, 1, 0, "minecraft:end_rod[facing=up]");
        builder.block(2, 1, 2, "minecraft:end_rod[facing=up]");
        return builder.build();
    }
}
