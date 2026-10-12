package com.uxplima.uxmskyblock.bukkit.config;

import java.time.Duration;
import java.util.Objects;

import com.uxplima.uxmlib.condition.action.ActionList;
import org.jspecify.annotations.Nullable;

/**
 * What happens at one exact break within a OneBlock phase, as the phase file writes it under {@code moments}: the
 * block it turns into, a creature on it, the loot table a container it turns into is filled from, an action list run
 * for the breaker, and a line shown over the block for a while.
 *
 * @param block the block, as a pool names one, in place of the one drawn; none keeps the draw
 * @param creature the creature, as a pool names one, brought onto the block besides any drawn
 * @param loot the loot table of {@code loot/} a container block is filled from when first opened
 * @param actions run for the breaker, as an effect list is
 * @param text the line shown over the block, MiniMessage or a {@code @key} read in the breaker's language
 * @param textTime how long the line stays
 */
public record OneBlockMoment(
        @Nullable String block,
        @Nullable String creature,
        @Nullable String loot,
        ActionList actions,
        @Nullable String text,
        Duration textTime) {

    /** How long a moment's line stays over the block, when the file says nothing. */
    public static final Duration TEXT_TIME = Duration.ofSeconds(10);

    public OneBlockMoment {
        Objects.requireNonNull(actions, "actions");
        Objects.requireNonNull(textTime, "textTime");
        if (textTime.isNegative() || textTime.isZero()) {
            throw new IllegalArgumentException("a line stays longer than nothing: " + textTime);
        }
    }
}
