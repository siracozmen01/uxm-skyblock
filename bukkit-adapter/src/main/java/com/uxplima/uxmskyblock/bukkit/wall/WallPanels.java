package com.uxplima.uxmskyblock.bukkit.wall;

import java.util.Objects;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;

import net.kyori.adventure.text.Component;

import com.uxplima.uxmskyblock.core.domain.wall.WallSegment;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Puts the entities of a wall into the world and takes them away. Every call runs on the thread that owns the chunk
 * the wall stands in.
 *
 * <p>A wall entity is never saved: it leaves with its chunk and is drawn again when the chunk loads, so a wall
 * follows what the island holds now and never what it held when the server stopped. Each carries the name of what it
 * walls in, so the walls of one island are taken away without touching another's.
 */
public final class WallPanels implements WallPainter {

    private static final Logger LOGGER = Logger.getLogger(WallPanels.class.getName());

    /** The mark a wall entity carries: the name of what it walls in. */
    static final NamespacedKey OWNER = new NamespacedKey("uxmskyblock", "wall");

    /** How far away a wall is drawn, as a share of the distance a client draws entities at. */
    static final float VIEW_RANGE = 2.0f;

    private final int bottom;
    private final int top;
    private final int slice;

    public WallPanels(int bottom, int top, int slice) {
        this.bottom = bottom;
        this.top = top;
        this.slice = slice;
    }

    public WallPanels(WallConfiguration config) {
        this(config.bottom(), config.top(), config.slice());
    }

    @Override
    public void draw(World world, String owner, WallSegment wall, WallStyle style) {
        Objects.requireNonNull(world, "world must not be null");
        Objects.requireNonNull(owner, "owner must not be null");
        BlockData block = style.kind() == WallStyle.Kind.BLOCK ? blockOf(style) : null;
        if (style.kind() == WallStyle.Kind.BLOCK && block == null) {
            return;
        }
        for (PanelShape.Panel panel : PanelShape.of(wall, style.kind(), bottom, top, slice)) {
            Location at = new Location(world, panel.x(), panel.y(), panel.z(), panel.yaw(), 0f);
            Transformation shape = new Transformation(
                    new Vector3f(panel.moveX(), panel.moveY(), panel.moveZ()),
                    new Quaternionf(),
                    new Vector3f(panel.scaleX(), panel.scaleY(), panel.scaleZ()),
                    new Quaternionf());
            if (block != null) {
                world.spawn(at, BlockDisplay.class, display -> {
                    display.setBlock(block);
                    dress(display, owner, panel, shape);
                });
            } else {
                world.spawn(at, TextDisplay.class, display -> {
                    display.text(Component.text(" "));
                    display.setBackgroundColor(Color.fromARGB(style.argb()));
                    display.setBillboard(Display.Billboard.FIXED);
                    display.setShadowed(false);
                    display.setSeeThrough(false);
                    dress(display, owner, panel, shape);
                });
            }
        }
    }

    @Override
    public int clear(Chunk chunk, String owner) {
        int removed = 0;
        for (Entity entity : chunk.getEntities()) {
            if (owner.equals(entity.getPersistentDataContainer().get(OWNER, PersistentDataType.STRING))) {
                entity.remove();
                removed++;
            }
        }
        return removed;
    }

    /** What a wall entity is, whatever it is drawn with: never saved, lit, and marked. */
    private static void dress(Display display, String owner, PanelShape.Panel panel, Transformation shape) {
        display.setPersistent(false);
        display.setTransformation(shape);
        display.setDisplayWidth(panel.width());
        display.setDisplayHeight(panel.height());
        display.setViewRange(VIEW_RANGE);
        display.setBrightness(new Display.Brightness(15, 15));
        display.getPersistentDataContainer().set(OWNER, PersistentDataType.STRING, owner);
    }

    private static @org.jspecify.annotations.Nullable BlockData blockOf(WallStyle style) {
        try {
            return Bukkit.createBlockData(style.block());
        } catch (IllegalArgumentException unknown) {
            LOGGER.warning(() -> "modules/walls.conf: the style " + style.id() + " names the block '" + style.block()
                    + "', which this server does not know, so it draws no wall.");
            return null;
        }
    }
}
