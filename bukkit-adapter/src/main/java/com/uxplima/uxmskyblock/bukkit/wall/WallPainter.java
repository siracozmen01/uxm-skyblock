package com.uxplima.uxmskyblock.bukkit.wall;

import org.bukkit.Chunk;
import org.bukkit.World;

import com.uxplima.uxmskyblock.core.domain.wall.WallSegment;

/** What stands a wall in the world and takes it down, on the thread that owns the chunk. */
public interface WallPainter {

    /** Stands {@code wall} in {@code style}, marked as the wall of {@code owner}. */
    void draw(World world, String owner, WallSegment wall, WallStyle style);

    /** Takes down every wall of {@code owner} in {@code chunk}, and answers how many pieces. */
    int clear(Chunk chunk, String owner);
}
