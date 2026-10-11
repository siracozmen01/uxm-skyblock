package com.uxplima.uxmskyblock.bukkit.wall;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.uxplima.uxmskyblock.core.domain.wall.WallSegment;

/**
 * Where the entities that draw one stretch of wall stand, and how each is stretched. Plain arithmetic, so the
 * shape is tested without a server.
 *
 * <p>Each entity stands just inside the closed ground, so it belongs to the chunk whose wall it is and leaves with
 * that chunk. A colour panel is drawn from one side only, so it is two, back to back. A block panel is a thin box,
 * seen from both sides, so it is one.
 */
public final class PanelShape {

    /** How far inside the closed ground an entity stands, so it falls in that ground's chunk. */
    static final double INSIDE = 0.01;

    /** How thick a block panel is. */
    static final float THICKNESS = 0.02f;

    /** How wide a colour panel of scale one is: the background of one space. */
    static final float TEXT_WIDTH = 0.125f;

    /** How tall a colour panel of scale one is. */
    static final float TEXT_HEIGHT = 0.25f;

    /** How far a colour panel's background starts below its entity. */
    static final float TEXT_DROP = 0.0f;

    /**
     * One entity of a wall.
     *
     * @param x where it stands
     * @param y where it stands
     * @param z where it stands
     * @param yaw which way it faces
     * @param moveX how far its drawing is moved from where it stands, along x
     * @param moveY along y
     * @param moveZ along z
     * @param scaleX how far its drawing is stretched, along x
     * @param scaleY along y
     * @param scaleZ along z
     * @param width how wide the box a client tests it against is, so a long panel is not hidden while in sight
     * @param height how tall that box is
     */
    public record Panel(
            double x,
            double y,
            double z,
            float yaw,
            float moveX,
            float moveY,
            float moveZ,
            float scaleX,
            float scaleY,
            float scaleZ,
            float width,
            float height) {}

    private PanelShape() {}

    /** The entities that draw {@code wall} in {@code kind}, piece by piece from {@code bottom} to {@code top}. */
    public static List<Panel> of(WallSegment wall, WallStyle.Kind kind, int bottom, int top, int slice) {
        Objects.requireNonNull(wall, "wall must not be null");
        Objects.requireNonNull(kind, "kind must not be null");
        List<Panel> panels = new ArrayList<>();
        for (int y = bottom; y < top; y += slice) {
            float height = Math.min(slice, top - y);
            switch (kind) {
                case COLOUR -> {
                    panels.add(colour(wall, y, height, false));
                    panels.add(colour(wall, y, height, true));
                }
                case BLOCK -> panels.add(block(wall, y, height));
            }
        }
        return List.copyOf(panels);
    }

    /** Where an entity of {@code wall} stands: the middle of the stretch, just inside the closed ground. */
    private static double[] standing(WallSegment wall) {
        double middle = (wall.from() + wall.to()) / 2.0;
        return switch (wall.side()) {
            case NORTH -> new double[] {middle, wall.line() + INSIDE};
            case SOUTH -> new double[] {middle, wall.line() - INSIDE};
            case WEST -> new double[] {wall.line() + INSIDE, middle};
            case EAST -> new double[] {wall.line() - INSIDE, middle};
        };
    }

    /**
     * One side of a colour panel. A text display faces south at yaw 0 and grows to its right and up from its
     * middle, so a panel along x faces south or north, and one along z faces west or east.
     */
    private static Panel colour(WallSegment wall, int y, float height, boolean back) {
        double[] at = standing(wall);
        float yaw = wall.side().alongX() ? (back ? 180f : 0f) : (back ? -90f : 90f);
        float length = wall.length();
        return new Panel(
                at[0],
                y,
                at[1],
                yaw,
                0f,
                -TEXT_DROP,
                0f,
                length / TEXT_WIDTH,
                height / TEXT_HEIGHT,
                1f,
                length + 1f,
                height);
    }

    /** A thin box of a block, straddling the wall's plane, its corner moved back from where the entity stands. */
    private static Panel block(WallSegment wall, int y, float height) {
        double[] at = standing(wall);
        float length = wall.length();
        float half = THICKNESS / 2f;
        if (wall.side().alongX()) {
            return new Panel(
                    at[0],
                    y,
                    at[1],
                    0f,
                    (float) (wall.from() - at[0]),
                    0f,
                    (float) (wall.line() - at[1]) - half,
                    length,
                    height,
                    THICKNESS,
                    length + 1f,
                    height);
        }
        return new Panel(
                at[0],
                y,
                at[1],
                0f,
                (float) (wall.line() - at[0]) - half,
                0f,
                (float) (wall.from() - at[1]),
                THICKNESS,
                height,
                length,
                length + 1f,
                height);
    }
}
