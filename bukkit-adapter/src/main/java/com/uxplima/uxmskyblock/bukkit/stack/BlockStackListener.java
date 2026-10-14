package com.uxplima.uxmskyblock.bukkit.stack;

import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.inventory.ItemStack;

import com.uxplima.uxmskyblock.bukkit.config.StackingConfiguration;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.core.application.worth.IslandWorthService;
import com.uxplima.uxmskyblock.core.domain.island.Island;

/**
 * Stacks a block placed on a block of its kind, gives a stack back one block at a time, and keeps a stack where it
 * stands.
 *
 * <p>Each block a stack stands for counts toward the island's worth as a placed block would, and a line above the
 * stack shows how many it is. A piston does not move a stack and an explosion does not break one: the stack is kept
 * under the place it stands, so a block moved off it would leave its count behind for whatever stood there next.
 */
public final class BlockStackListener implements Listener {

    private final Supplier<StackingConfiguration> configuration;
    private final IslandProtectionListener islands;
    private final IslandWorthService worth;
    private final StackWorld world;

    public BlockStackListener(
            Supplier<StackingConfiguration> configuration,
            IslandProtectionListener islands,
            IslandWorthService worth,
            StackWorld world) {
        this.configuration = Objects.requireNonNull(configuration, "configuration must not be null");
        this.islands = Objects.requireNonNull(islands, "islands must not be null");
        this.worth = Objects.requireNonNull(worth, "worth must not be null");
        this.world = Objects.requireNonNull(world, "world must not be null");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        Block against = event.getBlockAgainst();
        Material kind = event.getBlockPlaced().getType();
        StackingConfiguration rules = configuration.get();
        if (player.isSneaking() || against.getType() != kind || !rules.stacks(kind)) {
            return;
        }
        Optional<Island> island = islands.findIslandAt(against.getLocation());
        int size = BlockStacks.size(against);
        if (island.isEmpty() || size >= rules.most()) {
            return;
        }
        event.setCancelled(true);
        if (player.getGameMode() != GameMode.CREATIVE) {
            ItemStack held = player.getInventory().getItem(event.getHand());
            held.setAmount(held.getAmount() - 1);
        }
        BlockStacks.set(against, size + 1);
        worth.recordBlockPlace(island.get().id(), kind.getKey().toString(), 1);
        world.show(against, size + 1);
    }

    /** A block placed the usual way where a stack's count was left behind starts again from one. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlacedPlainly(BlockPlaceEvent event) {
        Block placed = event.getBlockPlaced();
        if (BlockStacks.size(placed) > 1) {
            BlockStacks.set(placed, 1);
            world.show(placed, 1);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        int size = BlockStacks.size(block);
        if (size <= 1) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        Material kind = block.getType();
        if (player.getGameMode() != GameMode.CREATIVE) {
            world.giveBack(block, player);
        }
        BlockStacks.set(block, size - 1);
        islands.findIslandAt(block.getLocation())
                .ifPresent(island ->
                        worth.recordBlockBreak(island.id(), kind.getKey().toString(), 1));
        world.show(block, size - 1);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPush(BlockPistonExtendEvent event) {
        if (anyStacked(event.getBlocks())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPull(BlockPistonRetractEvent event) {
        if (anyStacked(event.getBlocks())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(block -> BlockStacks.size(block) > 1);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(block -> BlockStacks.size(block) > 1);
    }

    private static boolean anyStacked(Collection<Block> blocks) {
        for (Block block : blocks) {
            if (BlockStacks.size(block) > 1) {
                return true;
            }
        }
        return false;
    }
}
