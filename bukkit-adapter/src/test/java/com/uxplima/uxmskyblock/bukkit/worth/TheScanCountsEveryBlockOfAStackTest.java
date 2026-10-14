package com.uxplima.uxmskyblock.bukkit.worth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

import com.uxplima.uxmskyblock.bukkit.stack.BlockStacks;
import com.uxplima.uxmskyblock.bukkit.test.InlineSchedulerPort;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A stacked block is one block in the world and stands for its whole stack in the island's worth. */
class TheScanCountsEveryBlockOfAStackTest extends MockBukkitHarness {

    @Test
    @DisplayName("A stack counts every block it stands for, a stack outside the island or of an untracked kind none")
    void aStackCountsWhole() {
        World world = server.addSimpleWorld("skyblock_world");
        Block stacked = world.getBlockAt(4, 64, 4);
        stacked.setType(Material.DIAMOND_BLOCK);
        BlockStacks.set(stacked, 5);
        Block single = world.getBlockAt(5, 64, 4);
        single.setType(Material.DIAMOND_BLOCK);
        Block untracked = world.getBlockAt(6, 64, 4);
        untracked.setType(Material.GOLD_BLOCK);
        BlockStacks.set(untracked, 9);
        Block outside = world.getBlockAt(14, 64, 4);
        outside.setType(Material.DIAMOND_BLOCK);
        BlockStacks.set(outside, 7);

        FoliaIslandChunkScanner scanner =
                new FoliaIslandChunkScanner(new InlineSchedulerPort(), Set.of("minecraft:diamond_block"), Set.of());
        Map<String, Integer> counted = new HashMap<>(
                scanner.stackedBeyondOne(stacked.getChunk(), world, IslandBounds.fromCenterAndRadius(4, 4, 6), 0, 320));
        assertThat(scanner.stackedBeyondOne(
                        stacked.getChunk(), world, IslandBounds.fromCenterAndRadius(4, 4, 6), 65, 320))
                .describedAs("below the heights scanned")
                .isEmpty();
        assertThat(scanner.stackedBeyondOne(
                        stacked.getChunk(), world, IslandBounds.fromCenterAndRadius(4, 4, 6), 0, 64))
                .describedAs("above the heights scanned")
                .isEmpty();

        assertThat(counted).containsExactlyEntriesOf(Map.of("minecraft:diamond_block", 4));
    }
}
