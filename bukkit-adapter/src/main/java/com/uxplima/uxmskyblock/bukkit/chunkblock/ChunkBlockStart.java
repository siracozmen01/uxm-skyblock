package com.uxplima.uxmskyblock.bukkit.chunkblock;

import java.util.Objects;

import com.uxplima.uxmskyblock.bukkit.schematic.IslandStart;
import com.uxplima.uxmskyblock.core.application.chunkblock.ChunkBlockService;
import com.uxplima.uxmskyblock.core.application.gamemode.CreationActionProvider;

/**
 * The creation action that makes an island a ChunkBlock island: its territory starts as the chunk
 * the island's centre stands in, where the magic block is set. Run in the island's Nether or End, as a dimension's
 * start list names it, it starts that dimension's own web.
 */
public final class ChunkBlockStart implements CreationActionProvider<IslandStart> {

    /** The name a preset's start list writes for this action. */
    public static final String ACTION = "uxm:chunkblock";

    private final ChunkBlockService service;
    private final java.util.function.Function<String, java.util.Optional<ChunkBlockService>> webs;

    public ChunkBlockStart(ChunkBlockService service) {
        this(service, world -> java.util.Optional.empty());
    }

    /**
     * @param service the overworld's web
     * @param webs the web of another dimension, by the name of its world
     */
    public ChunkBlockStart(
            ChunkBlockService service,
            java.util.function.Function<String, java.util.Optional<ChunkBlockService>> webs) {
        this.service = Objects.requireNonNull(service, "service must not be null");
        this.webs = Objects.requireNonNull(webs, "webs must not be null");
    }

    @Override
    public String actionId() {
        return ACTION;
    }

    @Override
    public void apply(IslandStart start) {
        webs.apply(start.world().getName()).orElse(service).start(start.islandId(), start.centerX(), start.centerZ());
    }
}
