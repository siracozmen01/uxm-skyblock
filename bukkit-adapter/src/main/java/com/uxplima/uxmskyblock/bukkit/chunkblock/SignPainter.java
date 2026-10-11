package com.uxplima.uxmskyblock.bukkit.chunkblock;

import org.bukkit.Chunk;
import org.bukkit.World;

import net.kyori.adventure.text.Component;

import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkPos;

/** Puts the sign over a closed chunk into the world and takes it away, on the thread that owns the chunk. */
public interface SignPainter {

    /** Stands the sign of {@code chunk}, reading {@code text}, at {@code spot}, marked as {@code owner}'s. */
    void draw(World world, String owner, ChunkPos chunk, ChunkSigns.Spot spot, Component text);

    /** Takes down every sign of {@code owner} in {@code chunk}, and answers how many pieces. */
    int clear(Chunk chunk, String owner);
}
