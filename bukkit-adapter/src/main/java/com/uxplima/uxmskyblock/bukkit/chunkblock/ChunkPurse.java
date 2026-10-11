package com.uxplima.uxmskyblock.bukkit.chunkblock;

import org.bukkit.entity.Player;

import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkRequirement;

/**
 * What a player carries toward a chunk's price: their money, a currency they hold, and items. Every call reaches
 * into the player, so it runs on the thread that owns them.
 */
public interface ChunkPurse {

    /** How much of {@code line} the player carries now, in the line's own units. */
    long carried(Player player, ChunkRequirement line);

    /** Takes up to {@code wanted} of {@code line} from the player, and answers how much was taken. */
    long take(Player player, ChunkRequirement line, long wanted);

    /** Gives {@code amount} of {@code line} back to the player, what does not fit in their inventory at their feet. */
    void giveBack(Player player, ChunkRequirement line, long amount);

    /** Whether the player hands {@code line} in themselves, rather than the island paying it. */
    static boolean handedIn(ChunkRequirement line) {
        return switch (line) {
            case ChunkRequirement.Money money -> money.from() == ChunkRequirement.Source.PLAYER;
            case ChunkRequirement.Currency currency -> currency.from() == ChunkRequirement.Source.PLAYER;
            case ChunkRequirement.Items items -> true;
            default -> false;
        };
    }
}
