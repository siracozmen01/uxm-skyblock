package com.uxplima.uxmskyblock.bukkit.config;

import java.util.List;
import java.util.Objects;

import com.uxplima.uxmlib.condition.action.ActionList;

/**
 * What a OneBlock phase runs when something happens to it, as its file writes it under {@code began},
 * {@code finished} or {@code first-finished}: a list run once for the player whose break it was, for commands and
 * rewards, and a list run for every member of the island who is online, for what they all should hear.
 *
 * @param breaker run once, for the breaker
 * @param members run for each member online, the breaker among them
 */
public record OneBlockLists(ActionList breaker, ActionList members) {

    /** The events a phase writes lists for. */
    public static final List<String> EVENTS = List.of("began", "finished", "first-finished");

    public OneBlockLists {
        Objects.requireNonNull(breaker, "breaker");
        Objects.requireNonNull(members, "members");
    }

    /** Whether the phase wrote nothing for the event, so the shipped fallback in effects.conf runs instead. */
    public boolean isEmpty() {
        return breaker.actions().isEmpty() && members.actions().isEmpty();
    }
}
