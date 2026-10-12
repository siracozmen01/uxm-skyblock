package com.uxplima.uxmskyblock.bukkit.oneblock;

import java.util.Objects;

import org.bukkit.Material;

import com.uxplima.uxmskyblock.bukkit.schematic.IslandStart;
import com.uxplima.uxmskyblock.core.application.gamemode.CreationActionProvider;
import com.uxplima.uxmskyblock.core.application.oneblock.OneBlockService;

/**
 * Builds a OneBlock island: one block, where the island's players arrive standing, drawn from the first
 * phase. A preset asks for it by writing {@value #ACTION} in its {@code start} list.
 *
 * <p>The island is a OneBlock island from here on, and the block is recorded before it is set, so a
 * break that follows at once is counted against it.
 */
public final class OneBlockStart implements CreationActionProvider<IslandStart> {

    /** The name a preset's start list writes for this action. */
    public static final String ACTION = "uxm:oneblock";

    private final OneBlockService service;
    private final OneBlockDraws draws;

    public OneBlockStart(OneBlockService service) {
        this(service, OneBlockDraws.PLAIN);
    }

    public OneBlockStart(OneBlockService service, OneBlockDraws draws) {
        this.service = Objects.requireNonNull(service, "service must not be null");
        this.draws = Objects.requireNonNull(draws, "draws must not be null");
    }

    @Override
    public String actionId() {
        return ACTION;
    }

    @Override
    public void apply(IslandStart start) {
        String first = service.start(start.islandId(), start.centerX(), start.y(), start.centerZ());
        draws.lay(start.world(), start.centerX(), start.y(), start.centerZ(), first);
        // Nobody broke anything yet, so a rarity has nobody to fire for.
        var unused = draws.fill(
                start.world(),
                start.centerX(),
                start.y(),
                start.centerZ(),
                first,
                service.phases().phases().getFirst().key());
    }

    /** The block a phase drew, or dirt when the operator wrote something that is not a block. */
    static Material blockOf(String drawn) {
        Material material = Material.matchMaterial(drawn);
        return material != null && material.isBlock() ? material : Material.DIRT;
    }
}
