package com.uxplima.uxmskyblock.bukkit.cave;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.IntFunction;

import com.uxplima.uxmlib.schematic.Schematic;

/**
 * The structures a CaveBlock island's rock holds, built in code and written as schematics on the plugin's first start,
 * so an operator who wants another edits the file rather than code. Inside each, cave air is the hollow a structure
 * carves out of the rock; air is where the rock is left as it stands.
 */
public final class CaveLayouts {

    /** Each shipped structure by the name its file is written under, as schematics/&lt;name&gt;.schem. */
    public static final Map<String, IntFunction<Schematic>> SHIPPED = shipped();

    static final String HOLLOW = "minecraft:cave_air";
    private static final String CHEST = "minecraft:chest[facing=north,type=single,waterlogged=false]";

    private CaveLayouts() {}

    private static Map<String, IntFunction<Schematic>> shipped() {
        Map<String, IntFunction<Schematic>> all = new LinkedHashMap<>();
        all.put("cave_dungeon", CaveLayouts::dungeon);
        all.put("cave_mineshaft", CaveLayouts::mineshaft);
        all.put("cave_geode", CaveLayouts::geode);
        all.put("cave_trial_chamber", CaveLayouts::trialChamber);
        return all;
    }

    /** A 9 by 6 by 9 room of cobblestone and mossy cobblestone, a spawner in its middle and two chests by its walls. */
    static Schematic dungeon(int dataVersion) {
        int side = 9;
        int height = 6;
        Schematic.Builder builder = Schematic.builder(side, height, side).dataVersion(dataVersion);
        for (int x = 0; x < side; x++) {
            for (int z = 0; z < side; z++) {
                for (int y = 0; y < height; y++) {
                    boolean shell = x == 0 || z == 0 || x == side - 1 || z == side - 1 || y == 0 || y == height - 1;
                    String stone =
                            (x * 7 + y * 3 + z * 5) % 3 == 0 ? "minecraft:mossy_cobblestone" : "minecraft:cobblestone";
                    builder.block(x, y, z, shell ? stone : HOLLOW);
                }
            }
        }
        builder.block(side / 2, 1, side / 2, "minecraft:spawner");
        builder.block(1, 1, side / 2, CHEST.replace("facing=north", "facing=east"));
        builder.block(side - 2, 1, side / 2, CHEST.replace("facing=north", "facing=west"));
        return builder.build();
    }

    /**
     * A 21 long corridor 3 wide and 4 high, framed in oak every fourth block, with a rail along its floor, cobwebs in
     * its corners and a chest at one end.
     */
    static Schematic mineshaft(int dataVersion) {
        int length = 21;
        Schematic.Builder builder = Schematic.builder(5, 5, length).dataVersion(dataVersion);
        for (int z = 0; z < length; z++) {
            boolean frame = z % 4 == 0;
            for (int x = 1; x <= 3; x++) {
                for (int y = 1; y <= 3; y++) {
                    builder.block(x, y, z, HOLLOW);
                }
                builder.block(x, 0, z, "minecraft:oak_planks");
            }
            builder.block(2, 1, z, "minecraft:rail[shape=north_south,waterlogged=false]");
            if (frame) {
                builder.block(1, 1, z, "minecraft:oak_fence[waterlogged=false]");
                builder.block(1, 2, z, "minecraft:oak_fence[waterlogged=false]");
                builder.block(3, 1, z, "minecraft:oak_fence[waterlogged=false]");
                builder.block(3, 2, z, "minecraft:oak_fence[waterlogged=false]");
                for (int x = 1; x <= 3; x++) {
                    builder.block(x, 3, z, "minecraft:oak_planks");
                }
            } else if (z % 4 == 2) {
                builder.block(1, 3, z, "minecraft:cobweb");
            }
        }
        builder.block(1, 1, length - 2, CHEST.replace("facing=north", "facing=east"));
        return builder.build();
    }

    /**
     * An 11 wide sphere: smooth basalt outside, calcite within it, amethyst within that, budding amethyst here and
     * there with clusters growing into its hollow.
     */
    static Schematic geode(int dataVersion) {
        int side = 11;
        double centre = (side - 1) / 2.0;
        Schematic.Builder builder = Schematic.builder(side, side, side).dataVersion(dataVersion);
        for (int x = 0; x < side; x++) {
            for (int y = 0; y < side; y++) {
                for (int z = 0; z < side; z++) {
                    double distance =
                            Math.sqrt(Math.pow(x - centre, 2) + Math.pow(y - centre, 2) + Math.pow(z - centre, 2));
                    String block;
                    if (distance > 5.4) {
                        continue;
                    } else if (distance > 4.6) {
                        block = "minecraft:smooth_basalt";
                    } else if (distance > 3.8) {
                        block = "minecraft:calcite";
                    } else if (distance > 3.0) {
                        block = (x + 2 * y + 3 * z) % 5 == 0
                                ? "minecraft:budding_amethyst"
                                : "minecraft:amethyst_block";
                    } else {
                        block = HOLLOW;
                    }
                    builder.block(x, y, z, block);
                }
            }
        }
        builder.block(5, 2, 5, "minecraft:amethyst_cluster[facing=up,waterlogged=false]");
        builder.block(5, 8, 5, "minecraft:amethyst_cluster[facing=down,waterlogged=false]");
        builder.block(2, 5, 5, "minecraft:large_amethyst_bud[facing=east,waterlogged=false]");
        builder.block(8, 5, 5, "minecraft:medium_amethyst_bud[facing=west,waterlogged=false]");
        return builder.build();
    }

    /**
     * An 11 by 7 by 11 hall of tuff bricks with a floor of polished tuff and copper, a trial spawner in its middle,
     * copper bulbs in its corners and a chest at its far wall.
     */
    static Schematic trialChamber(int dataVersion) {
        int side = 11;
        int height = 7;
        int last = side - 1;
        Schematic.Builder builder = Schematic.builder(side, height, side).dataVersion(dataVersion);
        for (int x = 0; x < side; x++) {
            for (int z = 0; z < side; z++) {
                for (int y = 0; y < height; y++) {
                    boolean shell = x == 0 || z == 0 || x == last || z == last || y == height - 1;
                    String block;
                    if (y == 0) {
                        block = (x + z) % 2 == 0 ? "minecraft:polished_tuff" : "minecraft:waxed_cut_copper";
                    } else if (shell) {
                        block = y == 3 ? "minecraft:chiseled_tuff_bricks" : "minecraft:tuff_bricks";
                    } else {
                        block = HOLLOW;
                    }
                    builder.block(x, y, z, block);
                }
            }
        }
        for (int[] corner : new int[][] {{1, 1}, {1, last - 1}, {last - 1, 1}, {last - 1, last - 1}}) {
            builder.block(corner[0], 1, corner[1], "minecraft:waxed_copper_bulb[lit=true,powered=false]");
        }
        builder.block(side / 2, 1, side / 2, "minecraft:trial_spawner");
        builder.block(side / 2, 1, last - 1, CHEST);
        return builder.build();
    }
}
