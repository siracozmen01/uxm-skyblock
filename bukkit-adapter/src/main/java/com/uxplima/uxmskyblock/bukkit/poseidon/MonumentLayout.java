package com.uxplima.uxmskyblock.bukkit.poseidon;

import com.uxplima.uxmlib.schematic.Schematic;

/**
 * The sunken temple a monument start lays under a Poseidon island: a prismarine hall on the sea floor, its walls of
 * dark prismarine opened by a door on each side, sea lanterns at its corners and around its middle, and a chest at
 * its heart. The island stands on its roof, over an opening in the middle; a swimmer comes in by the doors.
 *
 * <p>The plugin writes it as {@link #FILE} on its first start, and the monument group of modules/poseidon.conf lays
 * that file, so an operator who wants another temple edits the file rather than code.
 */
public final class MonumentLayout {

    /** The name the monument group knows the file by. */
    public static final String NAME = "monument";

    /** Where the file stands under the plugin's folder. */
    public static final String FILE = "schematics/" + NAME + ".schem";

    /** How wide and long the temple is. */
    static final int SIDE = 15;

    /** How tall the temple is, floor to roof: it fits under the island in the water the shipped ocean leaves. */
    static final int HEIGHT = 6;

    private static final String BRICKS = "minecraft:prismarine_bricks";
    private static final String DARK = "minecraft:dark_prismarine";
    private static final String PILLAR = "minecraft:prismarine";
    private static final String LANTERN = "minecraft:sea_lantern";
    private static final String SLAB = "minecraft:prismarine_brick_slab[type=bottom,waterlogged=false]";
    private static final String CHEST = "minecraft:chest[facing=north,type=single,waterlogged=false]";

    private MonumentLayout() {}

    /** The temple as a schematic of the server's data version. */
    public static Schematic shipped(int dataVersion) {
        int last = SIDE - 1;
        int middle = SIDE / 2;
        Schematic.Builder builder = Schematic.builder(SIDE, HEIGHT, SIDE).dataVersion(dataVersion);
        for (int x = 0; x < SIDE; x++) {
            for (int z = 0; z < SIDE; z++) {
                boolean edge = x == 0 || z == 0 || x == last || z == last;
                boolean corner = (x == 0 || x == last) && (z == 0 || z == last);
                builder.block(x, 0, z, BRICKS);
                if (corner) {
                    for (int y = 1; y < HEIGHT - 1; y++) {
                        builder.block(x, y, z, PILLAR);
                    }
                    builder.block(x, HEIGHT - 1, z, LANTERN);
                    continue;
                }
                if (edge) {
                    boolean door = Math.abs((x == 0 || x == last ? z : x) - middle) <= 1;
                    for (int y = 1; y < HEIGHT - 1; y++) {
                        if (!door || y > 2) {
                            builder.block(x, y, z, DARK);
                        }
                    }
                }
                // The roof: open over the middle, under the island that stands on it.
                if (Math.abs(x - middle) > 1 || Math.abs(z - middle) > 1) {
                    builder.block(x, HEIGHT - 1, z, edge ? BRICKS : SLAB);
                }
            }
        }
        // Lanterns ring the middle of the floor, and the chest stands at its heart.
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) == 2 && (dx == 0 || dz == 0 || Math.abs(dx) == Math.abs(dz))) {
                    builder.block(middle + dx, 0, middle + dz, LANTERN);
                }
            }
        }
        builder.block(middle, 1, middle, CHEST);
        return builder.build();
    }
}
