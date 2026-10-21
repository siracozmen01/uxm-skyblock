package com.uxplima.uxmskyblock.bukkit.chunkblock;

import java.util.function.Consumer;
import java.util.function.Function;

import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.Player;

import net.kyori.adventure.text.Component;

import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkPos;

/** Puts the sign over a closed chunk up and takes it down, on the thread that owns the chunk. */
public interface SignPainter {

    /**
     * Stands the sign of {@code chunk} at {@code spot}, as {@code owner}'s: each viewer reads {@code text} as it
     * answers for them, and a click on it is handed to {@code clicked}.
     */
    void draw(
            World world,
            String owner,
            ChunkPos chunk,
            ChunkSigns.Spot spot,
            Function<Player, Component> text,
            Consumer<Player> clicked);

    /** Takes down every sign of {@code owner} in {@code chunk}, and answers how many. */
    int clear(Chunk chunk, String owner);

    /** Takes down whatever stands in {@code chunk}, whoever's it is, as the chunk unloads; answers how many. */
    int unloaded(Chunk chunk);
}
