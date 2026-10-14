package com.uxplima.uxmskyblock.bukkit.stack;

import org.bukkit.block.Block;
import org.bukkit.entity.Player;

/** What a stack does in the world around it: the line that shows its size, and the block it gives back. */
public interface StackWorld {

    /** Shows {@code size} on the line above {@code block}, or takes the line away for a block of one. */
    void show(Block block, int size);

    /** Gives {@code breaker} one block of the stack back, as breaking it with what they hold would. */
    void giveBack(Block block, Player breaker);
}
