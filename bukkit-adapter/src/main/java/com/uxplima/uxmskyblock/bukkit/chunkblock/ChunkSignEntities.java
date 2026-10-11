package com.uxplima.uxmskyblock.bukkit.chunkblock;

import java.util.Objects;
import java.util.Optional;

import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;

import net.kyori.adventure.text.Component;

import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkPos;

/**
 * The sign over a closed chunk as entities: a line of text that turns to face whoever reads it, and an interaction
 * box under it that a click lands on. Neither is ever saved: they leave with their chunk and are drawn again when it
 * loads. Both carry the island they belong to and the chunk they price.
 */
public final class ChunkSignEntities implements SignPainter {

    /** The mark a sign entity carries: the island it belongs to. */
    static final NamespacedKey OWNER = new NamespacedKey("uxmskyblock", "chunk_sign");

    /** The chunk a sign prices, written as {@code x,z}. */
    static final NamespacedKey CHUNK = new NamespacedKey("uxmskyblock", "chunk_sign_at");

    /** How far away a sign is drawn, as a share of the distance a client draws entities at. */
    static final float VIEW_RANGE = 1.0f;

    /** The box a click lands on, in blocks. */
    static final float BOX_WIDTH = 1.2f;

    static final float BOX_HEIGHT = 2.2f;

    /** How far over the box the text floats. */
    static final double TEXT_RISE = 2.4;

    @Override
    public void draw(World world, String owner, ChunkPos chunk, ChunkSigns.Spot spot, Component text) {
        Objects.requireNonNull(world, "world must not be null");
        Objects.requireNonNull(owner, "owner must not be null");
        String at = chunk.x() + "," + chunk.z();
        world.spawn(new Location(world, spot.x(), spot.y() + TEXT_RISE, spot.z()), TextDisplay.class, display -> {
            display.text(text);
            display.setBillboard(Display.Billboard.CENTER);
            display.setAlignment(TextDisplay.TextAlignment.CENTER);
            display.setShadowed(true);
            // It stands behind the wall where open ground ends, and a wall panel hides what is behind it.
            display.setSeeThrough(true);
            display.setViewRange(VIEW_RANGE);
            display.setBrightness(new Display.Brightness(15, 15));
            mark(display, owner, at);
        });
        world.spawn(new Location(world, spot.x(), spot.y(), spot.z()), Interaction.class, box -> {
            box.setInteractionWidth(BOX_WIDTH);
            box.setInteractionHeight(BOX_HEIGHT);
            box.setResponsive(true);
            mark(box, owner, at);
        });
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

    /** The island and the chunk a sign entity stands for, when it is one. */
    static Optional<Marked> marked(Entity entity) {
        String owner = entity.getPersistentDataContainer().get(OWNER, PersistentDataType.STRING);
        String at = entity.getPersistentDataContainer().get(CHUNK, PersistentDataType.STRING);
        if (owner == null || at == null) {
            return Optional.empty();
        }
        String[] parts = at.split(",", -1);
        try {
            return Optional.of(new Marked(owner, new ChunkPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]))));
        } catch (NumberFormatException | ArrayIndexOutOfBoundsException wrong) {
            return Optional.empty();
        }
    }

    /** What a sign entity stands for: the island, by its id, and the chunk it prices. */
    record Marked(String owner, ChunkPos chunk) {}

    private static void mark(Entity entity, String owner, String at) {
        entity.setPersistent(false);
        entity.getPersistentDataContainer().set(OWNER, PersistentDataType.STRING, owner);
        entity.getPersistentDataContainer().set(CHUNK, PersistentDataType.STRING, at);
    }
}
